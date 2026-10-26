/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.desklink.mobile.backends.lan;

import static org.desklink.mobile.helpers.NetworkHelperKt.isPrivateAddress;
import static org.desklink.mobile.helpers.BoundedLineReaderKt.readLineBounded;

import android.content.Context;
import android.net.Network;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONException;
import org.desklink.mobile.backends.BaseLink;
import org.desklink.mobile.backends.BaseLinkProvider;
import org.desklink.mobile.DeviceHost;
import org.desklink.mobile.DeviceInfo;
import org.desklink.mobile.helpers.DeviceHelper;
import org.desklink.mobile.helpers.security.SslHelper;
import org.desklink.mobile.helpers.ThreadHelper;
import org.desklink.mobile.helpers.TrustedDevices;
import org.desklink.mobile.helpers.TrustedNetworkHelper;
import org.desklink.mobile.NetworkPacket;
import org.desklink.mobile.ui.CustomDevicesActivity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;

import kotlin.text.Charsets;

/**
 * This LanLinkProvider creates {@link LanLink}s to other devices on the same
 * WiFi network. The first packet sent over a socket must be an
 * {@link DeviceInfo#toIdentityPacket()}.
 *
 * @see #identityPacketReceived(NetworkPacket, Socket, LanLink.ConnectionStarted, boolean)
 */
public class LanLinkProvider extends BaseLinkProvider {

    final static int UDP_PORT = 1716;
    public final static int MIN_PORT = 1716;
    public final static int MAX_PORT = 1764;

    /** The running provider, for QR pairing's direct dial. */
    @Nullable
    public static volatile LanLinkProvider instance;
    final static int PAYLOAD_TRANSFER_MIN_PORT = 1739;

    final static int MAX_IDENTITY_PACKET_SIZE = UnpairedLimits.MAX_IDENTITY_PACKET;
    final static int MAX_UDP_PACKET_SIZE = UnpairedLimits.MAX_IDENTITY_PACKET;
    /** How long to wait before rebuilding a UDP socket that errored. */
    final static long UDP_RECOVERY_DELAY_MILLIS = 1000L;

    final static long MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE = 1000L;

    private final Context context;

    final ConcurrentHashMap<String, LanLink> visibleDevices = new ConcurrentHashMap<>(); // Links by device id

    final static int MAX_RATE_LIMIT_ENTRIES = 255;
    final ConcurrentHashMap<String, Long> lastConnectionTimeByDeviceId = new ConcurrentHashMap<>();
    final ConcurrentHashMap<InetAddress, Long> lastConnectionTimeByIp = new ConcurrentHashMap<>();
    // TCP accepts are limited separately: sharing one map with UDP beacons
    // discarded the desktop's dial-back that arrives within a second of its
    // own beacon — exactly the connection discovery is trying to make.
    final ConcurrentHashMap<InetAddress, Long> lastTcpConnectionTimeByIp = new ConcurrentHashMap<>();

    private ServerSocket tcpServer;
    private DatagramSocket udpServer;

    private final MdnsDiscovery mdnsDiscovery;

    private volatile long lastBroadcast = 0;
    private final static long delayBetweenBroadcasts = 200;

    private volatile boolean listening = false;

    /** Every paired computer has a LAN link: nothing left to find by multicast (a second computer's discovery needs the lock). */
    private boolean allPairedLinked() {
        java.util.List<String> paired = TrustedDevices.INSTANCE.getAllTrustedDevices(context);
        if (paired.isEmpty()) return !visibleDevices.isEmpty();
        for (String id : paired) if (!visibleDevices.containsKey(id)) return false;
        return true;
    }

    public void onConnectionLost(BaseLink link) {
        String deviceId = link.getDeviceId();
        // A replaced socket can finish its reader after the replacement has
        // already been installed. Do not let that stale callback remove the
        // current bootstrap link from discovery state.
        if (visibleDevices.remove(deviceId, link)) linkRace.forget(deviceId);
        mdnsDiscovery.updateLinkState(() -> allPairedLinked());
        super.onConnectionLost(link);
        if (listening) {
            ThreadHelper.execute(() -> {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (listening) {
                    broadcastUdpIdentityPacket(null);
                }
            });
        }
    }

    Pair<NetworkPacket, Boolean> unserializeReceivedIdentityPacket(String message, Object source) {
        NetworkPacket identityPacket;
        try {
            identityPacket = NetworkPacket.unserialize(message);
        } catch (JSONException e) {
            Log.w("DeskLink/LanLinkProvider", "Invalid identity packet from " + source + ": " + e.getMessage());
            return null;
        }

        if (!DeviceInfo.isValidIdentityPacket(identityPacket)) {
            // Type only, never the body.
            Log.w("DeskLink/LanLinkProvider", "Invalid identity packet from " + source + " (type " + identityPacket.getType() + ")");
            return null;
        }

        final String deviceId = identityPacket.getString("deviceId");
        String myId = DeviceHelper.getDeviceId(context);
        if (deviceId.equals(myId)) {
            //Ignore my own broadcast
            return null;
        }

        if (rateLimitByDeviceId(deviceId)) {
            Log.i("LanLinkProvider", "Discarding second packet from the same device " + deviceId + " received too quickly");
            return null;
        }

        boolean deviceTrusted = TrustedDevices.isTrustedDevice(context, deviceId);
        // The desktop whose QR code the user just scanned is let through: the
        // scan itself is the user's consent, and its certificate is verified
        // against the code's fingerprint before anything is sent to it.
        boolean qrPairing = deviceId.equals(org.desklink.mobile.QrPairing.getPendingDesktopId());
        if (!deviceTrusted && !qrPairing && !TrustedNetworkHelper.isTrustedNetwork(context)) {
            Log.i("DeskLink/LanLinkProvider", "Ignoring identity packet because the device is not trusted and I'm not on a trusted network.");
            return null;
        }

        // A stranger costs a Device, a session and a link: only so many, and only so many from one address.
        if (!deviceTrusted && !unpairedLimits.admit(deviceId, source instanceof InetAddress ? (InetAddress) source : (source instanceof Socket ? ((Socket) source).getInetAddress() : null))) {
            Log.i("DeskLink/LanLinkProvider", "Too many unpaired devices: ignoring one");
            return null;
        }
        if (deviceTrusted) unpairedLimits.release(deviceId);

        return new Pair<>(identityPacket, deviceTrusted);
    }

    private final UnpairedLimits unpairedLimits = UnpairedLimits.shared;

    /** Looking at discovery beacons: four at a time, sixteen waiting, the rest dropped. */
    // Four core threads that time out when idle: a pool of 1 core and a 16-deep queue never grows past one thread.
    private final ThreadPoolExecutor discoveryExecutor = newDiscoveryExecutor();

    private static ThreadPoolExecutor newDiscoveryExecutor() {
        ThreadPoolExecutor pool = new org.desklink.mobile.helpers.Guarded.Pool(
                4, 4, 30_000, new LinkedBlockingQueue<>(16), "DeskLink-discovery", new ThreadPoolExecutor.AbortPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    //They received my UDP broadcast and are connecting to me. The first thing they send should be their identity packet.
    @WorkerThread
    private void tcpPacketReceived(Socket socket) throws IOException, CertificateException {

        InetAddress address = socket.getInetAddress();

        if (!isPrivateAddress(address)) {
            Log.i("LanLinkProvider", "Discarding TCP packet from a non-local IP");
            closeQuietly(socket);
            return;
        }

        configureSocket(socket);
        // Until it has identified itself a peer gets 5 s per read: without a
        // timeout a silent connection pinned this thread and socket forever.
        socket.setSoTimeout(5000);

        if (rateLimit(lastTcpConnectionTimeByIp, address)) {
            Log.i("LanLinkProvider", "Discarding second TCP packet from the same ip " + address + " received too quickly");
            closeQuietly(socket);
            return;
        }

        String message;
        try {
            // We don't use a BufferedInputStream on purpose, since BufferedReader reads ahead and would require
            // us to keep a single BufferedInputStream instance and pass it around to make sure we don't lose data.
            // This means we are readying byte by byte directly from the OS, which is slow, but only for the handshake.
            message = readLineBounded(socket.getInputStream(), MAX_IDENTITY_PACKET_SIZE);
            //Log.e("TcpListener", "Received TCP packet: " + message);
        } catch (Exception e) {
            Log.e("DeskLink/LanLinkProvider", "Exception while receiving TCP packet", e);
            closeQuietly(socket);
            return;
        }

        final Pair<NetworkPacket, Boolean> pair = unserializeReceivedIdentityPacket(message, socket.getInetAddress());
        if (pair == null) {
            closeQuietly(socket);
            return;
        }
        final NetworkPacket identityPacket = pair.first;
        final boolean deviceTrusted = pair.second;

        Log.i("DeskLink/LanLinkProvider", "identity packet received from a TCP connection from " + identityPacket.getString("deviceName"));

        String targetDeviceId = identityPacket.getStringOrNull("targetDeviceId");
        Integer targetProtocolVersion = identityPacket.getIntOrNull("targetProtocolVersion");
        if (targetDeviceId != null && !targetDeviceId.equals(DeviceHelper.getDeviceId(context))) {
            Log.e("DeskLink/LanLinkProvider","Received a connection request for a device that isn't me: " + targetDeviceId);
            closeQuietly(socket);
            return;
        }
        if (targetProtocolVersion != null && targetProtocolVersion != DeviceHelper.PROTOCOL_VERSION) {
            Log.e("DeskLink/LanLinkProvider","Received a connection request for a protocol version that isn't mine: " + targetProtocolVersion);
            closeQuietly(socket);
            return;
        }

        identityPacketReceived(identityPacket, socket, LanLink.ConnectionStarted.Locally, deviceTrusted);
    }

    boolean rateLimitByIp(InetAddress address) {
        return rateLimit(lastConnectionTimeByIp, address);
    }

    private static boolean rateLimit(ConcurrentHashMap<InetAddress, Long> times, InetAddress address) {
        long now = System.currentTimeMillis();
        Long last = times.get(address);
        if (last != null && (last + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE > now)) {
            return true;
        }
        times.put(address, now);
        if (times.size() > MAX_RATE_LIMIT_ENTRIES) {
            times.entrySet().removeIf(e -> e.getValue() + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE < now);
        }
        return false;
    }

    /** Stagger between parallel dials (happy eyeballs, contract B4). */
    static final long DIAL_STAGGER_MS = 250;

    /**
     * QR v2: dials [addresses] in parallel, one every {@link #DIAL_STAGGER_MS}; the first TCP
     * connection wins and the rest are closed. From there it is the same as answering the desk's
     * announcement: our identity, then TLS. The desk's certificate is pinned by QR pairing, which
     * sends its proof only to a link whose certificate matches the code.
     */
    public void dialDirect(String desktopId, List<InetAddress> addresses, int port) {
        final java.util.concurrent.atomic.AtomicBoolean won = new java.util.concurrent.atomic.AtomicBoolean(false);
        for (int i = 0; i < addresses.size(); i++) {
            final InetAddress address = addresses.get(i);
            final long delay = i * DIAL_STAGGER_MS;
            ThreadHelper.execute(() -> {
                try {
                    if (delay > 0) Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (won.get() || visibleDevices.containsKey(desktopId)) return;
                Socket socket = null;
                try {
                    socket = SocketFactory.getDefault().createSocket();
                    socket.connect(new java.net.InetSocketAddress(address, port), 3000);
                    if (won.getAndSet(true)) {
                        closeQuietly(socket);
                        return;
                    }
                    Log.i("DeskLink/LanLinkProvider", "QR dial reached " + address.getHostAddress());
                    configureSocket(socket);
                    NetworkPacket myIdentity = DeviceHelper.getDeviceInfo(context).toIdentityPacket();
                    myIdentity.set("targetDeviceId", desktopId);
                    myIdentity.set("targetProtocolVersion", String.valueOf(DeviceHelper.PROTOCOL_VERSION));
                    OutputStream out = socket.getOutputStream();
                    out.write(myIdentity.serialize().getBytes());
                    out.flush();
                    identityPacketReceived(qrIdentity(desktopId, port), socket, LanLink.ConnectionStarted.Remotely,
                            TrustedDevices.isTrustedDevice(context, desktopId));
                } catch (IOException | CertificateException | JSONException e) {
                    Log.i("DeskLink/LanLinkProvider", "QR dial to " + address.getHostAddress() + " failed: " + e.getMessage());
                    if (socket != null) closeQuietly(socket);
                }
            });
        }
    }

    private final DialBackoff dialBackoff = new DialBackoff();
    private final LinkRace linkRace = new LinkRace();
    private final java.util.Set<String> dialsInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Paired desks with no link: dial each one's last known addresses ourselves (at most three per
     * desk) instead of only announcing and waiting for the desk to dial back. An address that did
     * not answer is backed off ({@link DialBackoff}), so a desk that moved is not hammered.
     */
    @WorkerThread
    private void dialKnownAddresses() {
        // Same rule as broadcasting: nothing is announced to, or dialed on, a network that is not trusted.
        if (!TrustedNetworkHelper.isTrustedNetwork(context)) return;
        long now = android.os.SystemClock.elapsedRealtime();
        for (LastLanAddress.Dial dial : LastLanAddress.INSTANCE.dialTargets(context, new java.util.HashSet<>(visibleDevices.keySet()), dialBackoff, now, true)) {
            final String key = LastLanAddress.INSTANCE.key(dial);
            if (!dialsInFlight.add(key)) continue;
            ThreadHelper.execute(() -> {
                Socket socket = null;
                try {
                    if (visibleDevices.containsKey(dial.getDeviceId())) return;
                    socket = SocketFactory.getDefault().createSocket();
                    socket.connect(new java.net.InetSocketAddress(dial.getAddress(), dial.getPort()), 3000);
                    configureSocket(socket);
                    NetworkPacket myIdentity = DeviceHelper.getDeviceInfo(context).toIdentityPacket();
                    myIdentity.set("targetDeviceId", dial.getDeviceId());
                    myIdentity.set("targetProtocolVersion", String.valueOf(DeviceHelper.PROTOCOL_VERSION));
                    OutputStream out = socket.getOutputStream();
                    out.write(myIdentity.serialize().getBytes());
                    out.flush();
                    identityPacketReceived(qrIdentity(dial.getDeviceId(), dial.getPort()), socket, LanLink.ConnectionStarted.Remotely,
                            TrustedDevices.isTrustedDevice(context, dial.getDeviceId()));
                    // The pinned handshake finished (it throws otherwise): only now is the address good.
                    dialBackoff.succeeded(key);
                } catch (IOException | CertificateException | JSONException e) {
                    // A host that refuses or times out, and one that accepts TCP but fails the pinned
                    // handshake (another machine at that address), both wait before the next try.
                    dialBackoff.failed(key, android.os.SystemClock.elapsedRealtime());
                    Log.i("DeskLink/LanLinkProvider", "Dial to a known address failed: " + e.getClass().getSimpleName());
                    if (socket != null) closeQuietly(socket);
                } finally {
                    dialsInFlight.remove(key);
                }
            });
        }
    }

    /** What the QR code says about the desk, standing in for its unencrypted announcement. */
    private static NetworkPacket qrIdentity(String desktopId, int port) {
        NetworkPacket identity = new NetworkPacket(NetworkPacket.PACKET_TYPE_IDENTITY);
        identity.set("deviceId", desktopId);
        identity.set("protocolVersion", DeviceHelper.PROTOCOL_VERSION);
        identity.set("tcpPort", port);
        return identity;
    }

    private static boolean isOwnAddress(InetAddress address) {
        try {
            return address.isAnyLocalAddress() || java.net.NetworkInterface.getByInetAddress(address) != null;
        } catch (java.net.SocketException e) {
            return false;
        }
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    boolean rateLimitByDeviceId(String deviceId) {
        long now = System.currentTimeMillis();
        Long last =  lastConnectionTimeByDeviceId.get(deviceId);
        if (last != null && (last + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE > now)) {
            return true;
        }
        lastConnectionTimeByDeviceId.put(deviceId, now);
        if (lastConnectionTimeByDeviceId.size() > MAX_RATE_LIMIT_ENTRIES) {
            lastConnectionTimeByDeviceId.entrySet().removeIf(e -> e.getValue() + MILLIS_DELAY_BETWEEN_CONNECTIONS_TO_SAME_DEVICE < now);
        }
        return false;
    }

    /**
     * Discovery refreshes peer metadata; it must not replace a healthy
     * bootstrap socket. Replacing that socket also replaces the session
     * generation and would make an authenticated WebRTC peer stale.
     */
    static boolean shouldOpenDiscoveredBootstrap(boolean hasActiveBootstrap) {
        return !hasActiveBootstrap;
    }

    //I've received their broadcast and should connect to their TCP socket and send my identity.
    @WorkerThread
    private void udpPacketReceived(InetAddress address, String message) {

        final Pair<NetworkPacket, Boolean> pair = unserializeReceivedIdentityPacket(message, address);
        if (pair == null) {
            return;
        }
        final NetworkPacket identityPacket = pair.first;
        final boolean deviceTrusted = pair.second;

        final String deviceId = identityPacket.getString("deviceId");
        // A beacon from a restarted desktop must open a fresh link even though
        // one is "active": that one belongs to the dead previous process.
        final LanLink existing = visibleDevices.get(deviceId);
        final boolean restarted = existing != null && DeviceInfo.peerRestarted(
                existing.getDeviceInfo().instanceId, identityPacket.getString("instanceId", ""));
        if (!restarted && !shouldOpenDiscoveredBootstrap(existing != null)) {
            Log.d(
                "DeskLink/LanLinkProvider",
                "Keeping the active bootstrap link for " + deviceId + " after discovery refresh"
            );
            return;
        }

        Log.i("DeskLink/LanLinkProvider", "Broadcast identity packet received from " + identityPacket.getString("deviceName"));

        int tcpPort = identityPacket.getInt("tcpPort", MIN_PORT);
        if (tcpPort < MIN_PORT || tcpPort > MAX_PORT) {
            Log.e("LanLinkProvider", "TCP port outside of DeskLink's configured range");
            return;
        }

        Socket socket = null;
        try {
            // Bounded: an unconnected socket plus explicit timeout, since the
            // default connect waits out the OS SYN retries (minutes).
            socket = SocketFactory.getDefault().createSocket();
            socket.connect(new java.net.InetSocketAddress(address, tcpPort), 3000);
            configureSocket(socket);

            DeviceInfo myDeviceInfo = DeviceHelper.getDeviceInfo(context);
            NetworkPacket myIdentity = myDeviceInfo.toIdentityPacket();
            myIdentity.set("targetDeviceId", identityPacket.getString("deviceId"));
            myIdentity.set("targetProtocolVersion", identityPacket.getString("protocolVersion"));

            OutputStream out = socket.getOutputStream();
            out.write(myIdentity.serialize().getBytes());
            out.flush();

            identityPacketReceived(identityPacket, socket, LanLink.ConnectionStarted.Remotely, deviceTrusted);
        } catch (IOException | CertificateException | JSONException e) {
            Log.e("LanLinkProvider", "Exception receiving incoming UDP connection", e);
            if (socket != null) {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * Applies the socket options every device link needs.
     *
     * This must be called on accepted sockets as well as sockets we open. It
     * previously ran only on the outgoing path, so a link the desktop
     * established to this device had no keepalive at all and a vanished peer
     * was never detected — the link simply sat there looking healthy.
     *
     * Java cannot tune the keepalive interval, so the operating system default
     * applies here; the desktop client sets an aggressive interval on its side,
     * which is what actually detects a dead link quickly.
     */
    private void configureSocket(Socket socket) {
        try {
            socket.setKeepAlive(true);
        } catch (SocketException e) {
            Log.e("DeskLink/LanLinkProvider", "Could not enable TCP keepalive", e);
        }
        try {
            // Feature packets are small and latency-sensitive.
            socket.setTcpNoDelay(true);
        } catch (SocketException e) {
            Log.e("DeskLink/LanLinkProvider", "Could not disable Nagle buffering", e);
        }
    }

    /**
     * Called when a new 'identity' packet is received. Those are passed here by
     * {@link #tcpPacketReceived(Socket)} and {@link #udpPacketReceived(DatagramPacket)}.
     * Should be called on a new thread since it blocks until the handshake is completed.
     *
     * @param identityPacket    identity of a remote device
     * @param socket            a new Socket, which should be used to receive packets from the remote device
     * @param connectionStarted which side started this connection
     * @param deviceTrusted     whether the packet comes from a trusted device
     */
    @WorkerThread
    private void identityPacketReceived(final NetworkPacket identityPacket, final Socket socket, final LanLink.ConnectionStarted connectionStarted, final boolean deviceTrusted) throws IOException, CertificateException {
        final String deviceId = identityPacket.getString("deviceId");
        final int protocolVersion = identityPacket.getInt("protocolVersion");

        if (deviceTrusted && isProtocolDowngrade(deviceId, protocolVersion)) {
            Log.w("DeskLink/LanLinkProvider", "Refusing to connect to a device using an older protocol version:" + protocolVersion);
            closeQuietly(socket);
            return;
        }

        if (deviceTrusted && !TrustedDevices.isCertificateStored(context, deviceId)) {
            Log.e("DeskLink/LanLinkProvider", "Device trusted but no cert stored. This should not happen.");
            closeQuietly(socket);
            return;
        }

        Log.i("DeskLink/LanLinkProvider", "Starting SSL handshake with " + deviceId + " trusted:" + deviceTrusted);

        // If I'm the TCP server I will be the SSL client and vice-versa.
        final boolean clientMode = (connectionStarted == LanLink.ConnectionStarted.Locally);
        final SSLSocket sslSocket = SslHelper.convertToSslSocket(context, socket, deviceId, deviceTrusted, clientMode);
        sslSocket.addHandshakeCompletedListener(event -> {
            // Start a new thread because some Android versions don't allow calling sslSocket.getOutputStream() from the callback
            ThreadHelper.execute(() -> {
                String mode = clientMode ? "client" : "server";
                try {
                    NetworkPacket secureIdentityPacket;
                    if (protocolVersion >= 8) {
                        DeviceInfo myDeviceInfo = DeviceHelper.getDeviceInfo(context);
                        NetworkPacket myIdentity = myDeviceInfo.toIdentityPacket();

                        OutputStream writer = sslSocket.getOutputStream();
                        writer.write(myIdentity.serialize().getBytes(Charsets.UTF_8));
                        writer.flush();
                        String line = readLineBounded(sslSocket.getInputStream(), MAX_IDENTITY_PACKET_SIZE);
                        // Do not trust the identity packet we received unencrypted
                        secureIdentityPacket = NetworkPacket.unserialize(line);
                        if (!DeviceInfo.isValidIdentityPacket(secureIdentityPacket)) {
                            Log.e("DeskLink/LanLinkProvider", "Identity packet isn't valid");
                            sslSocket.close();
                            return;
                        }
                        int newProtocolVersion = secureIdentityPacket.getInt("protocolVersion");
                        if (newProtocolVersion != protocolVersion) {
                            Log.e("DeskLink/LanLinkProvider", "Protocol version changed half-way through the handshake: " + protocolVersion + " -> " + newProtocolVersion);
                            sslSocket.close();
                            return;
                        }
                        String newDeviceId = secureIdentityPacket.getString("deviceId");
                        if (!newDeviceId.equals(deviceId)) {
                            Log.e("DeskLink/LanLinkProvider", "Device ID changed half-way through the handshake: " + deviceId + " -> " + newDeviceId);
                            sslSocket.close();
                            return;
                        }
                    } else {
                        secureIdentityPacket = identityPacket;
                    }
                    Certificate certificate = event.getPeerCertificates()[0];
                    DeviceInfo deviceInfo = DeviceInfo.fromIdentityPacketAndCert(secureIdentityPacket, certificate);
                    Log.i("DeskLink/LanLinkProvider", "Handshake as " + mode + " successful with " + deviceInfo.name + " secured with " + event.getCipherSuite());
                    addOrUpdateLink(sslSocket, deviceInfo, !clientMode);
                } catch (JSONException e) {
                    Log.e("DeskLink/LanLinkProvider", "Remote device doesn't correctly implement protocol version 8", e);
                    try { sslSocket.close(); } catch (IOException ignored) { }
                } catch (IOException e) {
                    Log.e("DeskLink/LanLinkProvider", "Handshake as " + mode + " failed with " + deviceId, e);
                    try { sslSocket.close(); } catch (IOException ignored) { }
                }
            });
        });

        //Handshake is blocking, so do it on another thread and free this thread to keep receiving new connection
        Log.d("LanLinkProvider", "Starting handshake");
        sslSocket.startHandshake();
        Log.d("LanLinkProvider", "Handshake done");
    }

    private boolean isProtocolDowngrade(String deviceId, int protocolVersion) {
        int lastKnownProtocolVersion = DeviceInfo.loadProtocolVersionFromSettings(context, deviceId);
        return lastKnownProtocolVersion > protocolVersion;
    }

    /**
     * Add or update a link in the {@link #visibleDevices} map.
     *
     * @param socket           a new Socket, which should be used to send and receive packets from the remote device
     * @param deviceInfo       remote device info
     * @throws IOException if an exception is thrown by {@link LanLink#reset(SSLSocket, DeviceInfo)}
     */
    @WorkerThread
    private void addOrUpdateLink(SSLSocket socket, DeviceInfo deviceInfo, boolean weDialed) throws IOException {
        LanLink link = visibleDevices.get(deviceInfo.id);
        // An inbound dial and our own redial within a second are one reconnection: both sides keep
        // the link dialed by the lower device id, so neither closes the socket the other kept.
        if (link != null && !linkRace.arrive(deviceInfo.id, DeviceHelper.getDeviceId(context), weDialed, android.os.SystemClock.elapsedRealtime())) {
            Log.i("DeskLink/LanLinkProvider", "Dropping the duplicate link to " + deviceInfo.id + " (dialed within 1 s of the kept one)");
            closeQuietly(socket);
            return;
        }
        if (link == null) linkRace.arrive(deviceInfo.id, DeviceHelper.getDeviceId(context), weDialed, android.os.SystemClock.elapsedRealtime());
        if (link != null) {
            if (!link.getDeviceInfo().certificate.equals(deviceInfo.certificate)) {
                Log.e("LanLinkProvider", "LanLink was asked to replace a socket but the certificate doesn't match, aborting");
                return;
            }
            // Update existing link
            Log.d("DeskLink/LanLinkProvider", "Reusing same link for device " + deviceInfo.id);
            link.reset(socket, deviceInfo);
            onConnectionReplaced(link);
            onDeviceInfoUpdated(deviceInfo);
        } else {
            // Create a new link
            Log.d("DeskLink/LanLinkProvider", "Creating a new link for device " + deviceInfo.id);
            link = new LanLink(context, deviceInfo, this, socket);
            visibleDevices.put(deviceInfo.id, link);
            onConnectionReceived(link);
        }
        mdnsDiscovery.updateLinkState(() -> allPairedLinked()); // the multicast lock is let go only when every paired computer is linked
        // Remembered only now, after the pinned handshake proved who is at this address. The port is
        // known when we dialed (it is the desk's listener); an accepted socket's port is not.
        LastLanAddress.INSTANCE.remember(context, deviceInfo.id, socket.getInetAddress(), weDialed ? socket.getPort() : 0);
    }

    public LanLinkProvider(Context context) {
        instance = this;
        this.context = context;
        this.mdnsDiscovery = new MdnsDiscovery(context, this);
    }

    private void setupUdpListener() {
        try {
            udpServer = new DatagramSocket(null);
            udpServer.setReuseAddress(true);
            udpServer.setBroadcast(true);
            udpServer.bind(new InetSocketAddress(UDP_PORT));
        } catch (SocketException e) {
            // We ignore this exception and continue without being able to receive broadcasts instead of crashing the app.
            Log.e("LanLinkProvider", "Error binding udp server. We can send udp broadcasts but not receive them", e);
            if (udpServer != null) {
                try { udpServer.close(); } catch (Exception ignored) {}
                udpServer = null;
            }
            return;
        }
        ThreadHelper.execute(() -> {
            Log.i("UdpListener", "Starting UDP listener");
            // One small buffer for the life of the listener: receive() fills it, and what is kept is copied out.
            final DatagramPacket packet = new DatagramPacket(new byte[MAX_UDP_PACKET_SIZE], MAX_UDP_PACKET_SIZE);
            while (listening) {
                packet.setLength(MAX_UDP_PACKET_SIZE);
                try {
                    udpServer.receive(packet);
                    final InetAddress from = packet.getAddress();
                    // Cheap checks on the receive thread, before anything is copied or queued: a flood of datagrams from
                    // one source, our own beacon, or a public address costs nothing more.
                    if (from == null || isOwnAddress(from) || !isPrivateAddress(from) || rateLimitByIp(from)) {
                        continue;
                    }
                    final String message = new String(packet.getData(), 0, packet.getLength(), Charsets.UTF_8);
                    try {
                        discoveryExecutor.execute(() -> {
                            try {
                                udpPacketReceived(from, message);
                            } catch (Exception e) {
                                Log.e("LanLinkProvider", "Unhandled exception receiving incoming UDP connection", e);
                            }
                        });
                    } catch (RejectedExecutionException full) {
                        // More beacons than can be looked at: the rest are dropped (the next one comes in seconds).
                    }
                } catch (IOException e) {
                    Log.e("LanLinkProvider", "UdpReceive exception", e);
                    // Rebuild the socket instead of retrying it. An errored
                    // DatagramSocket keeps failing immediately, and this used
                    // to loop straight back with no backoff and no teardown —
                    // pegging a core while calling onNetworkChange(null), which
                    // disconnected every LAN link, several times a second and
                    // forever. udpServer was never cleared either, so the
                    // `udpServer == null` recovery in onNetworkChange could
                    // never fire.
                    try { udpServer.close(); } catch (Exception ignored) {}
                    udpServer = null;
                    if (listening) {
                        try {
                            Thread.sleep(UDP_RECOVERY_DELAY_MILLIS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        setupUdpListener();
                    }
                    return;
                }
            }
            Log.w("UdpListener", "Stopping UDP listener");
        });
    }

    private void setupTcpListener() {
        try {
            tcpServer = openServerSocketOnFreePort(MIN_PORT);
        } catch (IOException e) {
            Log.e("LanLinkProvider", "Error creating tcp server", e);
            throw new RuntimeException(e);
        }
        ThreadHelper.execute(() -> {
            while (listening) {
                try {
                    Socket socket = tcpServer.accept();
                    configureSocket(socket);
                    ThreadHelper.execute(() -> {
                        try {
                            tcpPacketReceived(socket);
                        } catch (IOException | CertificateException e) {
                            try { socket.close(); } catch (IOException ignored) { }
                            Log.e("LanLinkProvider", "Exception receiving incoming TCP connection", e);
                        }
                    });
                } catch (Exception e) {
                    Log.e("LanLinkProvider", "TcpReceive exception", e);
                }
            }
            Log.w("TcpListener", "Stopping TCP listener");
        });

    }

    static ServerSocket openServerSocketOnFreePort(int minPort) throws IOException {
        int tcpPort = minPort;
        while (tcpPort <= MAX_PORT) {
            try {
                ServerSocket candidateServer = new ServerSocket(tcpPort);
                Log.i("DeskLink/LanLink", "Using port " + tcpPort);
                return candidateServer;
            } catch (IOException e) {
                tcpPort++;
                if (tcpPort == MAX_PORT) {
                    Log.e("DeskLink/LanLink", "No ports available");
                    throw e; //Propagate exception
                }
            }
        }
        throw new RuntimeException("This should not be reachable");
    }

    private void broadcastUdpIdentityPacket(@Nullable Network network) {
        ThreadHelper.execute(() -> {
            List<DeviceHost> hostList = CustomDevicesActivity
                    .getCustomDeviceList(context);

            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                hostList.add(DeviceHost.BROADCAST); //Default: broadcast.
            } else {
                Log.i("LanLinkProvider", "Current network isn't trusted, not broadcasting");
            }

            ArrayList<InetAddress> ipList = new ArrayList<>();
            // Paired desks with no link yet: also announce to where each was last reached.
            if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                for (InetAddress last : LastLanAddress.INSTANCE.targets(context, new java.util.HashSet<>(visibleDevices.keySet()), TrustedNetworkHelper.isTrustedNetwork(context))) {
                    if (!ipList.contains(last)) ipList.add(last);
                }
            }
            for (DeviceHost host : hostList) {
                try {
                    ipList.add(InetAddress.getByName(host.toString()));
                } catch (UnknownHostException e) {
                    Log.w("LanLinkProvider", "Failed: " + e.getClass().getSimpleName());
                }
            }

            if (ipList.isEmpty()) {
                return;
            }

            sendUdpIdentityPacket(ipList, network);
        });
        ThreadHelper.execute(this::dialKnownAddresses);
    }

    @WorkerThread
    public void sendUdpIdentityPacket(List<InetAddress> ipList, @Nullable Network network) {
        if (tcpServer == null || !tcpServer.isBound()) {
            Log.i("LanLinkProvider", "Won't broadcast UDP packet if TCP socket is not ready yet");
            return;
        }

        // TODO: In protocol version 8 this packet doesn't need to contain identity info
        //       since it will be exchanged after the socket is encrypted.
        DeviceInfo myDeviceInfo = DeviceHelper.getDeviceInfo(context);
        NetworkPacket identity = myDeviceInfo.toIdentityPacket();
        identity.set("tcpPort", tcpServer.getLocalPort());

        byte[] bytes;
        try {
            bytes = identity.serialize().getBytes(Charsets.UTF_8);
        } catch (JSONException e) {
            Log.e("DeskLink/LanLinkProvider", "Failed to serialize identity packet", e);
            return;
        }

        DatagramSocket socket;
        try {
            socket = new DatagramSocket();
            if (network != null) {
                try {
                    network.bindSocket(socket);
                } catch (IOException e) {
                    Log.w("LanLinkProvider", "Couldn't bind socket to the network");
                    Log.w("LanLinkProvider", "Failed: " + e.getClass().getSimpleName());
                }
            }
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
        } catch (SocketException e) {
            Log.e("DeskLink/LanLinkProvider", "Failed to create DatagramSocket", e);
            return;
        }

        for (InetAddress ip : ipList) {
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, ip, MIN_PORT));
                //Log.i("DeskLink/LanLinkProvider","Udp identity packet sent to address "+client);
            } catch (IOException e) {
                Log.e("DeskLink/LanLinkProvider", "Sending udp identity packet failed. Invalid address? (" + ip.toString() + ")", e);
            }
        }

        socket.close();
    }

    @Override
    public void onStart() {
        //Log.i("DeskLink/LanLinkProvider", "onStart");
        if (!listening) {
            listening = true;

            setupUdpListener();
            setupTcpListener();

            synchronized (mdnsDiscovery) {
                mdnsDiscovery.startDiscovering();
                if (TrustedNetworkHelper.isTrustedNetwork(context)) {
                    mdnsDiscovery.startAnnouncing();
                }
            }

            broadcastUdpIdentityPacket(null);
        }
    }

    @Override
    public void onNetworkChange(@Nullable Network network) {
        if (udpServer == null) {
            setupUdpListener();
        }

        // A socket bound to the previous interface will not recover merely
        // because discovery broadcasts are sent again. Close it now so the
        // device layer can establish one fresh authenticated link.
        //
        // Deliberately ahead of the rate limiter. Android delivers network
        // callbacks in bursts — a VPN coming up while Wi-Fi flaps — and
        // throttling this too meant the callback that actually moved the
        // interface could be the one dropped, leaving sockets bound to a dead
        // interface that nothing ever closed. Clearing twice is harmless; the
        // second pass has nothing left to disconnect.
        for (LanLink link : visibleDevices.values()) {
            link.disconnect();
        }
        visibleDevices.clear();
        dialBackoff.reset();

        announceBurst(network);
    }

    @Override
    public void refreshDiscovery() {
        if (udpServer == null) {
            setupUdpListener();
        }
        announceBurst(null);
    }

    // A single beacon is easily lost — UDP, a desktop listener not up yet, an
    // access point still settling — and the desktop's periodic beacon is 15 s
    // away, which was most of a cold start. So announce at 0, 0.3, 1, 3 and 7
    // s. A newer burst supersedes an older one instead of stacking with it.
    private static final long[] BURST_OFFSETS_MILLIS = {0, 300, 1_000, 3_000, 7_000};
    private final java.util.concurrent.atomic.AtomicLong burstGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    private void announceBurst(@Nullable Network network) {
        // Only the outbound churn is throttled.
        if (System.currentTimeMillis() < lastBroadcast + delayBetweenBroadcasts) {
            Log.i("LanLinkProvider", "announceBurst: relax cowboy");
            return;
        }
        lastBroadcast = System.currentTimeMillis();
        synchronized (mdnsDiscovery) {
            mdnsDiscovery.stopDiscovering();
            mdnsDiscovery.startDiscovering();
        }
        final long generation = burstGeneration.incrementAndGet();
        ThreadHelper.execute(() -> {
            long elapsed = 0;
            for (long offset : BURST_OFFSETS_MILLIS) {
                try {
                    Thread.sleep(offset - elapsed);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                elapsed = offset;
                if (!listening || burstGeneration.get() != generation) return;
                broadcastUdpIdentityPacket(network);
            }
        });
    }

    @Override
    public void onStop() {
        //Log.i("DeskLink/LanLinkProvider", "onStop");
        listening = false;
        synchronized (mdnsDiscovery) {
            mdnsDiscovery.stopAnnouncing();
            mdnsDiscovery.stopDiscovering();
        }
        try {
            tcpServer.close();
        } catch (Exception e) {
            Log.e("LanLink", "Exception", e);
        }
        try {
            udpServer.close();
        } catch (Exception e) {
            Log.e("LanLink", "Exception", e);
        }
    }

    @Override
    public String getName() {
        return "LanLinkProvider";
    }

    @Override
    public int getPriority() { return 20; }

    public int getTcpPort() { return tcpServer.getLocalPort(); }

}

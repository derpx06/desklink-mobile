/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.desklink.mobile.backends.lan;

import static org.desklink.mobile.helpers.BoundedLineReaderKt.readLineBounded;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.desklink.mobile.backends.BaseLink;
import org.desklink.mobile.backends.BaseLinkProvider;
import org.desklink.mobile.Device;
import org.desklink.mobile.DeviceInfo;
import org.desklink.mobile.helpers.security.SslHelper;
import org.desklink.mobile.helpers.ThreadHelper;
import org.desklink.mobile.NetworkPacket;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.NotYetConnectedException;
import java.security.cert.CertificateException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.desklink.mobile.helpers.TrustedDevices;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

import kotlin.text.Charsets;

public class LanLink extends BaseLink {

    /** What a paired peer may send in one line; a peer that is not paired gets {@link UnpairedLimits#MAX_UNPAIRED_LINE}. */
    final static int MAX_PACKET_SIZE = UnpairedLimits.MAX_PAIRED_LINE;
    /**
     * How long the link may hear nothing at all before it is declared dead.
     *
     * Tuning knob: long enough that a genuinely quiet link is not churned,
     * short enough to be worth having. Android's own TCP keepalive would
     * take about two hours and cannot be configured from Java.
     *
     * The desktop probes after 10 s of silence (daemon/mod.rs HEARTBEAT_IDLE),
     * so a live link is never quiet for long; 35 s is three missed probes.
     * At 120 s a zombie socket outlived a network switch by two minutes while
     * this side ignored the desktop's beacons and signaled into the dead link.
     */
    final static long IDLE_TIMEOUT_MILLIS = 35_000L;
    final static long UNPAIRED_LINK_LIFETIME_MILLIS = 5 * 60_000L;
    final static long MAX_PAYLOAD_SIZE = 4L * 1024L * 1024L * 1024L;
    public enum ConnectionStarted {
        Locally, Remotely
    }

    private DeviceInfo deviceInfo;

    private volatile SSLSocket socket = null;

    /** The address of the peer on the current socket, or null when there is none. */
    @androidx.annotation.Nullable
    public java.net.InetAddress getRemoteAddress() {
        SSLSocket current = socket;
        return current == null ? null : current.getInetAddress();
    }

    /** Guards writes to {@link #socket}, which several threads share. */
    private final Object writeLock = new Object();

    @Override
    public void disconnect() {
        Log.i("LanLink/Disconnect","socket:"+ (socket == null ? "none" : socket.hashCode()));
        try {
            if (socket != null) socket.close();
        } catch (IOException e) {
            Log.e("LanLink", "Error", e);
        }
    }

    //Returns the old socket
    @WorkerThread
    public SSLSocket reset(final SSLSocket newSocket, final DeviceInfo deviceInfo) throws IOException {

        this.deviceInfo = deviceInfo;

        SSLSocket oldSocket = socket;
        socket = newSocket;

        IOUtils.close(oldSocket); //This should cancel the readThread

        //Log.e("LanLink", "Start listening");
        //Create a thread to take care of incoming data for the new socket
        ThreadHelper.execute(() -> {
            try {
                BufferedInputStream stream = new BufferedInputStream(newSocket.getInputStream());
                // Survives read timeouts so a packet split by a stall is not
                // truncated. See readLineBounded.
                ByteArrayOutputStream carry = new ByteArrayOutputStream(1024);
                long lastActivityAt = SystemClock.elapsedRealtime();
                final long startedAt = lastActivityAt;
                while (true) {
                    // A stranger that keeps answering heartbeats but never pairs does not hold a slot for ever.
                    if (SystemClock.elapsedRealtime() - startedAt > UNPAIRED_LINK_LIFETIME_MILLIS && !TrustedDevices.isTrustedDevice(context, deviceInfo.id)) {
                        throw new IOException("An unpaired link that did not pair");
                    }
                    String packet;
                    try {
                        packet = readLineBounded(stream, maxLineNow(), carry);
                    } catch (SocketTimeoutException e) {
                        // SslHelper sets a 10s soTimeout, and this used to
                        // `continue` unconditionally. A peer that vanished
                        // without a FIN — laptop lid closed, AP rebooted — left
                        // this thread spinning for the ~2h07m it takes Android's
                        // untunable tcp_keepalive_time to notice, during which
                        // the device reported itself reachable, every send
                        // succeeded into a dead socket, and the service held an
                        // untimed PARTIAL_WAKE_LOCK.
                        //
                        // Java cannot set a keepalive interval, so silence is
                        // the only signal available here. Discovery re-dials
                        // immediately after, so a link dropped while merely idle
                        // costs a reconnect, not a disconnection.
                        if (SystemClock.elapsedRealtime() - lastActivityAt > IDLE_TIMEOUT_MILLIS) {
                            throw new IOException("No data from the peer in " + IDLE_TIMEOUT_MILLIS + "ms");
                        }
                        continue;
                    }
                    lastActivityAt = SystemClock.elapsedRealtime();
                    if (packet.isEmpty()) {
                        continue;
                    }
                    NetworkPacket np = NetworkPacket.unserialize(packet);
                    receivedNetworkPacket(np);
                }
            } catch (Exception e) {
                Log.i("LanLink", "Socket closed: " + newSocket.hashCode() + ". Reason: " + e.getMessage());
                try { Thread.sleep(300); } catch (InterruptedException ignored) {} // Wait a bit because we might receive a new socket meanwhile
                boolean thereIsaANewSocket = (newSocket != socket);
                if (!thereIsaANewSocket) {
                    Log.i("LanLink", "Socket closed and there's no new socket, disconnecting device");
                    getLinkProvider().onConnectionLost(LanLink.this);
                }
            }
        });

        return oldSocket;
    }

    /** The paired state is read per line, as the computer does: a link that pairs gets its larger limit at once. */
    private int maxLineNow() {
        return TrustedDevices.isTrustedDevice(context, deviceInfo.id) ? MAX_PACKET_SIZE : UnpairedLimits.MAX_UNPAIRED_LINE;
    }

    @WorkerThread
    public LanLink(@NonNull Context context, @NonNull DeviceInfo deviceInfo, @NonNull BaseLinkProvider linkProvider, @NonNull SSLSocket socket) throws IOException {
        super(context, linkProvider);
        reset(socket, deviceInfo);
    }

    @Override
    public String getName() {
        return "LanLink";
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return deviceInfo;
    }

    @WorkerThread
    @Override
    public boolean sendPacket(@NonNull NetworkPacket np, @NonNull final Device.SendPacketStatusCallback callback, boolean sendPayloadFromSameThread) {
        if (socket == null) {
            Log.e("DeskLink/sendPacket", "Not yet connected");
            callback.onFailure(new NotYetConnectedException());
            return false;
        }

        // Paired file bytes are WebRTC file-data messages only.  Keep the LAN
        // link available for discovery, pairing, and signed signaling, but do
        // not allow a caller to reintroduce the legacy payload socket by
        // bypassing Device.sendPacketBlocking().
        if (np.hasPayload()) {
            IOException error = new IOException("DeskLink file transfer requires WebRTC");
            Log.w("DeskLink/LanLink", error.getMessage());
            callback.onFailure(error);
            np.getPayload().close();
            return false;
        }

        try {

            //Log.e("LanLink/sendPacket", np.getType());

            // Serialize writes. Signed SDP/ICE is written from the WebRTC
            // callback and recovery threads while feature packets are written
            // from the outbound coroutine, and an unsynchronized write/flush
            // pair let those interleave — splicing two newline-delimited
            // packets into one line that the peer then discards. Losing an
            // offer that way stalls the whole handover.
            byte[] bytes = np.serialize().getBytes(Charsets.UTF_8);
            synchronized (writeLock) {
                OutputStream writer = socket.getOutputStream();
                writer.write(bytes);
                writer.flush();
            }
            if (!np.isCanceled()) {
                callback.onSuccess();
            } else {
                callback.onFailure(new IOException("DeskLink packet send cancelled"));
                return false;
            }
            return true;
        } catch (Exception e) {
            if (np.hasPayload()) {
                np.getPayload().close();
            }
            callback.onFailure(e);
            return false;
        }
    }

    private void sendPayload(NetworkPacket np, Device.SendPacketStatusCallback callback, ServerSocket server) throws IOException {
        Socket payloadSocket = null;
        OutputStream outputStream = null;
        InputStream inputStream;
        try {
            if (!np.isCanceled()) {
                //Wait a maximum of 10 seconds for the other end to establish a connection with our socket, close it afterwards
                server.setSoTimeout(10 * 1000);

                payloadSocket = server.accept();

                //Convert to SSL if needed
                payloadSocket = SslHelper.convertToSslSocket(context, payloadSocket, getDeviceId(), true, false);

                outputStream = payloadSocket.getOutputStream();
                inputStream = np.getPayload().getInputStream();

                String transferToken = np.getStringOrNull("transferToken");
                if (transferToken == null || transferToken.isEmpty() || transferToken.length() > 128) {
                    throw new IOException("Missing or invalid payload transfer token");
                }
                outputStream.write((transferToken + "\n").getBytes(StandardCharsets.UTF_8));

                Log.i("DeskLink/LanLink", "Beginning to send payload for " + np.getType());
                byte[] buffer = new byte[4096];
                int bytesRead;
                long size = np.getPayloadSize();
                long progress = 0;
                long timeSinceLastUpdate = -1;
                while (!np.isCanceled() && (bytesRead = inputStream.read(buffer)) != -1) {
                    //Log.e("ok",""+bytesRead);
                    progress += bytesRead;
                    outputStream.write(buffer, 0, bytesRead);
                    if (size > 0) {
                        if (timeSinceLastUpdate + 500 < System.currentTimeMillis()) { //Report progress every half a second
                            long percent = ((100 * progress) / size);
                            callback.onPayloadProgressChanged((int) percent);
                            timeSinceLastUpdate = System.currentTimeMillis();
                        }
                    }
                }
                outputStream.flush();
                Log.i("DeskLink/LanLink", "Finished sending payload (" + progress + " bytes written)");
                if (np.isCanceled()) {
                    callback.onFailure(new IOException("Payload transfer cancelled"));
                } else {
                    callback.onPayloadProgressChanged(100);
                    callback.onSuccess();
                }
            }
        } catch(SocketTimeoutException e) {
            Log.e("LanLink", "Socket for payload in packet " + np.getType() + " timed out. The other end didn't fetch the payload.");
            callback.onFailure(e);
        } catch(CertificateException | SSLHandshakeException e) {
            // The exception can be due to several causes. "Connection closed by peer" seems to be a common one.
            // If we could distinguish different cases we could react differently for some of them, but I haven't found how.
            Log.e("sendPacket","Payload SSLSocket failed");
            Log.e("sendPacket", "Payload transfer failed", e);
            callback.onFailure(e);
        } finally {
            try { server.close(); } catch (Exception ignored) { }
            try { IOUtils.close(payloadSocket); } catch (Exception ignored) { }
            np.getPayload().close();
            try { IOUtils.close(outputStream); } catch (Exception ignored) { }
        }
    }

    private void receivedNetworkPacket(NetworkPacket np) {

        if (np.hasPayloadTransferInfo()) {
            // Never open a legacy TCP/TLS payload connection. File control
            // and bytes arrive through the authenticated WebRTC channels.
            Log.w("DeskLink/LanLink", "Rejected legacy LAN file payload; WebRTC is required");
            return;
        }

        packetReceived(np);
    }

}

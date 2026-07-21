/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/

package org.desklink.mobile.backends.lan;

import static main.java.org.desklink.mobile.helpers.BoundedLineReaderKt.readLineBounded;

import android.content.Context;
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

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;

import kotlin.text.Charsets;
import main.java.org.desklink.mobile.helpers.LineTooLongException;

public class LanLink extends BaseLink {

    final static int MAX_PACKET_SIZE = 32 * 1024 * 1024;
    final static long MAX_PAYLOAD_SIZE = 4L * 1024L * 1024L * 1024L;

    public enum ConnectionStarted {
        Locally, Remotely
    }

    private DeviceInfo deviceInfo;

    private volatile SSLSocket socket = null;

    @Override
    public void disconnect() {
        Log.i("LanLink/Disconnect","socket:"+ socket.hashCode());
        try {
            socket.close();
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
                while (true) {
                    String packet;
                    try {
                        packet = readLineBounded(stream, MAX_PACKET_SIZE);
                    } catch (LineTooLongException | SocketTimeoutException e) {
                        continue;
                    }
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

        try {

            if (np.hasPayload() && np.getPayloadSize() > MAX_PAYLOAD_SIZE) {
                throw new IOException("Payload exceeds the maximum supported size");
            }

            //Prepare socket for the payload
            final ServerSocket server;
            if (np.hasPayload()) {
                server = LanLinkProvider.openServerSocketOnFreePort(LanLinkProvider.PAYLOAD_TRANSFER_MIN_PORT);
                JSONObject payloadTransferInfo = new JSONObject();
                payloadTransferInfo.put("port", server.getLocalPort());
                np.setPayloadTransferInfo(payloadTransferInfo);
                if (np.getStringOrNull("transferToken") == null) {
                    np.set("transferToken", UUID.randomUUID().toString());
                }
                if (np.getStringOrNull("transferId") == null) {
                    // Optional v9 field used by both sides to persist a
                    // resumable transfer without changing the packet envelope.
                    np.set("transferId", UUID.randomUUID().toString());
                }
            } else {
                server = null;
            }

            //Log.e("LanLink/sendPacket", np.getType());

            //Send body of the network packet
            try {
                OutputStream writer = socket.getOutputStream();
                writer.write(np.serialize().getBytes(Charsets.UTF_8));
                writer.flush();
            } catch (Exception e) {
                disconnect(); //main socket is broken, disconnect
                if (server != null) {
                    try { server.close(); } catch (Exception ignored) { }
                }
                throw e;
            }

            //Send payload
            if (server != null) {
                if (sendPayloadFromSameThread) {
                    sendPayload(np, callback, server);
                } else {
                    ThreadHelper.execute(() -> {
                        try {
                            sendPayload(np, callback, server);
                        } catch (IOException e) {
                            Log.e("LanLink/sendPacket", "Async sendPayload failed for packet of type " + np.getType(), e);
                            callback.onFailure(e);
                        }
                    });
                }
            }

            if (server == null && !np.isCanceled()) {
                callback.onSuccess();
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
            Socket payloadSocket = new Socket();
            try {
                if (np.getPayloadSize() < 0 || np.getPayloadSize() > MAX_PAYLOAD_SIZE) {
                    throw new IOException("Payload size is invalid");
                }
                int tcpPort = np.getPayloadTransferInfo().getInt("port");
                InetSocketAddress deviceAddress = (InetSocketAddress) socket.getRemoteSocketAddress();
                payloadSocket.connect(new InetSocketAddress(deviceAddress.getAddress(), tcpPort));
                payloadSocket = SslHelper.convertToSslSocket(context, payloadSocket, getDeviceId(), true, true);
                String expectedToken = np.getStringOrNull("transferToken");
                if (expectedToken == null || expectedToken.isEmpty() || expectedToken.length() > 128) {
                    throw new IOException("Missing or invalid payload transfer token");
                }
                InputStream tokenStream = payloadSocket.getInputStream();
                StringBuilder token = new StringBuilder();
                int tokenByte;
                while ((tokenByte = tokenStream.read()) != -1 && tokenByte != '\n') {
                    if (token.length() >= 128) {
                        throw new IOException("Payload transfer token is too long");
                    }
                    token.append((char) tokenByte);
                }
                if (!expectedToken.contentEquals(token)) {
                    throw new IOException("Payload transfer token does not match control packet");
                }
                np.setPayload(new NetworkPacket.Payload(payloadSocket, np.getPayloadSize()));
            } catch (Exception e) {
                try { payloadSocket.close(); } catch(Exception ignored) { }
                Log.e("DeskLink/LanLink", "Exception connecting to payload remote socket", e);
            }

        }

        packetReceived(np);
    }

}

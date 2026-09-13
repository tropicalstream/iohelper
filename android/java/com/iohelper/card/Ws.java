package com.iohelper.card;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A WebSocket client small enough to read in one sitting.
 *
 * Android ships no WebSocket client, and this build has no third-party
 * libraries - the same reason the ADB client is vendored. What GPT-Live's
 * session needs is modest: one TLS connection, JSON text frames both ways,
 * answer pings, close cleanly. That is RFC 6455's client side minus
 * extensions and subprotocols, about two hundred lines, and every byte of it
 * is here rather than behind a dependency nobody can inspect.
 *
 * Client frames are masked (the RFC requires it; servers drop unmasked ones),
 * server frames are not. Fragmented messages are reassembled. Binary frames
 * are read and dropped - the Live protocol never sends them.
 */
final class Ws {

    interface Listener {
        void onText(String text);

        void onClosed(int code, String reason);

        void onError(Exception e);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    /** Audio deltas are a few KB; anything near this is a protocol error. */
    private static final long MAX_FRAME = 16L << 20;

    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private final Listener listener;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean open = true;

    private Ws(Socket sock, InputStream in, OutputStream out, Listener listener) {
        this.sock = sock;
        this.in = in;
        this.out = out;
        this.listener = listener;
    }

    /** Connect, upgrade, and start delivering messages on a reader thread. */
    static Ws connect(URI uri, Map<String, String> headers, Listener l, int timeoutMs)
            throws IOException {
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), timeoutMs);
        plain.setTcpNoDelay(true);
        // Wrapping a connected socket with the host name is what sets SNI,
        // without which api.openai.com's front door serves the wrong cert.
        SSLSocket s = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                .createSocket(plain, host, port, true);
        s.setSoTimeout(timeoutMs);                  // handshake only; cleared below
        s.startHandshake();

        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        StringBuilder req = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                .append("Sec-WebSocket-Version: 13\r\n");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        req.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16);
        String response = readHeaders(in);
        String status = response.split("\r\n", 2)[0];
        if (!status.contains(" 101")) {
            throw new IOException("handshake refused: " + status);
        }
        if (!response.toLowerCase().contains("sec-websocket-accept: " + accept(key).toLowerCase())) {
            throw new IOException("bad Sec-WebSocket-Accept");
        }
        s.setSoTimeout(0);
        Ws ws = new Ws(s, in, out, l);
        Thread t = new Thread(ws::readLoop, "ws-read");
        t.setDaemon(true);
        t.start();
        return ws;
    }

    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int match = 0;
        while (match < 4) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("connection closed during handshake");
            }
            buf.write(b);
            match = (b == (match % 2 == 0 ? '\r' : '\n')) ? match + 1 : (b == '\r' ? 1 : 0);
            if (buf.size() > 65536) {
                throw new IOException("handshake response too large");
            }
        }
        return buf.toString("UTF-8");
    }

    private static String accept(String key) throws IOException {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.encodeToString(sha1.digest((key + GUID).getBytes(StandardCharsets.US_ASCII)),
                    Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    boolean isOpen() {
        return open;
    }

    /** Send one text message. Safe from any thread. */
    void send(String text) throws IOException {
        sendFrame(1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(int op, byte[] payload) throws IOException {
        synchronized (out) {
            ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
            f.write(0x80 | op);
            int len = payload.length;
            if (len < 126) {
                f.write(0x80 | len);
            } else if (len < 65536) {
                f.write(0x80 | 126);
                f.write(len >> 8);
                f.write(len);
            } else {
                f.write(0x80 | 127);
                for (int i = 7; i >= 0; i--) {
                    f.write((int) (((long) len >> (8 * i)) & 0xFF));
                }
            }
            byte[] mask = new byte[4];
            rnd.nextBytes(mask);
            f.write(mask);
            byte[] masked = new byte[len];
            for (int i = 0; i < len; i++) {
                masked[i] = (byte) (payload[i] ^ mask[i & 3]);
            }
            f.write(masked);
            out.write(f.toByteArray());
            out.flush();
        }
    }

    /** Close politely: a close frame, then the socket. Idempotent. */
    void close() {
        if (!open) {
            return;
        }
        open = false;
        try {
            sendFrame(8, new byte[]{0x03, (byte) 0xE8});    // 1000, normal
        } catch (IOException ignored) {
        }
        try {
            sock.close();
        } catch (IOException ignored) {
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private void readFully(byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) {
                throw new EOFException();
            }
            off += n;
        }
    }

    private void readLoop() {
        try {
            ByteArrayOutputStream frag = null;
            int fragOp = 0;
            while (open) {
                int b0 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                int b1 = readByte();
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) readByte() << 8) | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | readByte();
                    }
                }
                if (len > MAX_FRAME) {
                    throw new IOException("frame too large: " + len);
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
                switch (op) {
                    case 0:                                  // continuation
                        if (frag != null) {
                            frag.write(payload);
                            if (fin) {
                                deliver(fragOp, frag.toByteArray());
                                frag = null;
                            }
                        }
                        break;
                    case 1:
                    case 2:
                        if (fin) {
                            deliver(op, payload);
                        } else {
                            frag = new ByteArrayOutputStream();
                            frag.write(payload);
                            fragOp = op;
                        }
                        break;
                    case 8: {
                        int code = payload.length >= 2
                                ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1005;
                        String reason = payload.length > 2
                                ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
                        try {
                            sendFrame(8, payload);
                        } catch (IOException ignored) {
                        }
                        open = false;
                        listener.onClosed(code, reason);
                        return;
                    }
                    case 9:
                        sendFrame(10, payload);              // pong with the same body
                        break;
                    default:
                        break;                               // pong, reserved
                }
            }
        } catch (Exception e) {
            if (open) {
                open = false;
                listener.onError(e);
            }
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void deliver(int op, byte[] payload) {
        if (op == 1) {
            listener.onText(new String(payload, StandardCharsets.UTF_8));
        }
    }
}

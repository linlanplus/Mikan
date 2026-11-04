package top.niunaijun.blackbox.fake.network.vmess;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * One VMess connection, carrying a single TCP stream to a fixed destination.
 *
 * The handshake is VMess AEAD over plain TCP (optionally TLS): a sealed header naming the target,
 * then AES-128-GCM chunks in both directions. Each chunk is a big-endian length that counts the
 * GCM tag, the ciphertext, and the tag; a chunk whose length is exactly the tag size means end of
 * stream.
 */
public final class VmessSession {

    private static final byte VERSION = 0x01;
    private static final int COMMAND_TCP = 0x01;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_IPV6 = 0x03;
    private static final int OPTION_CHUNK_STREAM = 0x01;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int MAX_CHUNK = 16 * 1024 + VmessCrypto.GCM_TAG_BYTES;

    /**
     * Lets the host native hook be told to ignore the thread while the tunnel dials its own
     * server, so the tunnel is not routed back into itself. Left null in a plain JVM.
     */
    public interface Bypass {
        void enter();
        void exit();
    }

    private static volatile Bypass bypass;

    public static void setBypass(Bypass hook) {
        bypass = hook;
    }

    private final VmessNode node;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private ChunkOutputStream chunkOut;
    private ChunkInputStream chunkIn;

    private final byte[] requestBodyKey;
    private final byte[] requestBodyIv;
    private final byte[] responseBodyKey;
    private final byte[] responseBodyIv;

    /** Guards the one-time response header read; independent of the uplink write path. */
    private final Object responseHeaderLock = new Object();
    private final byte expectedResponseHeader;
    private boolean responseHeaderRead;

    private VmessSession(VmessNode node, Socket socket, InputStream in, OutputStream out,
                         byte[] requestBodyKey, byte[] requestBodyIv, byte expectedResponseHeader) {
        this.node = node;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.requestBodyKey = requestBodyKey;
        this.requestBodyIv = requestBodyIv;
        this.expectedResponseHeader = expectedResponseHeader;
        this.responseBodyKey = Arrays.copyOf(VmessKdf.sha256(requestBodyKey), 16);
        this.responseBodyIv = Arrays.copyOf(VmessKdf.sha256(requestBodyIv), 16);
    }

    /** Connects to the node, performs the handshake, and returns a ready stream pair. */
    public static VmessSession open(VmessNode node, InetAddress target, int targetPort) throws IOException {
        byte[] cmdKey = VmessCrypto.cmdKey(node.uuid);
        if (cmdKey == null) {
            throw new IOException("invalid uuid: " + node.uuid);
        }

        Socket socket = new Socket();
        try {
            Bypass hook = bypass;
            if (hook != null) hook.enter();
            try {
                socket.connect(new InetSocketAddress(node.host, node.port), CONNECT_TIMEOUT_MS);
            } finally {
                if (hook != null) hook.exit();
            }
            socket.setTcpNoDelay(true);
            if (node.tls) {
                socket = upgradeToTls(socket, node);
            }

            byte[] requestBodyKey = new byte[16];
            byte[] requestBodyIv = new byte[16];
            byte[] responseHeader = new byte[1];
            VmessCrypto.RANDOM.nextBytes(requestBodyKey);
            VmessCrypto.RANDOM.nextBytes(requestBodyIv);
            VmessCrypto.RANDOM.nextBytes(responseHeader);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] header = buildRequestHeader(requestBodyIv, requestBodyKey, responseHeader[0],
                    target, targetPort);
            byte[] authId = VmessCrypto.createAuthId(cmdKey, System.currentTimeMillis() / 1000L);
            byte[] sealed = VmessCrypto.sealRequestHeader(cmdKey, header, authId);
            out.write(sealed);
            out.flush();

            return new VmessSession(node, socket, in, out, requestBodyKey, requestBodyIv,
                    responseHeader[0]);
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        } catch (RuntimeException e) {
            closeQuietly(socket);
            throw new IOException("vmess handshake failed: " + e.getMessage(), e);
        }
    }

    private static Socket upgradeToTls(Socket plain, VmessNode node) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) factory.createSocket(plain, node.host, node.port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        parameters.setServerNames(java.util.Collections.singletonList(
                new javax.net.ssl.SNIHostName(node.sni)));
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(parameters);
        ssl.startHandshake();
        return ssl;
    }

    /** Version, keys, options, command, target address, padding, then an FNV-1a checksum over it all. */
    private static byte[] buildRequestHeader(byte[] requestBodyIv, byte[] requestBodyKey,
                                              byte responseHeader, InetAddress target, int port) {
        byte[] address = target.getAddress();
        int paddingLen = VmessCrypto.RANDOM.nextInt(16);

        int size = 1 + 16 + 16 + 1 + 1 + 1 + 1 + 1 + 1 + 2 + paddingLen + 4;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(size);
        buffer.write(VERSION);
        buffer.write(requestBodyIv, 0, 16);
        buffer.write(requestBodyKey, 0, 16);
        buffer.write(responseHeader);
        buffer.write(OPTION_CHUNK_STREAM);
        buffer.write((paddingLen << 4) | VmessCrypto.SECURITY_AES128_GCM);
        buffer.write(0x00);
        buffer.write(COMMAND_TCP);

        // VMess puts the port first, then the address type, then the address. It is not the SOCKS5
        // order, and 0x03 is IPv6 here even though 0x03 means a domain name in SOCKS5.
        buffer.write((port >>> 8) & 0xFF);
        buffer.write(port & 0xFF);
        buffer.write(address.length == 4 ? ATYP_IPV4 : ATYP_IPV6);
        buffer.write(address, 0, address.length);

        if (paddingLen > 0) {
            byte[] padding = new byte[paddingLen];
            VmessCrypto.RANDOM.nextBytes(padding);
            buffer.write(padding, 0, paddingLen);
        }

        byte[] body = buffer.toByteArray();
        int hash = fnv1a(body);
        byte[] out = Arrays.copyOf(body, body.length + 4);
        out[body.length] = (byte) (hash >>> 24);
        out[body.length + 1] = (byte) (hash >>> 16);
        out[body.length + 2] = (byte) (hash >>> 8);
        out[body.length + 3] = (byte) hash;
        return out;
    }

    private static int fnv1a(byte[] data) {
        int hash = 0x811C9DC5;
        for (byte b : data) {
            hash ^= b & 0xFF;
            hash *= 0x01000193;
        }
        return hash;
    }

    /**
     * Reads the sealed response header, once, just before the first byte of downstream data.
     *
     * It cannot be done at connect time: the server only answers after the request has been made,
     * and a SOCKS CONNECT that waited for it would deadlock against a peer waiting for the request.
     * The first byte echoes the one we sent, which confirms the server decrypted our request
     * correctly and is not a stale or unrelated connection.
     */
    private void ensureResponseHeader() throws IOException {
        synchronized (responseHeaderLock) {
            if (responseHeaderRead) return;
            readResponseHeader(expectedResponseHeader);
            responseHeaderRead = true;
        }
    }

    private void readResponseHeader(byte expected) throws IOException {
        byte[] sealedLength = readExactly(in, 18);
        byte[] lengthKey = new VmessKdf(VmessCrypto.KDF_RESP_HEADER_LEN_KEY).derive16(responseBodyKey);
        byte[] lengthIv = Arrays.copyOf(new VmessKdf(VmessCrypto.KDF_RESP_HEADER_LEN_IV).derive(responseBodyIv),
                VmessCrypto.NONCE_LENGTH);

        byte[] lengthPlain;
        try {
            lengthPlain = VmessCrypto.gcmOpen(lengthKey, lengthIv, sealedLength, null);
        } catch (RuntimeException e) {
            throw new IOException("server did not accept the request (bad key or node is not vmess)", e);
        }
        int length = ((lengthPlain[0] & 0xFF) << 8) | (lengthPlain[1] & 0xFF);
        if (length < 4 || length > 4096) {
            throw new IOException("unexpected response header length " + length);
        }

        byte[] sealedPayload = readExactly(in, length + VmessCrypto.GCM_TAG_BYTES);
        byte[] payloadKey = new VmessKdf(VmessCrypto.KDF_RESP_HEADER_KEY).derive16(responseBodyKey);
        byte[] payloadIv = Arrays.copyOf(new VmessKdf(VmessCrypto.KDF_RESP_HEADER_IV).derive(responseBodyIv),
                VmessCrypto.NONCE_LENGTH);
        byte[] payload;
        try {
            payload = VmessCrypto.gcmOpen(payloadKey, payloadIv, sealedPayload, null);
        } catch (RuntimeException e) {
            throw new IOException("cannot decrypt response header", e);
        }

        if (payload[0] != expected) {
            throw new IOException("response header mismatch: expected " + expected + ", got " + payload[0]);
        }
    }

    /** Streams application data as GCM chunks over the connection. */
    public synchronized OutputStream outputStream() {
        if (chunkOut == null) {
            chunkOut = new ChunkOutputStream(out, requestBodyKey, new VmessCrypto.ChunkNonce(requestBodyIv));
        }
        return chunkOut;
    }

    /** Decrypts the server's chunks back into application data. */
    public synchronized InputStream inputStream() {
        if (chunkIn == null) {
            chunkIn = new ChunkInputStream(length -> {
                ensureResponseHeader();
                return readExactly(in, length);
            }, responseBodyKey, new VmessCrypto.ChunkNonce(responseBodyIv));
        }
        return chunkIn;
    }

    /**
     * Signals end of stream to the server without tearing the connection down, so the reply
     * direction can still deliver what is in flight.
     */
    public void shutdownOutput() {
        try {
            OutputStream chunked = outputStream();
            if (chunked instanceof ChunkOutputStream) {
                ((ChunkOutputStream) chunked).finish();
            }
            socket.shutdownOutput();
        } catch (IOException ignored) {
        }
    }

    public void close() {
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(out, offset, length - offset);
            if (n < 0) {
                throw new EOFException("connection closed mid-frame");
            }
            offset += n;
        }
        return out;
    }

    private static final class ChunkOutputStream extends FilterOutputStream {
        private final byte[] key;
        private final VmessCrypto.ChunkNonce nonce;
        private final byte[] buffer = new byte[16384];
        private int count;

        ChunkOutputStream(OutputStream out, byte[] key, VmessCrypto.ChunkNonce nonce) {
            super(out);
            this.key = key;
            this.nonce = nonce;
        }

        @Override
        public void write(int b) throws IOException {
            if (count == buffer.length) flushChunk();
            buffer[count++] = (byte) b;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int room = buffer.length - count;
                if (room == 0) {
                    flushChunk();
                    room = buffer.length;
                }
                int n = Math.min(room, len);
                System.arraycopy(b, off, buffer, count, n);
                count += n;
                off += n;
                len -= n;
            }
        }

        @Override
        public void flush() throws IOException {
            flushChunk();
            out.flush();
        }

        private void flushChunk() throws IOException {
            if (count == 0) return;
            writeChunk(buffer, count);
            count = 0;
        }

        private void writeChunk(byte[] data, int length) throws IOException {
            byte[] sealed = VmessCrypto.gcmSeal(key, nonce.next(), Arrays.copyOf(data, length), null);
            int total = sealed.length;
            byte[] frame = new byte[2 + total];
            frame[0] = (byte) (total >>> 8);
            frame[1] = (byte) total;
            System.arraycopy(sealed, 0, frame, 2, total);
            out.write(frame);
        }

        /** Sends the end-of-stream marker, a chunk that is nothing but a tag for empty data. */
        void finish() throws IOException {
            flushChunk();
            writeChunk(new byte[0], 0);
            out.flush();
        }

        @Override
        public void close() throws IOException {
            finish();
        }
    }

    /** Fetches exactly {@code length} bytes of downstream data, after any handshake frames. */
    private interface FrameReader {
        byte[] read(int length) throws IOException;
    }

    private static final class ChunkInputStream extends InputStream {
        private final FrameReader frames;
        private final byte[] key;
        private final VmessCrypto.ChunkNonce nonce;
        private byte[] pending = new byte[0];
        private int position;
        private boolean eof;

        ChunkInputStream(FrameReader frames, byte[] key, VmessCrypto.ChunkNonce nonce) {
            this.frames = frames;
            this.key = key;
            this.nonce = nonce;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (position >= pending.length) {
                if (!fill()) return -1;
            }
            int n = Math.min(len, pending.length - position);
            System.arraycopy(pending, position, b, off, n);
            position += n;
            return n;
        }

        @Override
        public int available() {
            return pending.length - position;
        }

        private boolean fill() throws IOException {
            if (eof) return false;
            byte[] sizeBytes;
            try {
                sizeBytes = frames.read(2);
            } catch (EOFException e) {
                eof = true;
                return false;
            }
            int size = ((sizeBytes[0] & 0xFF) << 8) | (sizeBytes[1] & 0xFF);
            if (size == VmessCrypto.GCM_TAG_BYTES) {
                eof = true;
                return false;
            }
            if (size > MAX_CHUNK) {
                throw new IOException("chunk too large: " + size);
            }
            byte[] sealed = frames.read(size);
            pending = VmessCrypto.gcmOpen(key, nonce.next(), sealed, null);
            position = 0;
            if (pending.length == 0) return fill();
            return true;
        }
    }
}

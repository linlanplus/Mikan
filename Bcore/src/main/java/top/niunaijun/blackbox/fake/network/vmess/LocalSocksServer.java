package top.niunaijun.blackbox.fake.network.vmess;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.utils.Slog;

/**
 * A SOCKS5 server bound to loopback inside the guest process.
 *
 * The native {@code connect()} hook in this process is pointed at it, so every outbound TCP
 * connection the cloned app makes arrives here as a SOCKS5 CONNECT. Each one is turned into a
 * VMess session to the configured node, which gives the clone the node's exit address without
 * the hook needing to know anything about VMess.
 *
 * Only CONNECT is supported; UDP ASSOCIATE is refused, so UDP goes straight out and does not
 * follow the node.
 */
public final class LocalSocksServer {
    private static final String TAG = "LocalSocks";

    private static final int SOCKS_VERSION = 0x05;
    private static final int METHOD_NO_AUTH = 0x00;
    private static final int METHOD_UNACCEPTABLE = 0xFF;
    private static final int CMD_CONNECT = 0x01;
    private static final int ATYP_IPV4 = 0x01;
    private static final int ATYP_DOMAIN = 0x03;
    private static final int ATYP_IPV6 = 0x04;

    private static final int REPLY_SUCCEEDED = 0x00;
    private static final int REPLY_GENERAL_FAILURE = 0x01;
    private static final int REPLY_COMMAND_NOT_SUPPORTED = 0x07;
    private static final int REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

    private static LocalSocksServer instance;

    private final VmessNode node;
    private final ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread acceptThread;

    private LocalSocksServer(VmessNode node, ServerSocket serverSocket) {
        this.node = node;
        this.serverSocket = serverSocket;
        this.acceptThread = new Thread(this::acceptLoop, "mikan-vmess-accept");
        this.acceptThread.setDaemon(true);
    }

    /**
     * Starts the listener on an ephemeral loopback port, replacing any server this process already
     * had. Returns its port, or -1 if it could not start.
     */
    public static synchronized int start(VmessNode node) {
        stop();
        try {
            ServerSocket serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 128);
            LocalSocksServer server = new LocalSocksServer(node, serverSocket);
            instance = server;
            server.acceptThread.start();
            Slog.d(TAG, "listening on 127.0.0.1:" + serverSocket.getLocalPort() + " for " + node);
            return serverSocket.getLocalPort();
        } catch (IOException e) {
            Slog.w(TAG, "cannot start local proxy: " + e.getMessage());
            return -1;
        }
    }

    public static synchronized void stop() {
        LocalSocksServer current = instance;
        instance = null;
        if (current != null) current.shutdown();
    }

    private void shutdown() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                Thread worker = new Thread(() -> handle(client), "mikan-vmess-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException e) {
                if (running.get()) {
                    Slog.w(TAG, "accept failed: " + e.getMessage());
                }
                return;
            }
        }
    }

    private void handle(Socket client) {
        VmessSession session = null;
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            if (!readGreeting(in, out)) {
                client.close();
                return;
            }

            Target target = readRequest(in);
            if (target == null) {
                writeReply(out, REPLY_ADDRESS_TYPE_NOT_SUPPORTED);
                client.close();
                return;
            }

            try {
                session = VmessSession.open(node, target.address, target.port);
            } catch (Exception e) {
                Slog.w(TAG, "cannot reach " + target + " through " + node.host + ": " + e.getMessage());
                writeReply(out, REPLY_GENERAL_FAILURE);
                client.close();
                return;
            }

            writeReply(out, REPLY_SUCCEEDED);
            relay(client, session);
        } catch (IOException e) {
            Slog.d(TAG, "connection ended: " + e.getMessage());
            if (session != null) session.close();
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean readGreeting(InputStream in, OutputStream out) throws IOException {
        byte[] head = readExactly(in, 2);
        if (head[0] != SOCKS_VERSION) return false;
        int methodCount = head[1] & 0xFF;
        byte[] methods = readExactly(in, methodCount);
        for (byte method : methods) {
            if (method == METHOD_NO_AUTH) {
                out.write(new byte[]{SOCKS_VERSION, METHOD_NO_AUTH});
                out.flush();
                return true;
            }
        }
        out.write(new byte[]{SOCKS_VERSION, (byte) METHOD_UNACCEPTABLE});
        out.flush();
        return false;
    }

    private Target readRequest(InputStream in) throws IOException {
        byte[] head = readExactly(in, 4);
        if (head[0] != SOCKS_VERSION) return null;
        int command = head[1] & 0xFF;
        int addressType = head[3] & 0xFF;
        if (command != CMD_CONNECT) return null;

        InetAddress address;
        if (addressType == ATYP_IPV4) {
            byte[] raw = readExactly(in, 4);
            address = InetAddress.getByAddress(raw);
        } else if (addressType == ATYP_IPV6) {
            byte[] raw = readExactly(in, 16);
            address = InetAddress.getByAddress(raw);
        } else if (addressType == ATYP_DOMAIN) {
            int length = readExactly(in, 1)[0] & 0xFF;
            String host = new String(readExactly(in, length), "UTF-8");
            try {
                address = InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                return null;
            }
        } else {
            return null;
        }

        byte[] portBytes = readExactly(in, 2);
        int port = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);
        return new Target(address, port);
    }

    /** Replies with an IPv4 zero address, which clients accept as "bound address unknown". */
    private void writeReply(OutputStream out, int status) throws IOException {
        out.write(new byte[]{SOCKS_VERSION, (byte) status, 0x00, ATYP_IPV4,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00});
        out.flush();
    }

    private void relay(Socket client, VmessSession session) {
        AtomicBoolean closed = new AtomicBoolean(false);
        Thread upstream = new Thread(() -> {
            try {
                copy(client.getInputStream(), session.outputStream());
            } catch (IOException e) {
                Slog.d(TAG, "client to node: " + e.getMessage());
            } finally {
                session.close();
                closeQuietly(client);
            }
        }, "mikan-vmess-up");
        Thread downstream = new Thread(() -> {
            try {
                copy(session.inputStream(), client.getOutputStream());
            } catch (IOException e) {
                Slog.d(TAG, "node to client: " + e.getMessage());
            } finally {
                closeQuietly(client);
                if (closed.compareAndSet(false, true)) session.close();
            }
        }, "mikan-vmess-down");
        upstream.setDaemon(true);
        downstream.setDaemon(true);
        upstream.start();
        downstream.start();
        try {
            upstream.join();
            downstream.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Copies in one direction, flushing after every read.
     *
     * The chunked writer buffers up to 16 KiB before it emits a frame, so a short request left
     * unflushed would never reach the peer, and the response would never come.
     */
    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n == 0) continue;
            out.write(buffer, 0, n);
            out.flush();
        }
        out.flush();
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(out, offset, length - offset);
            if (n < 0) throw new IOException("connection closed during SOCKS5 handshake");
            offset += n;
        }
        return out;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static final class Target {
        final InetAddress address;
        final int port;

        Target(InetAddress address, int port) {
            this.address = address;
            this.port = port;
        }

        @Override
        public String toString() {
            return address.getHostAddress() + ":" + port;
        }
    }
}

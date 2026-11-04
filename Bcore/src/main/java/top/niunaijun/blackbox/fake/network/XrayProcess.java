package top.niunaijun.blackbox.fake.network;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class XrayProcess {
    static final int MAX_INPUT = 4 * 1024 * 1024;
    private static final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "xray-start-timeout"); t.setDaemon(true); return t; });
    private static Process active;
    private XrayProcess() {}

    private static Process launch(Context context, String mode) throws IOException {
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libmikanxray.so");
        Process child = new ProcessBuilder(binary.getAbsolutePath(), mode).start();
        Thread drain = new Thread(() -> {
            try (InputStream errors = child.getErrorStream()) {
                byte[] buffer = new byte[4096];
                while (errors.read(buffer) != -1) { /* Do not log credentials or subscription URLs. */ }
            } catch (IOException ignored) {}
        }, "xray-stderr");
        drain.setDaemon(true);
        drain.start();
        return child;
    }

    static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (count > limit - out.size()) throw new IOException("Response too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    static JSONObject parse(Context context, byte[] content) throws Exception {
        if (content.length > MAX_INPUT) throw new IOException("Subscription too large");
        Process child = launch(context, "import");
        ScheduledFuture<?> deadline = timer.schedule(child::destroy, 30, TimeUnit.SECONDS);
        try {
            try (OutputStream input = child.getOutputStream()) { input.write(content); }
            byte[] result;
            try (InputStream output = child.getInputStream()) { result = readLimited(output, 12 * 1024 * 1024); }
            if (child.waitFor() != 0) throw new IOException("No supported nodes");
            return new JSONObject(new String(result, StandardCharsets.UTF_8));
        } finally {
            deadline.cancel(false);
            child.destroy();
        }
    }

    static int measureLatency(Context context, String outbound) throws Exception {
        Process child = launch(context, "latency");
        ScheduledFuture<?> deadline = timer.schedule(child::destroy, 10, TimeUnit.SECONDS);
        try {
            try (OutputStream input = child.getOutputStream()) { input.write(outbound.getBytes(StandardCharsets.UTF_8)); }
            if (child.waitFor() != 0) return -1;
            try (InputStream output = child.getInputStream()) {
                return Integer.parseInt(new String(readLimited(output, 32), StandardCharsets.US_ASCII).trim());
            }
        } finally {
            deadline.cancel(false);
            child.destroy();
        }
    }

    static synchronized int start(Context context, JSONObject outbound, String password) throws Exception {
        stop();
        Process child = launch(context, "run");
        ScheduledFuture<?> deadline = timer.schedule(child::destroy, 12, TimeUnit.SECONDS);
        try {
            byte[] request = new JSONObject().put("outbound", outbound).put("password", password)
                    .toString().getBytes(StandardCharsets.UTF_8);
            OutputStream input = child.getOutputStream();
            input.write(request);
            input.write('\n');
            input.flush();
            BufferedReader output = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8));
            String ready = output.readLine();
            if (ready == null || !ready.startsWith("READY ")) throw new IOException("Xray could not start");
            int port = Integer.parseInt(ready.substring(6));
            if (port < 1 || port > 65535) throw new IOException("Invalid local port");
            active = child;
            return port;
        } finally {
            deadline.cancel(false);
            if (child != active) child.destroy();
        }
    }

    static synchronized void stop() {
        if (active != null) {
            try { active.getOutputStream().close(); } catch (IOException ignored) {}
            active.destroy();
            active = null;
        }
    }
}

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
    private XrayProcess() {}

    static Process launch(Context context, String mode) throws IOException {
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "libmikanxray.so");
        ProcessBuilder launcher = new ProcessBuilder(binary.getAbsolutePath(), mode);
        if ("gateway".equals(mode) && android.os.Build.SUPPORTED_ABIS[0].startsWith("x86")) {
            // ARM native bridges can crash on asynchronous Go signal preemption.
            launcher.environment().put("GODEBUG", "asyncpreemptoff=1");
            launcher.environment().put("GOMAXPROCS", "2");
        }
        Process child = launcher.start();
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
        if (CloneVpnService.network(context)!=null) {
            JSONObject ob=new JSONObject(outbound);
            String server=ob.getJSONObject("settings").getString("address");
            String pin=ProxyConfig.numeric(server)?server:CloneVpnService.physicalAddress(context,server);
            try (java.net.Socket socket=new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("10.112.0.2",5355),2000);
                socket.setSoTimeout(9000);
                String request=new JSONObject().put("Outbound",ob).put("Pin",pin).toString()+"\n";
                socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
                String result=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
                return result==null?-2:Integer.parseInt(result);
            } catch (java.net.SocketTimeoutException e) {return -1;}
        }
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

}

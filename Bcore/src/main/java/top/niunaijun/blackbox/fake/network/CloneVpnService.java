package top.niunaijun.blackbox.fake.network;

import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Network;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.NativeCore;

/** Host-side packet gateway. The separate GuardService owns the VPN and all egress sockets. */
public final class CloneVpnService extends android.app.Service {
    private static volatile uk.lqstudio.guard.IGuard guard;
    private final android.os.Binder lifetime = new android.os.Binder();
    private volatile boolean closing;
    private android.os.IBinder.DeathRecipient guardDeath;
    private final String token = UUID.randomUUID().toString();
    private ExecutorService control;
    private LocalServerSocket listener;
    private ParcelFileDescriptor tun;
    private Process child;
    private android.content.ContentProviderClient manager;

    public static Network network(Context ctx) {
        uk.lqstudio.guard.IGuard session = guard;
        try {
            return session == null ? null : session.network();
        } catch (android.os.RemoteException e) {
            return null;
        }
    }
    static String physicalAddress(Context ctx, String name) throws IOException {
        uk.lqstudio.guard.IGuard session = guard;
        try {
            String[] ips = session == null ? null : session.resolve(name);
            if (ips != null && ips.length > 0) {
                for (String ip : ips)
                    if (ip.indexOf(':') < 0)
                        return ip;
                return ips[0];
            }
        } catch (android.os.RemoteException ignored) {
        }
        throw new IOException("No physical address for endpoint");
    }
    static String controlName() {
        return BlackBoxCore.getHostPkg() + ".route." + BlackBoxCore.getHostUid();
    }
    private void checkGuard() {
        try {
            String pkg = getPackageName() + ".networkguard";
            if (getPackageManager().getApplicationInfo(pkg, 0).uid == android.os.Binder.getCallingUid()
                && getPackageManager().checkSignatures(getPackageName(), pkg)
                    == android.content.pm.PackageManager.SIGNATURE_MATCH)
                return;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        throw new SecurityException("Unexpected network controller");
    }
    private final uk.lqstudio.guard.IGuardHost.Stub endpoint = new uk.lqstudio.guard.IGuardHost.Stub() {
        public void attach(uk.lqstudio.guard.IGuard session) {
            checkGuard();
            new android.os.Handler(getMainLooper()).post(() -> begin(session));
        }
        public void detach() {
            checkGuard();
            shutdown();
        }
    };
    @Override
    public android.os.IBinder onBind(Intent intent) {
        return endpoint;
    }
    private synchronized void begin(uk.lqstudio.guard.IGuard session) {
        if (tun != null)
            return;
        closing = false;
        control = Executors.newFixedThreadPool(8);
        try {
            manager = getContentResolver().acquireUnstableContentProviderClient(
                top.niunaijun.blackbox.proxy.ProxyManifest.getBindProvider());
            if (manager == null)
                throw new IOException("Process manager unavailable");
            guardDeath = () -> {synchronized (CloneVpnService.this){if (guard == session) shutdown();
        }
    };
    session.asBinder().linkToDeath(guardDeath, 0);
    tun = session.tunnel();
    if (tun == null)
        throw new IOException("No tunnel");
    guard = session;
    String address = controlName();
    listener = new LocalServerSocket(address);
    LocalServerSocket server = listener;
    new Thread(() -> accept(server), "clone-vpn-control").start();
    child = XrayProcess.launch(this, "gateway");
    OutputStream in = child.getOutputStream();
    in.write((new JSONObject().put("Socket", address).put("Token", token) + "\n")
            .getBytes(StandardCharsets.UTF_8));
    in.flush();
    Process running = child;
    new Thread(() -> {
        try (BufferedReader output = new BufferedReader(new InputStreamReader(running.getInputStream()))) {
            if ("READY".equals(output.readLine())) {
                for (int i = 0; i < 100 && guard == session; i++) {
                    Network n = session.network();
                    if (n != null) {
                        BlackBoxCore.getBActivityManager().getService().publishCloneNetwork(n, lifetime);
                        break;
                    }
                    android.os.SystemClock.sleep(100);
                }
                Log.i("CloneVPN", "Packet gateway ready");
            }
            running.waitFor();
        } catch (Exception ignored) {
        }
        // Keep the TUN until teardown. A failed gateway must not release selected guests to Wi-Fi.
        Log.w("CloneVPN", "Packet gateway stopped");
        synchronized (CloneVpnService.this) {
            if (child == running)
                shutdown();
        }
    }, "clone-vpn-watch").start();
}
catch (Exception e) {
    Log.w("CloneVPN", "Cannot start packet gateway: " + e.getClass().getSimpleName());
    shutdown();
}
}
private void accept(LocalServerSocket server) {
    try {
        while (true) {
            LocalSocket socket = server.accept();
            control.execute(() -> serve(socket));
        }
    } catch (Exception ignored) {
    }
}
private void serve(LocalSocket client) {
    try (LocalSocket c = client) {
        if (c.getPeerCredentials().getUid() != android.os.Process.myUid())
            return;
        c.setSoTimeout(8000);
        InputStream in = new BufferedInputStream(c.getInputStream());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int octet;
        while ((octet = in.read()) >= 0 && octet != '\n' && bytes.size() < 8192) bytes.write(octet);
        if (octet != '\n')
            return;
        String line = bytes.toString("UTF-8");
        JSONObject req = new JSONObject(line);
        if (!token.equals(req.optString("Token")))
            return;
        JSONObject response = new JSONObject();
        switch (req.getString("Op")) {
            case "tun":
                c.setFileDescriptorsForSend(new FileDescriptor[] {tun.getFileDescriptor()});
                c.getOutputStream().write(1);
                return;
            case "tcp":
            case "udp":
                uk.lqstudio.guard.IGuard g = guard;
                if (g == null)
                    return;
                String endpoint = req.getString("Host");
                int sep = endpoint.lastIndexOf('/');
                if (sep < 1)
                    return;
                try (ParcelFileDescriptor fd = g.dial(endpoint.substring(0, sep),
                         Integer.parseInt(endpoint.substring(sep + 1)), "udp".equals(req.getString("Op")))) {
                    if (fd == null)
                        return;
                    c.setFileDescriptorsForSend(new FileDescriptor[] {fd.getFileDescriptor()});
                    c.getOutputStream().write(1);
                }
                return;
            case "answers":
                if (guard != null) {
                    String[] answers = guard.resolve(req.getString("Host"));
                    response.put("Answers", new org.json.JSONArray(answers == null ? new String[0] : answers))
                        .put("Mode", "ok");
                }
                break;
            case "resolve":
                response.put("Pin", physicalAddress(this, req.getString("Host"))).put("Mode", "ok");
                break;
            case "route":
            case "owner":
                response = route(req.getJSONObject("Flow"), "owner".equals(req.getString("Op")));
                break;
            default:
                return;
        }
        c.getOutputStream().write((response + "\n").getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {
        // Closing the control connection denies this operation.
    }
}
private JSONObject route(JSONObject f, boolean ownerOnly) throws Exception {
    if (closing)
        return new JSONObject();
    int type = "tcp".equals(f.getString("Proto")) ? OsConstants.SOCK_STREAM : OsConstants.SOCK_DGRAM;
    String src = f.getString("Src"), dst = f.getString("Dst");
    int sp = f.getInt("SP"), dp = f.getInt("DP");
    String guest = BlackBoxCore.getBActivityManager().getService().socketOwner(type, src, sp, dst, dp);
    long own = NativeCore.socketOwner(type, src, sp, dst, dp);
    JSONObject p = new JSONObject();
    if (guest == null || guest.equals("blocked") || own < 0 || (own > 0 && !guest.isEmpty()))
        return p;
    if (own > 0)
        return p.put("Owner", "host:" + own).put("Mode", "direct").put("UDP", true);
    if (guest.isEmpty()) {
        if (dp == 53 && ("10.112.0.2".equals(dst) || "fd75:6d69:6b61::2".equals(dst)))
            p.put("Mode", "system-dns");
        return p;
    }
    String[] parts = guest.split(":", 4);
    if (parts.length != 4)
        return p;
    p.put("Owner", guest + ":" + ProxyConfig.revision());
    if (ownerOnly)
        return p.put("Mode", "owner");
    return ProxyConfig.policy(Integer.parseInt(parts[0]), parts[1], p);
}

private synchronized void shutdown() {
    if (closing)
        return;
    closing = true;
    uk.lqstudio.guard.IGuard previous = guard;
    guard = null;
    if (previous != null && guardDeath != null) {
        try {
            previous.asBinder().unlinkToDeath(guardDeath, 0);
        } catch (java.util.NoSuchElementException ignored) {
        }
    }
    if (listener != null) {
        // Closing a listening descriptor from another thread does not interrupt Linux accept().
        try {
            android.system.Os.shutdown(listener.getFileDescriptor(), OsConstants.SHUT_RDWR);
        } catch (Exception ignored) {
        }
        try {
            listener.close();
        } catch (IOException ignored) {
        }
        listener = null;
    }
    if (control != null)
        control.shutdownNow();
    if (manager != null) {
        try {
            BlackBoxCore.getBActivityManager().getService().publishCloneNetwork(null, lifetime);
            BlackBoxCore.getBActivityManager().getService().releaseNetworkProviders(true);
        } catch (Exception ignored) {
        }
        manager.release();
    }
    if (child != null) {
        try {
            child.getOutputStream().close();
        } catch (IOException ignored) {
        }
        child.destroy();
    }
    try {
        if (tun != null)
            tun.close();
    } catch (IOException ignored) {
    }
    tun = null;
    manager = null;
    child = null;
}
@Override
public void onDestroy() {
    shutdown();
    super.onDestroy();
}
}

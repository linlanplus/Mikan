package uk.lqstudio.guard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import java.io.IOException;
import java.net.InetAddress;

/** This UID controls the VPN. Guest processes run under the separate Mikan UID. */
public final class GuardService extends VpnService {
    private ParcelFileDescriptor tun;
    private IGuardHost host;
    private boolean bound;
    private String hostPackage() {
        return getPackageName().substring(0, getPackageName().length() - ".networkguard".length());
    }
    // The capability is delivered only to the explicit, signature-verified
    // main-process service. The exported consent Activity never returns it.
    private final IGuard.Stub session = new IGuard.Stub() {
        @Override
        public ParcelFileDescriptor tunnel() {
            try {
                return tun == null ? null : ParcelFileDescriptor.dup(tun.getFileDescriptor());
            } catch (IOException e) {
                return null;
            }
        }
        @Override
        public ParcelFileDescriptor dial(String address, int port, boolean udp) {
            if (tun == null || port < 1 || port > 65535)
                return null;
            int family = address.indexOf(':') < 0 ? android.system.OsConstants.AF_INET
                                                  : android.system.OsConstants.AF_INET6;
            InetAddress ip = android.system.Os.inet_pton(family, address);
            if (ip == null)
                return null;
            java.io.FileDescriptor socket = null;
            try {
                socket = android.system.Os.socket(family,
                    udp ? android.system.OsConstants.SOCK_DGRAM : android.system.OsConstants.SOCK_STREAM, 0);
                try (ParcelFileDescriptor handle = ParcelFileDescriptor.dup(socket)) {
                    if (!protect(handle.getFd()))
                        return null;
                }
                android.system.Os.setsockoptTimeval(socket, android.system.OsConstants.SOL_SOCKET,
                    android.system.OsConstants.SO_SNDTIMEO, android.system.StructTimeval.fromMillis(6000));
                android.system.Os.connect(socket, ip, port);
                return ParcelFileDescriptor.dup(socket);
            } catch (Exception e) {
                return null;
            } finally {
                if (socket != null)
                    try {
                        android.system.Os.close(socket);
                    } catch (Exception ignored) {
                    }
            }
        }
        @Override
        public Network network() {
            long saved = Binder.clearCallingIdentity();
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                for (Network n : cm.getAllNetworks()) {
                    NetworkCapabilities cap = cm.getNetworkCapabilities(n);
                    LinkProperties link = cm.getLinkProperties(n);
                    if (cap == null || link == null || !cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                        continue;
                    if (Build.VERSION.SDK_INT >= 31 && cap.getOwnerUid() != android.os.Process.myUid())
                        continue;
                    for (LinkAddress a : link.getLinkAddresses())
                        if ("10.112.0.1".equals(a.getAddress().getHostAddress()))
                            return n;
                }
                return null;
            } finally {
                Binder.restoreCallingIdentity(saved);
            }
        }
        @Override
        public String[] resolve(String name) {
            long saved = Binder.clearCallingIdentity();
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                Network active = Build.VERSION.SDK_INT >= 23 ? cm.getActiveNetwork() : null;
                Network[] networks = cm.getAllNetworks();
                for (int i = -1; i < networks.length; i++) {
                    Network n = i < 0 ? active : networks[i];
                    NetworkCapabilities cap = n == null ? null : cm.getNetworkCapabilities(n);
                    if (cap == null || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                        continue;
                    try {
                        InetAddress[] ips = n.getAllByName(name);
                        if (ips.length > 0) {
                            String[] values = new String[ips.length];
                            for (int k = 0; k < ips.length; k++) values[k] = ips[k].getHostAddress();
                            return values;
                        }
                    } catch (IOException ignored) {
                    }
                }
                return null;
            } finally {
                Binder.restoreCallingIdentity(saved);
            }
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            host = IGuardHost.Stub.asInterface(service);
            try {
                host.attach(session);
            } catch (RemoteException ignored) {
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Keep routing into the TUN while Android reconnects the host.
            host = null;
        }
    };
    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        Notification.Builder notice;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class)
                .createNotificationChannel(
                    new NotificationChannel("clones", "分身代理", NotificationManager.IMPORTANCE_LOW));
            notice = new Notification.Builder(this, "clones");
        } else
            notice = new Notification.Builder(this);
        startForeground(3319,
            notice.setContentTitle("小橘分身代理")
                .setContentText("分身按所选节点联网")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build());
        if (tun != null) {
            if (host != null)
                try {
                    host.attach(session);
                } catch (RemoteException ignored) {
                }
            return START_NOT_STICKY;
        }
        try {
            String pkg = hostPackage();
            if (getPackageManager().checkSignatures(getPackageName(), pkg) != PackageManager.SIGNATURE_MATCH)
                throw new SecurityException();
            Builder b = new Builder()
                            .setSession("小橘分身代理")
                            .setBlocking(true)
                            .setMtu(1500)
                            .addAllowedApplication(pkg)
                            .addAddress("10.112.0.1", 32)
                            .addAddress("fd75:6d69:6b61::1", 128)
                            .addDnsServer("10.112.0.2")
                            .addDnsServer("fd75:6d69:6b61::2")
                            .addRoute("0.0.0.0", 0)
                            .addRoute("::", 0);
            if (Build.VERSION.SDK_INT >= 29)
                b.setMetered(false);
            tun = b.establish();
            if (tun == null)
                throw new IOException();
            Intent service = new Intent().setComponent(
                new ComponentName(pkg, "top.niunaijun.blackbox.fake.network.CloneVpnService"));
            bound = bindService(service, connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            if (!bound)
                throw new IOException();
        } catch (Exception e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    @Override
    public void onRevoke() {
        stopSelf();
    }
    @Override
    public void onDestroy() {
        if (host != null)
            try {
                host.detach();
            } catch (RemoteException ignored) {
            }
        if (bound)
            unbindService(connection);
        try {
            if (tun != null)
                tun.close();
        } catch (IOException ignored) {
        }
        tun = null;
        super.onDestroy();
    }
}

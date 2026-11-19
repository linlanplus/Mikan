package top.niunaijun.blackbox.fake.service;

import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;

import black.android.net.wifi.BRWifiSsid;
import top.niunaijun.blackbox.fake.device.DeviceIdentifiers;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Gives each clone its own name and BSSID for the Wi-Fi networks the phone sees. Values the
 * system already hides (no location permission, hidden network) are left as they are.
 */
public final class WifiIdentityRewriter {
    private static final String TAG = "WifiIdentity";
    private static HandlerThread sCallbackThread;

    private WifiIdentityRewriter() {
    }

    public static void rewrite(WifiInfo info) {
        if (info == null) return;
        try {
            String ssid = info.getSSID();
            if (ssid != null && ssid.length() > 1 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            String fakeSsid = DeviceIdentifiers.wifiSsid(ssid);
            if (fakeSsid != null && !fakeSsid.equals(ssid)) {
                Object wifiSsid = createWifiSsid(fakeSsid);
                if (wifiSsid != null) setField(WifiInfo.class, info, "mWifiSsid", wifiSsid);
            }
            String bssid = info.getBSSID();
            String fakeBssid = DeviceIdentifiers.wifiBssid(bssid);
            if (fakeBssid != null && !fakeBssid.equals(bssid)) {
                setField(WifiInfo.class, info, "mBSSID", fakeBssid);
            }
        } catch (Throwable e) {
            Slog.w(TAG, "WifiInfo rewrite failed: " + e);
        }
    }

    public static void rewrite(ScanResult result) {
        if (result == null) return;
        try {
            String fakeSsid = DeviceIdentifiers.wifiSsid(result.SSID);
            if (fakeSsid != null && !fakeSsid.equals(result.SSID)) {
                result.SSID = fakeSsid;
                Object wifiSsid = createWifiSsid(fakeSsid);
                if (wifiSsid != null) {
                    // Hidden field before Android 13, private after.
                    if (!setField(ScanResult.class, result, "wifiSsid", wifiSsid)) {
                        setField(ScanResult.class, result, "mWifiSsid", wifiSsid);
                    }
                }
            }
            result.BSSID = DeviceIdentifiers.wifiBssid(result.BSSID);
        } catch (Throwable e) {
            Slog.w(TAG, "ScanResult rewrite failed: " + e);
        }
    }

    /** Scan lists arrive as a List or, from Android 13, a ParceledListSlice. */
    public static void rewriteScanResults(Object results) {
        if (results == null) return;
        Object list = results;
        if (!(results instanceof List)) {
            try {
                list = results.getClass().getMethod("getList").invoke(results);
            } catch (Throwable e) {
                return;
            }
        }
        if (list instanceof List) {
            for (Object item : (List<?>) list) {
                if (item instanceof ScanResult) rewrite((ScanResult) item);
            }
        }
    }

    public static void rewrite(NetworkCapabilities capabilities) {
        if (capabilities != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && capabilities.getTransportInfo() instanceof WifiInfo) {
            rewrite((WifiInfo) capabilities.getTransportInfo());
        }
    }

    /**
     * Network callbacks carry the same WifiInfo inside NetworkCapabilities. Pass the system a
     * messenger that rewrites them on the way to the app's own handler.
     */
    public static void wrapCallbackMessenger(Object[] args) {
        if (args == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Messenger) {
                args[i] = wrap((Messenger) args[i]);
                return;
            }
        }
    }

    private static synchronized Messenger wrap(Messenger target) {
        if (sCallbackThread == null) {
            sCallbackThread = new HandlerThread("mikan-net-callback");
            sCallbackThread.start();
        }
        // One thread keeps callbacks in the order the system sent them.
        Handler handler = new Handler(sCallbackThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                Message copy = Message.obtain(msg);
                try {
                    Bundle data = copy.peekData();
                    if (data != null) {
                        data.setClassLoader(NetworkCapabilities.class.getClassLoader());
                        Object capabilities = data.getParcelable("NetworkCapabilities");
                        if (capabilities instanceof NetworkCapabilities) {
                            rewrite((NetworkCapabilities) capabilities);
                        }
                    }
                } catch (Throwable e) {
                    Slog.w(TAG, "Callback rewrite failed: " + e);
                }
                try {
                    target.send(copy);
                } catch (RemoteException ignored) {
                }
            }
        };
        return new Messenger(handler);
    }

    private static Object createWifiSsid(String ssid) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return Class.forName("android.net.wifi.WifiSsid").getMethod("fromBytes", byte[].class)
                        .invoke(null, (Object) ssid.getBytes(StandardCharsets.UTF_8));
            }
            return BRWifiSsid.get().createFromAsciiEncoded(ssid);
        } catch (Throwable e) {
            Slog.w(TAG, "Cannot create WifiSsid: " + e);
            return null;
        }
    }

    private static boolean setField(Class<?> type, Object target, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}

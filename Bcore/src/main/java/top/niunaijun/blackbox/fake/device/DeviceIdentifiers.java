package top.niunaijun.blackbox.fake.device;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

/**
 * Hardware and SIM identifiers of the current clone. Values are derived from the clone's serial
 * and Android ID, so "random device" changes all of them together. Access follows the platform
 * rules for an ordinary app, so a clone never sees an identifier a real phone would refuse.
 */
public final class DeviceIdentifiers {
    private static final String UNKNOWN = "unknown";

    private DeviceIdentifiers() {
    }

    /**
     * Platform check for IMEI, MEID, IMSI, ICCID and the serial number
     * (TelephonyPermissions.checkReadDeviceIdentifiers for an app without privileged access).
     *
     * @return true to return the clone's value, false to return null ("unknown" for the serial)
     * @throws SecurityException where a real phone throws
     */
    private static boolean mayRead(String api) {
        boolean granted = BlackBoxCore.getContext().checkPermission(Manifest.permission.READ_PHONE_STATE,
                Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (granted && guestTargetSdk() < Build.VERSION_CODES.Q) {
                return false;
            }
            throw new SecurityException(api + ": The uid " + Process.myUid()
                    + " does not meet the requirements to access device identifiers.");
        }
        if (!granted) {
            throw new SecurityException(api + ": Neither user " + Process.myUid()
                    + " nor current process has " + Manifest.permission.READ_PHONE_STATE + ".");
        }
        return true;
    }

    public static String imei(String api, int slot) {
        return mayRead(api) ? imei(slot) : null;
    }

    public static String meid(String api, int slot) {
        return mayRead(api) ? meid(slot) : null;
    }

    public static String subscriberId(String api, int subId) {
        if (!mayRead(api)) return null;
        String operator = simOperator(subId);
        if (operator == null) return null;
        return operator + digits("imsi" + subId, 15 - operator.length());
    }

    public static String iccid(String api, int subId) {
        if (!mayRead(api)) return null;
        String operator = simOperator(subId);
        if (operator == null) return null;
        String body = operator.startsWith("460")
                ? "8986" + chinaIssuer(operator.substring(3)) + digits("iccid" + subId, 13)
                : "89" + digits("iccid" + subId, 17);
        return body + luhn(body);
    }

    public static String serial(String api) {
        return mayRead(api) ? profile().serial : UNKNOWN;
    }

    /** Type allocation code, the model part of the IMEI. Readable without permission. */
    public static String typeAllocationCode(int slot) {
        return imei(slot).substring(0, 8);
    }

    /** Manufacturer code, the first part of the MEID. Readable without permission. */
    public static String manufacturerCode(int slot) {
        return meid(slot).substring(0, 8);
    }

    /** Whether Build.SERIAL carries the serial for this app (apps targeting O and later read "unknown"). */
    public static boolean exposesBuildSerial() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && guestTargetSdk() < Build.VERSION_CODES.O);
    }

    /** Slot of a subscription, for per-SIM identifiers. */
    public static int slotOf(int subId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int slot = SubscriptionManager.getSlotIndex(subId);
            if (slot >= 0) return slot;
        }
        return 0;
    }

    /**
     * The clone's name for a Wi-Fi network. The same real network keeps the same name in one
     * clone, so the connected network still matches its scan result. A hidden or unreadable SSID
     * is returned as is.
     */
    public static String wifiSsid(String realSsid) {
        if (TextUtils.isEmpty(realSsid) || "<unknown ssid>".equals(realSsid)) {
            return realSsid;
        }
        byte[] h = hash("ssid|" + realSsid);
        String suffix = String.format(Locale.US, "%02X%02X", h[1] & 0xff, h[2] & 0xff);
        String[] patterns = {"TP-LINK_%s", "ChinaNet-%s", "CMCC-%s", "MERCURY_%s", "FAST_%s",
                "HUAWEI-%s", "Xiaomi_%s", "CU_%s", "TENDA_%s", "H3C_%s"};
        String name = String.format(Locale.US, patterns[(h[0] & 0xff) % patterns.length], suffix);
        String upper = realSsid.toUpperCase(Locale.US);
        if (upper.endsWith("5G")) {
            name += "_5G";
        }
        return name;
    }

    /** The clone's BSSID for a real access point, or the value itself when it is hidden. */
    public static String wifiBssid(String realBssid) {
        if (TextUtils.isEmpty(realBssid) || "02:00:00:00:00:00".equals(realBssid)
                || "00:00:00:00:00:00".equals(realBssid)) {
            return realBssid;
        }
        byte[] h = hash("bssid|" + realBssid.toLowerCase(Locale.US));
        // Unicast, globally administered, like a router's factory address.
        h[0] &= (byte) 0xfc;
        return String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x",
                h[0] & 0xff, h[1] & 0xff, h[2] & 0xff, h[3] & 0xff, h[4] & 0xff, h[5] & 0xff);
    }

    /** Hex digits for the clone's advertising IDs, stable until "random device". */
    public static String hex(String label, int length) {
        byte[] h = hash(label);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; sb.length() < length; i++) {
            if (i == h.length) {
                h = hash(label + "|" + sb.length());
                i = 0;
            }
            sb.append(String.format(Locale.US, "%02x", h[i] & 0xff));
        }
        return sb.substring(0, length);
    }

    private static String imei(int slot) {
        String body = "86" + digits("imei" + slot, 12);
        return body + luhn(body);
    }

    private static String meid(int slot) {
        return "A0" + hex("meid" + slot, 12).toUpperCase(Locale.US);
    }

    private static String simOperator(int subId) {
        TelephonyManager tm = (TelephonyManager) BlackBoxCore.getContext().getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && subId >= 0 && subId != Integer.MAX_VALUE) {
            tm = tm.createForSubscriptionId(subId);
        }
        String operator = tm.getSimOperator();
        // Without a SIM a real phone has no IMSI or ICCID either.
        return operator != null && operator.matches("\\d{5,6}") ? operator : null;
    }

    private static String chinaIssuer(String mnc) {
        switch (mnc) {
            case "01": case "06": case "09": case "10":
                return "01";
            case "03": case "05": case "11": case "12":
                return "03";
            default:
                return "00";
        }
    }

    private static String luhn(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int d = body.charAt(body.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
        }
        return String.valueOf((10 - sum % 10) % 10);
    }

    private static String digits(String label, int length) {
        byte[] h = hash(label);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; sb.length() < length; i++) {
            if (i == h.length) {
                h = hash(label + "|" + sb.length());
                i = 0;
            }
            sb.append((h[i] & 0xff) % 10);
        }
        return sb.toString();
    }

    private static byte[] hash(String label) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest((DeviceSpoofManager.identitySeed(currentUserId()) + "|" + label)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static DeviceSpoofProfile profile() {
        return DeviceSpoofManager.getProfile(currentUserId());
    }

    private static int currentUserId() {
        try {
            return BActivityThread.getUserId();
        } catch (Throwable e) {
            return 0;
        }
    }

    static int guestTargetSdk() {
        try {
            if (BActivityThread.getApplication() != null) {
                return BActivityThread.getApplication().getApplicationInfo().targetSdkVersion;
            }
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Object current = runtime.getMethod("getRuntime").invoke(null);
            return (int) runtime.getMethod("getTargetSdkVersion").invoke(current);
        } catch (Throwable e) {
            return Build.VERSION.SDK_INT;
        }
    }
}

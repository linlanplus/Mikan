package top.niunaijun.blackbox.fake.device;

import android.os.Build;
import android.text.TextUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.utils.Slog;

public final class DeviceBuildSpoofer {
    private static final String TAG = "DeviceBuildSpoofer";

    private DeviceBuildSpoofer() {
    }

    public static void apply(DeviceSpoofProfile profile) {
        if (profile == null) {
            return;
        }
        // Build.VERSION stays the real one: applications branch on it, and a spoofed value sends
        // them down paths this framework does not implement.
        setStaticStringField("MANUFACTURER", profile.manufacturer);
        setStaticStringField("BRAND", profile.brand);
        setStaticStringField("MODEL", profile.model);
        setStaticStringField("DEVICE", profile.device);
        setStaticStringField("PRODUCT", profile.product);
        setStaticStringField("FINGERPRINT", profile.fingerprint);
        setStaticStringField("BOARD", profile.board);
        setStaticStringField("HARDWARE", profile.hardware);
        setStaticStringField("DISPLAY", DeviceSpoofManager.displayId(profile));
        setStaticStringField(Build.VERSION.class, "INCREMENTAL", DeviceSpoofManager.incremental(profile.serial));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Build.SOC_MANUFACTURER and Build.SOC_MODEL arrive with Android 12.
            setStaticStringField("SOC_MANUFACTURER", profile.socManufacturer);
            setStaticStringField("SOC_MODEL", profile.socModel);
        }
        if (DeviceIdentifiers.exposesBuildSerial()) {
            setStaticStringField("SERIAL", profile.serial);
        }
    }

    private static void setStaticStringField(String fieldName, String value) {
        setStaticStringField(Build.class, fieldName, value);
    }

    private static void setStaticStringField(Class<?> owner, String fieldName, String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            try {
                field.set(null, value);
                return;
            } catch (Throwable ignored) {
            }

            Object unsafe = getUnsafe();
            if (unsafe == null) {
                throw new IllegalStateException("Unsafe unavailable for " + fieldName);
            }
            Method staticFieldBase = unsafe.getClass().getMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafe.getClass().getMethod("staticFieldOffset", Field.class);
            Method putObject = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
            Object base = staticFieldBase.invoke(unsafe, field);
            long offset = ((Number) staticFieldOffset.invoke(unsafe, field)).longValue();
            putObject.invoke(unsafe, base, offset, value);
        } catch (Throwable e) {
            Slog.w(TAG, "Unable to spoof " + owner.getSimpleName() + "." + fieldName + ": " + e.getMessage());
        }
    }

    private static Object getUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable e) {
            Slog.w(TAG, "Unable to access Unsafe: " + e.getMessage());
            return null;
        }
    }
}

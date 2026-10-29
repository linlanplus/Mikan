package top.niunaijun.blackbox.fake.device;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.utils.Md5Utils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Per-user device identity. A user without a saved profile gets one derived from a per-install
 * salt, so each user poses as a different phone without any setup and keeps it across restarts.
 */
public final class DeviceSpoofManager {
    private static final String TAG = "DeviceSpoofManager";
    private static final String PREFS_NAME = "DeviceSpoofProfiles";

    private static final String KEY_INSTALL_SALT = "install_salt";
    private static final String KEY_MANUFACTURER = "manufacturer_";
    private static final String KEY_MODEL = "model_";
    private static final String KEY_BRAND = "brand_";
    private static final String KEY_DEVICE = "device_";
    private static final String KEY_PRODUCT = "product_";
    private static final String KEY_MARKET_NAME = "market_name_";
    private static final String KEY_SERIAL = "serial_";
    private static final String KEY_ANDROID_ID = "android_id_";

    // Coprime with the preset count, so consecutive users land on different presets.
    private static final int PRESET_STEP = 7;

    private static final String[] PRODUCT_PARTITIONS = {
            "", "system.", "vendor.", "odm.", "product.", "system_ext.", "bootimage.", "vendor_dlkm.", "odm_dlkm."
    };
    private static final String[] FINGERPRINT_PROPS = {
            "ro.build.fingerprint", "ro.system.build.fingerprint", "ro.vendor.build.fingerprint",
            "ro.odm.build.fingerprint", "ro.product.build.fingerprint", "ro.system_ext.build.fingerprint",
            "ro.bootimage.build.fingerprint", "ro.vendor_dlkm.build.fingerprint"
    };
    private static final String[] MARKET_NAME_PROPS = {
            "ro.product.marketname", "ro.product.vendor.marketname", "ro.product.odm.marketname",
            "ro.config.marketing_name", "ro.vendor.oplus.market.name", "ro.oppo.market.name",
            "ro.vivo.market.name", "ro.product.model.display"
    };

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceSpoofManager() {
    }

    @SuppressWarnings("deprecation")
    private static SharedPreferences prefs() {
        return BlackBoxCore.getContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    public static DeviceSpoofProfile getProfile(int userId) {
        SharedPreferences prefs = prefs();
        String manufacturer = prefs.getString(KEY_MANUFACTURER + userId, null);
        String model = prefs.getString(KEY_MODEL + userId, null);
        if (TextUtils.isEmpty(manufacturer) || TextUtils.isEmpty(model)) {
            return getDefaultProfile(userId);
        }
        DeviceSpoofProfile defaults = getDefaultProfile(userId);
        String brand = valueOrDefault(prefs.getString(KEY_BRAND + userId, null), slugify(manufacturer));
        String device = valueOrDefault(prefs.getString(KEY_DEVICE + userId, null), slugify(model));
        String product = valueOrDefault(prefs.getString(KEY_PRODUCT + userId, null), device);
        String serial = valueOrDefault(prefs.getString(KEY_SERIAL + userId, null), defaults.serial);
        return new DeviceSpoofProfile(
                manufacturer,
                brand,
                model,
                device,
                product,
                valueOrDefault(prefs.getString(KEY_MARKET_NAME + userId, null), model),
                buildFingerprint(brand, product, device, serial),
                serial,
                valueOrDefault(prefs.getString(KEY_ANDROID_ID + userId, null), defaults.androidId)
        );
    }

    /** The profile a user has until one is saved for it. */
    public static DeviceSpoofProfile getDefaultProfile(int userId) {
        String salt = installSalt();
        List<DevicePresets.Preset> presets = DevicePresets.all();
        int base = (String.valueOf(Md5Utils.md5(salt)).hashCode() & 0x7fffffff) % presets.size();
        DevicePresets.Preset preset = presets.get((base + userId * PRESET_STEP) % presets.size());
        return fromPreset(preset,
                hexFromSeed(salt + "_serial_" + userId, 12).toUpperCase(Locale.US),
                hexFromSeed(salt + "_android_id_" + userId, 16));
    }

    /** A random preset with fresh identifiers, for the "randomize" action. Not saved. */
    public static DeviceSpoofProfile randomProfile() {
        List<DevicePresets.Preset> presets = DevicePresets.all();
        return fromPreset(presets.get(RANDOM.nextInt(presets.size())), randomHex(12).toUpperCase(Locale.US), randomHex(16));
    }

    /** A preset keeping the given identifiers. */
    public static DeviceSpoofProfile presetProfile(DevicePresets.Preset preset, DeviceSpoofProfile current) {
        return fromPreset(preset, current.serial, current.androidId);
    }

    /**
     * Saves values edited by hand. Brand and codename come from a matching preset when the
     * manufacturer and model are one, otherwise they are derived from the names. [serial] is the
     * one shown on screen (a new one after "random device"); an invalid one keeps the saved serial.
     */
    public static void saveUserProfile(int userId, String marketName, String manufacturer, String model,
                                       String serial, String androidId) {
        DeviceSpoofProfile current = getProfile(userId);
        String safeManufacturer = manufacturer.trim();
        String safeModel = model.trim();
        String brand = slugify(safeManufacturer);
        String device = slugify(safeModel);
        String product = device;
        for (DevicePresets.Preset preset : DevicePresets.all()) {
            if (preset.manufacturer.equalsIgnoreCase(safeManufacturer) && preset.model.equalsIgnoreCase(safeModel)) {
                brand = preset.brand;
                device = preset.device;
                product = preset.product;
                break;
            }
        }
        String safeMarketName = TextUtils.isEmpty(marketName) ? safeModel : marketName.trim();
        String safeAndroidId = isValidAndroidId(androidId) ? androidId.trim().toLowerCase(Locale.US) : current.androidId;
        String safeSerial = isValidSerial(serial) ? serial.trim() : current.serial;
        prefs().edit()
                .putString(KEY_MANUFACTURER + userId, safeManufacturer)
                .putString(KEY_BRAND + userId, brand)
                .putString(KEY_MODEL + userId, safeModel)
                .putString(KEY_DEVICE + userId, device)
                .putString(KEY_PRODUCT + userId, product)
                .putString(KEY_MARKET_NAME + userId, safeMarketName)
                .putString(KEY_SERIAL + userId, safeSerial)
                .putString(KEY_ANDROID_ID + userId, safeAndroidId)
                .commit();
    }

    public static void resetToDefaults(int userId) {
        prefs().edit()
                .remove(KEY_MANUFACTURER + userId)
                .remove(KEY_BRAND + userId)
                .remove(KEY_MODEL + userId)
                .remove(KEY_DEVICE + userId)
                .remove(KEY_PRODUCT + userId)
                .remove(KEY_MARKET_NAME + userId)
                .remove(KEY_SERIAL + userId)
                .remove(KEY_ANDROID_ID + userId)
                .remove("fingerprint_" + userId)
                .commit();
    }

    public static void applyToCurrentProcess(int userId) {
        try {
            DeviceSpoofProfile profile = getProfile(userId);
            DeviceBuildSpoofer.apply(profile);

            List<String> names = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (String partition : PRODUCT_PARTITIONS) {
                String prefix = "ro.product." + partition;
                addProp(names, values, prefix + "manufacturer", profile.manufacturer);
                addProp(names, values, prefix + "brand", profile.brand);
                addProp(names, values, prefix + "model", profile.model);
                addProp(names, values, prefix + "device", profile.device);
                addProp(names, values, prefix + "name", profile.product);
            }
            for (String prop : FINGERPRINT_PROPS) {
                addProp(names, values, prop, profile.fingerprint);
            }
            for (String prop : MARKET_NAME_PROPS) {
                addProp(names, values, prop, profile.marketName);
            }
            addProp(names, values, "ro.build.product", profile.device);
            addProp(names, values, "ro.build.description", buildDescription(profile));
            addProp(names, values, "ro.serialno", profile.serial);
            addProp(names, values, "ro.boot.serialno", profile.serial);
            NativeCore.setPropOverrides(names.toArray(new String[0]), values.toArray(new String[0]));
            Slog.d(TAG, "Applied device spoof for user " + userId + ": " + profile.marketName
                    + " (" + profile.manufacturer + " " + profile.model + ")");
        } catch (Throwable e) {
            Slog.w(TAG, "Failed to apply device spoof for user " + userId + ": " + e.getMessage());
        }
    }

    public static String getAndroidIdForCurrentUser() {
        return getProfile(currentUserId()).androidId;
    }

    /**
     * Spoofed value of a Settings entry for the current guest app, or null to read the real one.
     *
     * @param table "secure", "global" or "system"
     */
    public static String getSettingOverride(String table, String key) {
        if (key == null || TextUtils.isEmpty(BActivityThread.getAppPackageName())) {
            return null;
        }
        if ("secure".equals(table) && "android_id".equals(key)) {
            return getAndroidIdForCurrentUser();
        }
        if ("device_name".equals(key) || "bluetooth_name".equals(key)) {
            return getProfile(currentUserId()).marketName;
        }
        return null;
    }

    private static boolean isValidSerial(String value) {
        return value != null && value.trim().matches("[A-Za-z0-9]{6,20}");
    }

    public static boolean isValidAndroidId(String value) {
        return !TextUtils.isEmpty(value) && value.trim().matches("(?i)[0-9a-f]{16}");
    }

    private static int currentUserId() {
        try {
            return BActivityThread.getUserId();
        } catch (Throwable e) {
            return 0;
        }
    }

    private static DeviceSpoofProfile fromPreset(DevicePresets.Preset preset, String serial, String androidId) {
        return new DeviceSpoofProfile(
                preset.manufacturer,
                preset.brand,
                preset.model,
                preset.device,
                preset.product,
                preset.marketName,
                buildFingerprint(preset.brand, preset.product, preset.device, serial),
                serial,
                androidId
        );
    }

    // Keeps the real Android version and build ID so the fingerprint agrees with Build.VERSION.
    private static String buildFingerprint(String brand, String product, String device, String serial) {
        return brand + "/" + product + "/" + device + ":" + Build.VERSION.RELEASE + "/" + Build.ID + "/"
                + incremental(serial) + ":user/release-keys";
    }

    private static String buildDescription(DeviceSpoofProfile profile) {
        return profile.product + "-user " + Build.VERSION.RELEASE + " " + Build.ID + " "
                + incremental(profile.serial) + " release-keys";
    }

    private static String incremental(String serial) {
        long value = Math.abs((long) String.valueOf(Md5Utils.md5(serial)).hashCode());
        return "V" + (value % 900000000L + 100000000L);
    }

    private static void addProp(List<String> names, List<String> values, String name, String value) {
        if (!TextUtils.isEmpty(value)) {
            names.add(name);
            values.add(value);
        }
    }

    /** Creates the per-install salt from the host, before any guest process can race to create it. */
    public static void ensureInstallSalt() {
        installSalt();
    }

    private static String installSalt() {
        SharedPreferences prefs = prefs();
        String salt = prefs.getString(KEY_INSTALL_SALT, null);
        if (TextUtils.isEmpty(salt)) {
            salt = randomHex(32);
            prefs.edit().putString(KEY_INSTALL_SALT, salt).commit();
        }
        return salt;
    }

    private static String hexFromSeed(String seed, int length) {
        String hash = Md5Utils.md5(seed);
        if (TextUtils.isEmpty(hash)) {
            return randomHex(length);
        }
        return hash.toLowerCase(Locale.US).substring(0, length);
    }

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(Character.forDigit(RANDOM.nextInt(16), 16));
        }
        return sb.toString();
    }

    private static String valueOrDefault(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private static String slugify(String input) {
        if (TextUtils.isEmpty(input)) {
            return "device";
        }
        String normalized = input.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "");
        return TextUtils.isEmpty(normalized) ? "device" : normalized;
    }
}

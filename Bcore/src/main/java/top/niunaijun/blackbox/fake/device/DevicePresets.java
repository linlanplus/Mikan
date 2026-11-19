package top.niunaijun.blackbox.fake.device;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Retail phones a clone can pose as. Identity strings follow each vendor's naming scheme. Chip
 * values come from getprop dumps of the phone, or of a phone with the same chip and vendor
 * (github.com/getActivity/AndroidSystemPropertyCollect, github.com/xdaGari/tadiphone-buildprop-archive).
 *
 * <p>{@code displayId} holds the build name only for vendors that replace the AOSP one. Huawei and
 * HONOR do, and on those phones {@code ro.huawei.build.display.id} carries the same string. Every
 * other preset leaves it empty and the clone keeps Build.ID, which is what a real phone of that
 * vendor reports.
 *
 * <p>Build.ID and Build.VERSION stay the real ones on purpose: applications branch on them, and a
 * spoofed value sends them down code paths the running framework does not implement.
 */
public final class DevicePresets {

    /** 32 hex digits, no separators. Xiaomi, OPPO, OnePlus, realme, vivo. */
    public static final int OAID_HEX32 = 0;
    /** 8-4-4-4-12 UUID. Huawei, HONOR, Samsung. */
    public static final int OAID_UUID36 = 1;
    /** No OAID service on the device. The clone reports GAID and stays silent about OAID. */
    public static final int OAID_NONE = 2;

    public static final class Preset {
        public final String marketName;
        public final String manufacturer;
        public final String brand;
        public final String model;
        public final String device;
        public final String product;
        /** Build.BOARD (ro.product.board). */
        public final String board;
        /** ro.board.platform, the chip platform. */
        public final String platform;
        /** Build.HARDWARE (ro.hardware): "qcom" on Snapdragon, the platform name on most other chips. */
        public final String hardware;
        /** Build.SOC_MANUFACTURER and Build.SOC_MODEL. */
        public final String socManufacturer;
        public final String socModel;
        /** OAID shape this vendor prints: one of the OAID_* constants above. */
        public final int oaidStyle;
        /** Build.DISPLAY where the vendor overrides the AOSP build ID; empty elsewhere. */
        public final String displayId;

        Preset(String marketName, String manufacturer, String brand, String model, String device, String product,
               String board, String platform, String hardware, String socManufacturer, String socModel,
               int oaidStyle, String displayId) {
            this.marketName = marketName;
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.model = model;
            this.device = device;
            this.product = product;
            this.board = board;
            this.platform = platform;
            this.hardware = hardware;
            this.socManufacturer = socManufacturer;
            this.socModel = socModel;
            this.oaidStyle = oaidStyle;
            this.displayId = displayId;
        }
    }

    // ROM names of the Huawei and HONOR presets follow the vendor scheme <model> <version>(<region>).
    // The version numbers sit inside the HarmonyOS 3.1/4.2 and MagicOS 7.1/7.2/8.0 series each phone
    // shipped with; check them against getprop ro.build.display.id on a real unit before release.
    private static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new Preset("Xiaomi 14", "Xiaomi", "Xiaomi", "23127PN0CC", "houji", "houji",
                    "houji", "pineapple", "qcom", "QTI", "SM8650", OAID_HEX32, ""),
            new Preset("Xiaomi 14 Pro", "Xiaomi", "Xiaomi", "23116PN5BC", "shennong", "shennong",
                    "shennong", "pineapple", "qcom", "QTI", "SM8650", OAID_HEX32, ""),
            new Preset("Xiaomi 13", "Xiaomi", "Xiaomi", "2211133C", "fuxi", "fuxi",
                    "kalama", "kalama", "qcom", "QTI", "SM8550", OAID_HEX32, ""),
            new Preset("Redmi K70", "Xiaomi", "Redmi", "23113RKC6C", "vermeer", "vermeer",
                    "kalama", "kalama", "qcom", "QTI", "SM8550", OAID_HEX32, ""),
            new Preset("Redmi K60", "Xiaomi", "Redmi", "23013RK75C", "mondrian", "mondrian",
                    "taro", "taro", "qcom", "QTI", "SM8475", OAID_HEX32, ""),
            new Preset("Redmi Note 13 Pro", "Xiaomi", "Redmi", "2312DRA50C", "garnet", "garnet",
                    "parrot", "parrot", "qcom", "QTI", "SM7435", OAID_HEX32, ""),
            new Preset("Redmi Turbo 3", "Xiaomi", "Redmi", "24069RA21C", "peridot", "peridot",
                    "peridot", "pineapple", "qcom", "QTI", "SM8635", OAID_HEX32, ""),
            new Preset("HONOR Magic6", "HONOR", "HONOR", "BVL-AN00", "HNBVL", "BVL-AN00",
                    "BVL", "pineapple", "qcom", "QTI", "SM8650", OAID_UUID36,
                    "BVL-AN00 8.0.0.130(C00E130R2P1)"),
            new Preset("HONOR 100", "HONOR", "HONOR", "MAA-AN00", "HNMAA", "MAA-AN00",
                    "MAA", "crow", "qcom", "QTI", "SM7550", OAID_UUID36,
                    "MAA-AN00 7.2.0.132(C00E130R2P2)"),
            new Preset("HONOR 90", "HONOR", "HONOR", "REA-AN00", "HNREA", "REA-AN00",
                    "REA", "taro", "qcom", "QTI", "SM7450", OAID_UUID36,
                    "REA-AN00 7.1.0.152(C00E150R2P3)"),
            new Preset("HUAWEI Mate 60 Pro", "HUAWEI", "HUAWEI", "ALN-AL00", "HWALN", "ALN-AL00",
                    "ALN", "kirin9000s", "kirin9000s", "Hisilicon", "Kirin", OAID_UUID36,
                    "ALN-AL00 4.2.0.126(CN01)"),
            new Preset("HUAWEI P60", "HUAWEI", "HUAWEI", "LNA-AL00", "HWLNA", "LNA-AL00",
                    "LNA", "taro", "qcom", "QTI", "SM8475", OAID_UUID36,
                    "LNA-AL00 3.1.0.162(CN01)"),
            new Preset("HUAWEI nova 12", "HUAWEI", "HUAWEI", "BLK-AL00", "HWBLK", "BLK-AL00",
                    "BLK", "kirin830", "kirin830", "Hisilicon", "Kirin", OAID_UUID36,
                    "BLK-AL00 4.2.0.128(CN01)"),
            new Preset("OPPO Find X7", "OPPO", "OPPO", "PHZ110", "OP5D2BL1", "PHZ110",
                    "k6989v1_64", "mt6989", "mt6989", "Mediatek", "MT6989", OAID_HEX32, ""),
            new Preset("OPPO Reno11", "OPPO", "OPPO", "PJH110", "OP5A0FL1", "PJH110",
                    "k6895v1_64", "mt6895", "mt6895", "Mediatek", "MT6896", OAID_HEX32, ""),
            new Preset("OnePlus 12", "OnePlus", "OnePlus", "PJD110", "OP595DL1", "PJD110",
                    "pineapple", "pineapple", "qcom", "QTI", "SM8650", OAID_HEX32, ""),
            new Preset("OnePlus Ace 3", "OnePlus", "OnePlus", "PJE110", "OP5D0DL1", "PJE110",
                    "kalama", "kalama", "qcom", "QTI", "SM8550", OAID_HEX32, ""),
            new Preset("vivo X100", "vivo", "vivo", "V2309A", "PD2309", "PD2309",
                    "k6989v1_64", "mt6989", "mt6989", "Mediatek", "MT6989", OAID_HEX32, ""),
            new Preset("vivo S18", "vivo", "vivo", "V2323A", "PD2323", "PD2323",
                    "crow", "crow", "qcom", "QTI", "SM7550", OAID_HEX32, ""),
            new Preset("iQOO 12", "vivo", "iQOO", "V2307A", "PD2307", "PD2307",
                    "pineapple", "pineapple", "qcom", "QTI", "SM8650", OAID_HEX32, ""),
            new Preset("realme GT5", "realme", "realme", "RMX3820", "RE5C82L1", "RMX3820",
                    "kalama", "kalama", "qcom", "QTI", "SM8550", OAID_HEX32, ""),
            new Preset("Galaxy S24", "samsung", "samsung", "SM-S9210", "e1q", "e1qzcx",
                    "pineapple", "pineapple", "qcom", "QTI", "SM8650", OAID_UUID36, ""),
            new Preset("Galaxy S23", "samsung", "samsung", "SM-S9110", "dm1q", "dm1qzcx",
                    "kalama", "kalama", "qcom", "QTI", "SM8550", OAID_UUID36, ""),
            new Preset("Pixel 8", "Google", "google", "Pixel 8", "shiba", "shiba",
                    "shiba", "zuma", "shiba", "Google", "Tensor G3", OAID_NONE, ""),
            new Preset("Pixel 7", "Google", "google", "Pixel 7", "panther", "panther",
                    "panther", "gs201", "panther", "Google", "GS201", OAID_NONE, "")
    ));

    private DevicePresets() {
    }

    public static List<Preset> all() {
        return PRESETS;
    }

    /** The preset a profile was made from, or null for values entered by hand. */
    public static Preset find(String manufacturer, String model) {
        for (Preset preset : PRESETS) {
            if (preset.manufacturer.equalsIgnoreCase(manufacturer) && preset.model.equalsIgnoreCase(model)) {
                return preset;
            }
        }
        return null;
    }
}

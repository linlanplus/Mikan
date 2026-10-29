package top.niunaijun.blackbox.fake.device;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Retail phones a clone can pose as. Identity strings follow each vendor's naming scheme. */
public final class DevicePresets {

    public static final class Preset {
        public final String marketName;
        public final String manufacturer;
        public final String brand;
        public final String model;
        public final String device;
        public final String product;

        Preset(String marketName, String manufacturer, String brand, String model, String device, String product) {
            this.marketName = marketName;
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.model = model;
            this.device = device;
            this.product = product;
        }
    }

    private static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new Preset("Xiaomi 14", "Xiaomi", "Xiaomi", "23127PN0CC", "houji", "houji"),
            new Preset("Xiaomi 14 Pro", "Xiaomi", "Xiaomi", "23116PN5BC", "shennong", "shennong"),
            new Preset("Xiaomi 13", "Xiaomi", "Xiaomi", "2211133C", "fuxi", "fuxi"),
            new Preset("Redmi K70", "Xiaomi", "Redmi", "23113RKC6C", "vermeer", "vermeer"),
            new Preset("Redmi K60", "Xiaomi", "Redmi", "23013RK75C", "mondrian", "mondrian"),
            new Preset("Redmi Note 13 Pro", "Xiaomi", "Redmi", "2312DRA50C", "garnet", "garnet"),
            new Preset("Redmi Turbo 3", "Xiaomi", "Redmi", "24069RA21C", "peridot", "peridot"),
            new Preset("HONOR Magic6", "HONOR", "HONOR", "BVL-AN00", "HNBVL", "BVL-AN00"),
            new Preset("HONOR 100", "HONOR", "HONOR", "MAA-AN00", "HNMAA", "MAA-AN00"),
            new Preset("HONOR 90", "HONOR", "HONOR", "REA-AN00", "HNREA", "REA-AN00"),
            new Preset("HUAWEI Mate 60 Pro", "HUAWEI", "HUAWEI", "ALN-AL00", "HWALN", "ALN-AL00"),
            new Preset("HUAWEI P60", "HUAWEI", "HUAWEI", "LNA-AL00", "HWLNA", "LNA-AL00"),
            new Preset("HUAWEI nova 12", "HUAWEI", "HUAWEI", "BLK-AL00", "HWBLK", "BLK-AL00"),
            new Preset("OPPO Find X7", "OPPO", "OPPO", "PHZ110", "OP5D2BL1", "PHZ110"),
            new Preset("OPPO Reno11", "OPPO", "OPPO", "PJH110", "OP5A0FL1", "PJH110"),
            new Preset("OnePlus 12", "OnePlus", "OnePlus", "PJD110", "OP595DL1", "PJD110"),
            new Preset("OnePlus Ace 3", "OnePlus", "OnePlus", "PJE110", "OP5D0DL1", "PJE110"),
            new Preset("vivo X100", "vivo", "vivo", "V2309A", "PD2309", "PD2309"),
            new Preset("vivo S18", "vivo", "vivo", "V2323A", "PD2323", "PD2323"),
            new Preset("iQOO 12", "vivo", "iQOO", "V2307A", "PD2307", "PD2307"),
            new Preset("realme GT5", "realme", "realme", "RMX3820", "RE5C82L1", "RMX3820"),
            new Preset("Galaxy S24", "samsung", "samsung", "SM-S9210", "e1q", "e1qzcx"),
            new Preset("Galaxy S23", "samsung", "samsung", "SM-S9110", "dm1q", "dm1qzcx"),
            new Preset("Pixel 8", "Google", "google", "Pixel 8", "shiba", "shiba"),
            new Preset("Pixel 7", "Google", "google", "Pixel 7", "panther", "panther")
    ));

    private DevicePresets() {
    }

    public static List<Preset> all() {
        return PRESETS;
    }
}

package top.niunaijun.blackbox.fake.device;

public class DeviceSpoofProfile {
    public final String manufacturer;
    public final String brand;
    public final String model;
    public final String device;
    public final String product;
    /** Retail name shown to users, e.g. "Xiaomi 14". Also used as the device and Bluetooth name. */
    public final String marketName;
    public final String fingerprint;
    public final String serial;
    public final String androidId;
    /** Build.BOARD, ro.product.board. Empty for a hand-entered model with no matching preset. */
    public final String board;
    /** ro.board.platform, the chip platform name. */
    public final String platform;
    /** Build.HARDWARE, ro.hardware. */
    public final String hardware;
    /** Build.SOC_MANUFACTURER and Build.SOC_MODEL (API 31 and later). */
    public final String socManufacturer;
    public final String socModel;
    /**
     * Build.DISPLAY, ro.build.display.id and ro.huawei.build.display.id on the vendors that replace
     * the AOSP name (Huawei, HONOR). Empty elsewhere, where the clone keeps the real Build.ID.
     */
    public final String displayId;
    /** This clone's OAID, already in the shape the pose vendor prints. Empty means none. */
    public final String oaid;

    public DeviceSpoofProfile(
            String manufacturer,
            String brand,
            String model,
            String device,
            String product,
            String marketName,
            String fingerprint,
            String serial,
            String androidId,
            String board,
            String platform,
            String hardware,
            String socManufacturer,
            String socModel,
            String displayId,
            String oaid
    ) {
        this.manufacturer = manufacturer;
        this.brand = brand;
        this.model = model;
        this.device = device;
        this.product = product;
        this.marketName = marketName;
        this.fingerprint = fingerprint;
        this.serial = serial;
        this.androidId = androidId;
        this.board = board;
        this.platform = platform;
        this.hardware = hardware;
        this.socManufacturer = socManufacturer;
        this.socModel = socModel;
        this.displayId = displayId;
        this.oaid = oaid;
    }
}

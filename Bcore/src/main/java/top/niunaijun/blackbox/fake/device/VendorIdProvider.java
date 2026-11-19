package top.niunaijun.blackbox.fake.device;

/**
 * Values behind the clone's copy of the MIUI identifier provider.
 *
 * <p>{@code com.android.id.impl.IdProviderImpl} delegates here, so the identity logic stays in one
 * place and the vendor-named class holds nothing but signatures.
 */
public final class VendorIdProvider {

    private VendorIdProvider() {
    }

    public static String getOAID() {
        return DeviceSpoofManager.getOaidForCurrentUser();
    }

    public static String getVAID() {
        return DeviceIdentifiers.hex("vaid", 32);
    }

    public static String getAAID() {
        return DeviceIdentifiers.hex("aaid", 32);
    }
}

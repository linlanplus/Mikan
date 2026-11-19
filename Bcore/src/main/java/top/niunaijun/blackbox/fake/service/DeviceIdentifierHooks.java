package top.niunaijun.blackbox.fake.service;

import android.os.Build;
import android.telephony.SubscriptionManager;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.fake.device.DeviceIdentifiers;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;

/**
 * IMEI, MEID, IMSI, ICCID and serial reads, shared by the telephony, phone sub-info and
 * device-identifier services. Every clone gets its own values; see {@link DeviceIdentifiers}.
 */
public class DeviceIdentifierHooks {

    @ProxyMethods({"getDeviceId", "getDeviceIdWithFeature", "getImeiForSlot", "getPrimaryImei",
            "getDeviceIdForPhone", "getImeiForSubscriber"})
    public static class Imei extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            int index = firstInt(args, 0);
            int slot = "getImeiForSubscriber".equals(name) ? DeviceIdentifiers.slotOf(index) : index;
            return DeviceIdentifiers.imei(name, slot);
        }
    }

    @ProxyMethods({"getMeidForSlot"})
    public static class Meid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return DeviceIdentifiers.meid(method.getName(), firstInt(args, 0));
        }
    }

    @ProxyMethods({"getTypeAllocationCodeForSlot"})
    public static class TypeAllocationCode extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return DeviceIdentifiers.typeAllocationCode(firstInt(args, 0));
        }
    }

    @ProxyMethods({"getManufacturerCodeForSlot"})
    public static class ManufacturerCode extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return DeviceIdentifiers.manufacturerCode(firstInt(args, 0));
        }
    }

    @ProxyMethods({"getSubscriberId", "getSubscriberIdWithFeature", "getSubscriberIdForSubscriber"})
    public static class SubscriberId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return DeviceIdentifiers.subscriberId(method.getName(), firstInt(args, defaultSubscription()));
        }
    }

    @ProxyMethods({"getIccSerialNumber", "getIccSerialNumberWithFeature", "getIccSerialNumberForSubscriber"})
    public static class IccSerialNumber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return DeviceIdentifiers.iccid(method.getName(), firstInt(args, defaultSubscription()));
        }
    }

    @ProxyMethods({"getSerial", "getSerialForPackage"})
    public static class Serial extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return DeviceIdentifiers.serial(method.getName());
        }
    }

    private static int firstInt(Object[] args, int fallback) {
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Integer) return (Integer) arg;
            }
        }
        return fallback;
    }

    private static int defaultSubscription() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? SubscriptionManager.getDefaultSubscriptionId() : -1;
    }
}

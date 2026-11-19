package top.niunaijun.blackbox.fake.device;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dalvik.system.DexClassLoader;
import top.niunaijun.blackbox.utils.Slog;

/**
 * Redirects the vendor identifier providers that an application reaches by reflection.
 *
 * <p>Xiaomi does not bind a service. com.android.id.impl.IdProviderImpl lives in the MIUI
 * framework, and an OAID SDK calls it inside its own process with
 * {@code Class.forName("com.android.id.impl.IdProviderImpl")}. No binder, provider or property is
 * involved, so nothing on those layers sees the call.
 *
 * <p>The lookup itself is reachable: a class is resolved through the loader of the calling class,
 * which is the guest loader, and that loader asks its parent first. Putting this shim in between
 * answers the name before the boot loader hands over the real MIUI class.
 *
 * <p>The replacement has to carry the very same name. The runtime checks what loadClass returns and
 * fails the lookup with "returned class X instead of Y" otherwise, so a differently named class
 * cannot be substituted. The host dex holds its own com.android.id.impl.IdProviderImpl, read here
 * through a dedicated DexClassLoader: loadClass would ask the boot loader first and hand back the
 * MIUI original, and even the host loader's own findClass answers with the boot copy on MIUI, so
 * the lookup is made against a loader that has nothing but the host apk in its path.
 */
public final class VendorClassShim extends ClassLoader {
    private static final String TAG = "VendorClassShim";

    /** Names answered by the clone instead of the vendor framework. */
    private static final String XIAOMI_ID_PROVIDER = "com.android.id.impl.IdProviderImpl";

    private final ClassLoader mHostLoader;
    private ClassLoader mHostDex;

    private VendorClassShim(ClassLoader parent, ClassLoader hostLoader) {
        super(parent);
        mHostLoader = hostLoader;
    }

    /**
     * Puts the shim between the guest loader and its real parent. Called while the bound
     * application is being set up, before any guest class can be cached.
     */
    public static void install(ClassLoader guest) {
        if (guest == null || guest instanceof VendorClassShim) {
            return;
        }
        try {
            ClassLoader parent = guest.getParent();
            if (parent instanceof VendorClassShim) {
                return;
            }
            // The loader that defined this class is the host one: BActivityThread reaches it from
            // the host dex. BlackBoxCore.getContext().getClassLoader() is the guest loader instead.
            ClassLoader host = VendorClassShim.class.getClassLoader();
            if (host == null) {
                Slog.w(TAG, "no host class loader, vendor shim skipped");
                return;
            }
            VendorClassShim shim = new VendorClassShim(parent, host);
            if (shim.findInHost(XIAOMI_ID_PROVIDER) == null) {
                Slog.w(TAG, "host dex has no " + XIAOMI_ID_PROVIDER + ", vendor shim skipped");
                return;
            }
            setParent(guest, shim);
            Slog.d(TAG, "vendor class shim installed for " + guest);
        } catch (Throwable e) {
            Slog.w(TAG, "unable to install the vendor class shim: " + e.getMessage());
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (XIAOMI_ID_PROVIDER.equals(name)) {
            Class<?> replacement = findInHost(name);
            if (replacement != null) {
                Slog.d(TAG, "answered " + name + " from the host dex");
                return replacement;
            }
        }
        return super.loadClass(name, resolve);
    }

    /** findClass looks at this loader's own dex only, which is exactly what is wanted here. */
    private Class<?> findInHost(String name) {
        try {
            ClassLoader dex = hostDexLoader();
            if (dex == null) {
                Slog.w(TAG, "no host dex loader");
                return null;
            }
            Method findClass = ClassLoader.class.getDeclaredMethod("findClass", String.class);
            findClass.setAccessible(true);
            return (Class<?>) findClass.invoke(dex, name);
        } catch (Throwable e) {
            Slog.w(TAG, "host dex has no " + name + ": " + e);
            return null;
        }
    }

    private synchronized ClassLoader hostDexLoader() {
        if (mHostDex != null) {
            return mHostDex;
        }
        String apk = hostApkPath(mHostLoader);
        if (apk == null) {
            return null;
        }
        // Parent is the host loader so this copy can reach the BlackBox classes it delegates to;
        // findClass still looks at this loader's own dex only.
        mHostDex = new DexClassLoader(apk, null, null, mHostLoader);
        return mHostDex;
    }

    /** First entry of a BaseDexClassLoader's dex list, which is the host apk. */
    private static String hostApkPath(ClassLoader host) {
        try {
            Class<?> base = Class.forName("dalvik.system.BaseDexClassLoader");
            Field pathListField = base.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(host);
            if (pathList == null) {
                return null;
            }
            Field elementsField = pathList.getClass().getDeclaredField("dexElements");
            elementsField.setAccessible(true);
            Object[] elements = (Object[]) elementsField.get(pathList);
            if (elements == null || elements.length == 0) {
                return null;
            }
            Field pathField = elements[0].getClass().getDeclaredField("path");
            pathField.setAccessible(true);
            Object path = pathField.get(elements[0]);
            // Android 15 turned Element.path from a String into a File.
            if (path instanceof File) {
                return ((File) path).getPath();
            }
            return path instanceof String ? (String) path : null;
        } catch (Throwable e) {
            Slog.w(TAG, "unable to read the host apk path: " + e);
            return null;
        }
    }

    /** ClassLoader.parent is final; the runtime does not refuse a direct field write. */
    private static void setParent(ClassLoader loader, ClassLoader parent) throws Exception {
        Field field = ClassLoader.class.getDeclaredField("parent");
        field.setAccessible(true);
        Object unsafe = getUnsafe();
        if (unsafe == null) {
            throw new IllegalStateException("Unsafe unavailable");
        }
        Method offset = unsafe.getClass().getMethod("objectFieldOffset", Field.class);
        Method put = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
        put.invoke(unsafe, loader, ((Number) offset.invoke(unsafe, field)).longValue(), parent);
    }

    private static Object getUnsafe() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable e) {
            Slog.w(TAG, "unable to access Unsafe: " + e.getMessage());
            return null;
        }
    }
}

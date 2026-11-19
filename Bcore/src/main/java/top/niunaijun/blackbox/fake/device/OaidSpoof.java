package top.niunaijun.blackbox.fake.device;

import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.text.TextUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import black.android.app.BRIServiceConnectionO;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.Slog;

/**
 * The clone's OAID.
 *
 * <p>An OAID SDK bundled in a guest does not read a property: it binds a system service
 * (com.mdid.msa, com.miui.systemAdSolution, com.heytap.openid, com.vivo.identifier, the HMS
 * identifier service of Huawei and HONOR, ...) and asks one AIDL method for a string. Bound that
 * way, every clone used to get the real OAID of the phone, because the host package is the caller.
 *
 * <p>Here the bind of those services is answered locally instead: bindService reports success and
 * onServiceConnected receives a Binder owned by the clone that answers the transaction with the
 * clone's own value. The SDK cannot tell the difference, a phone without the vendor service works
 * all the same, and two clones never share a value.
 *
 * <p>vivo does not bind: it reads content://com.vivo.vms.IdProvider, answered by
 * {@link #answerVivoQuery}. Xiaomi calls com.android.id.impl.IdProviderImpl in process through
 * reflection, which no binder can see; {@link VendorClassShim} answers that lookup instead.
 *
 * <p>Only a guest process is served; the host keeps reading the real one.
 */
public final class OaidSpoof {
    private static final String TAG = "OaidSpoof";

    /** Packages that ship an OAID service. */
    private static final String[] PROVIDER_PACKAGES = {
            "com.mdid.msa",                        // MSA shared service
            "com.miui.systemAdSolution",           // MIUI, Redmi, Black Shark
            "com.heytap.openid",                   // ColorOS, realme
            "com.oplus.stdid",                     // OnePlus and newer ColorOS
            "com.vivo.identifier",                 // OriginOS, Funtouch
            "com.samsung.android.deviceidservice", // Samsung China firmware
            "com.zui.deviceidservice",             // Lenovo
            "com.asus.msa",                        // ASUS
            "com.coolpad.deviceidsupport",         // Coolpad
            "com.qiku.id",                         // QiKu
            "com.android.creator",                 // Freeme OS
    };

    /** Vendor families whose services are OAID providers once the class or action says so. */
    private static final String[] VENDOR_PREFIXES = {
            "com.huawei.hms", "com.huawei.hwid", "com.hihonor", "com.honor",
            "com.bun.", "com.mdid", "com.miui", "com.heytap", "com.oplus",
            "com.vivo", "com.samsung", "com.zui", "com.asus", "com.coolpad",
            "com.qiku", "com.android.creator", "com.meizu", "com.oneplus", "com.realme",
    };

    /** Fragments that mark a service as an advertising identifier provider. */
    private static final String[] MARKERS = {
            "oaid", "advertisingid", "opendevice", "openids", "open_id", "openid",
            "identifier", "deviceidservice", "msaid",
    };

    /**
     * Real binder dispatch reaches a system service on a binder thread, after bindService() has
     * returned. The delay keeps that ordering, which SDKs rely on when they wait on a latch.
     */
    private static final long CONNECT_DELAY_MS = 20L;

    /**
     * HONOR's identifier interface, read from com.hihonor.id of MagicOS 10. The package also holds
     * the account, wallet and cloud services, so it is matched by this descriptor and the service
     * action rather than by the package alone.
     */
    private static final String HONOR_OAID = "com.hihonor.cloudservice.oaid.IOAIDService";
    private static final String HONOR_CALLBACK = "com.hihonor.cloudservice.oaid.IOAIDCallBack";
    /** IOAIDCallBack declares basicTypes first, handleResult second. */
    private static final int HONOR_HANDLE_RESULT = IBinder.FIRST_CALL_TRANSACTION + 1;

    private static final Map<String, IBinder> sBinders = new HashMap<>();
    private static final Set<IBinder> sFakedConnections = new HashSet<>();
    private static final Object sLock = new Object();

    private static volatile String sCurrentOaid = "";
    private static volatile Handler sHandler;

    private OaidSpoof() {
    }

    /** Called once per guest process, before the guest Application exists. */
    public static synchronized void install(String oaid) {
        sCurrentOaid = oaid == null ? "" : oaid;
        if (TextUtils.isEmpty(sCurrentOaid)) {
            return;
        }
        IBinder binder = binderFor(sCurrentOaid);
        try {
            Map<String, IBinder> cache = BRServiceManager.get().sCache();
            if (cache != null) {
                // Answers a guest that asks ServiceManager for "oaid" directly instead of binding.
                cache.put("oaid", binder);
            }
        } catch (Throwable e) {
            Slog.w(TAG, "unable to preset the oaid service: " + e.getMessage());
        }
        Slog.d(TAG, "installed OAID for " + packageName() + ": " + sCurrentOaid);
    }

    /** Answers the bind of an OAID service locally. Returns false to let the real bind through. */
    public static boolean tryFakeBind(Intent intent, IServiceConnection connection) {
        if (connection == null || !isEnabled() || !isOaidIntent(intent)) {
            return false;
        }
        final String oaid = sCurrentOaid;
        if (TextUtils.isEmpty(oaid)) {
            return false;
        }
        final ComponentName target = componentOf(intent);
        final IBinder binder = binderFor(oaid);
        final IServiceConnection conn = connection;
        synchronized (sLock) {
            sFakedConnections.add(connection.asBinder());
        }
        handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                deliver(conn, target, binder);
            }
        }, CONNECT_DELAY_MS);
        Slog.d(TAG, "faked OAID service " + target + " for " + packageName());
        return true;
    }

    /** True once, for the unbind that follows a faked bind. */
    public static boolean consumeFakedConnection(IBinder connection) {
        if (connection == null) {
            return false;
        }
        synchronized (sLock) {
            return sFakedConnections.remove(connection);
        }
    }

    /**
     * The identifier an AIDL interface reaches the app with through the bind callback. Replaces a
     * real vendor service with the clone's own, for binds that were not answered already.
     */
    public static IBinder wrap(Intent intent, IBinder service) {
        if (service == null || !isEnabled() || !isOaidIntent(intent)) {
            return service;
        }
        String oaid = sCurrentOaid;
        if (TextUtils.isEmpty(oaid)) {
            return service;
        }
        return binderFor(oaid);
    }

    /**
     * vivo never binds: it reads content://com.vivo.vms.IdProvider/IdentifierId/{OAID,VAID,AAID}
     * and takes the "value" column. Returns null for every other provider.
     */
    public static Cursor answerVivoQuery(Object[] args) {
        if (args == null || !isEnabled()) {
            return null;
        }
        String oaid = sCurrentOaid;
        if (TextUtils.isEmpty(oaid)) {
            return null;
        }
        Uri uri = null;
        for (Object arg : args) {
            if (arg instanceof Uri) {
                uri = (Uri) arg;
                break;
            }
        }
        if (uri == null) {
            return null;
        }
        String authority = uri.getAuthority();
        if (authority == null || !authority.startsWith("com.vivo.vms")) {
            return null;
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String kind = path.substring(path.lastIndexOf('/') + 1).toUpperCase(Locale.US);
        String value;
        if ("OAID".equals(kind)) {
            value = oaid;
        } else if ("VAID".equals(kind)) {
            value = derived("vaid", oaid);
        } else if ("AAID".equals(kind)) {
            value = derived("aaid", oaid);
        } else {
            return null;
        }
        MatrixCursor cursor = new MatrixCursor(new String[]{"_id", "value"});
        cursor.addRow(new Object[]{0, value});
        Slog.d(TAG, "answered vivo " + kind + " for " + packageName());
        return cursor;
    }

    /** Whether an intent targets one of the vendor identifier services. */
    public static boolean isOaidIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        ComponentName component = intent.getComponent();
        String packageName = component != null ? component.getPackageName() : intent.getPackage();
        String className = component != null ? component.getClassName() : null;
        if (isProvider(packageName)) {
            return true;
        }
        if (!isVendorFamily(packageName)) {
            return false;
        }
        // Huawei ships its identifier service inside the general com.huawei.hms package.
        return containsMarker(intent.getAction()) || containsMarker(className);
    }

    private static boolean isProvider(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        String name = packageName.toLowerCase(Locale.US);
        for (String provider : PROVIDER_PACKAGES) {
            if (name.equals(provider) || name.startsWith(provider + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVendorFamily(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        if (isProvider(packageName)) {
            return true;
        }
        String name = packageName.toLowerCase(Locale.US);
        for (String prefix : VENDOR_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMarker(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        for (String marker : MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static ComponentName componentOf(Intent intent) {
        ComponentName component = intent.getComponent();
        if (component != null) {
            return component;
        }
        String packageName = intent.getPackage();
        return new ComponentName(packageName != null ? packageName : "com.mdid.msa", "OaidService");
    }

    private static void deliver(IServiceConnection connection, ComponentName name, IBinder binder) {
        if (deliverWithSession(connection, name, binder)) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // The framework interface carries a "dead" flag from Android 8 on.
                BRIServiceConnectionO.get(connection).connected(name, binder, false);
            } else {
                connection.connected(name, binder);
            }
        } catch (Throwable e) {
            Slog.w(TAG, "onServiceConnected dispatch failed: " + e.getMessage());
        }
    }

    /**
     * Android 16 changed the callback to connected(name, binder, IBinderSession, dead). The
     * signature is looked up by arity, which keeps the new session type out of this source. A null
     * session is accepted by the dispatcher, which passes it on untouched.
     */
    private static boolean deliverWithSession(IServiceConnection connection, ComponentName name, IBinder binder) {
        try {
            for (Method method : connection.getClass().getMethods()) {
                if (!"connected".equals(method.getName()) || method.getParameterTypes().length != 4) {
                    continue;
                }
                method.invoke(connection, name, binder, null, false);
                return true;
            }
        } catch (Throwable e) {
            Slog.w(TAG, "four-argument onServiceConnected failed: " + e.getMessage());
        }
        return false;
    }

    private static synchronized IBinder binderFor(String oaid) {
        IBinder binder = sBinders.get(oaid);
        if (binder == null) {
            binder = new SpoofBinder(oaid, derived("vaid", oaid), derived("aaid", oaid));
            sBinders.put(oaid, binder);
        }
        return binder;
    }

    /**
     * Answers the AIDL transaction of any vendor. Every OAID getter returns a String, so the
     * default reply is one no-exception header and one string. The few methods that return
     * something else are keyed off the interface descriptor.
     */
    private static final class SpoofBinder extends Binder {
        private final String oaid;
        private final String vaid;
        private final String aaid;

        SpoofBinder(String oaid, String vaid, String aaid) {
            this.oaid = oaid;
            this.vaid = vaid;
            this.aaid = aaid;
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code < IBinder.FIRST_CALL_TRANSACTION || code > 0x5f000000) {
                // INTERFACE_TRANSACTION, PING, DUMP and the other framework codes.
                return super.onTransact(code, data, reply, flags);
            }
            if (reply == null) {
                // A oneway call takes no reply parcel.
                return true;
            }
            String descriptor = readInterfaceDescriptor(data);
            int index = code - IBinder.FIRST_CALL_TRANSACTION;
            if (HONOR_OAID.equals(descriptor)) {
                return answerHonor(reply, data, index);
            }
            reply.writeNoException();
            if (isTrackLimited(descriptor, index)) {
                // Huawei asks isOaidTrackLimited() as a boolean. Zero means tracking is allowed,
                // which agrees with the limit_ad_tracking value served through Settings.
                reply.writeInt(0);
                return true;
            }
            reply.writeString(valueFor(index, data));
            return true;
        }

        /**
         * HONOR answers in two shapes. The sync getters return through the reply parcel, the async
         * ones take an IOAIDCallBack and answer through it; a value written into the reply of the
         * latter would never be read and the SDK would sit on its latch until it timed out.
         */
        private boolean answerHonor(Parcel reply, Parcel data, int index) {
            reply.writeNoException();
            switch (index) {
                case 0:
                    // basicTypes(int, long, boolean, float, double, String), returns nothing.
                    return true;
                case 1: {
                    Bundle bundle = new Bundle();
                    bundle.putString("oa_id_flag", oaid);
                    sendHonorCallback(data, bundle);
                    return true;
                }
                case 2:
                    reply.writeString(oaid);
                    return true;
                case 3: {
                    Bundle bundle = new Bundle();
                    bundle.putBoolean("oa_id_limit_state", false);
                    sendHonorCallback(data, bundle);
                    return true;
                }
                case 4:
                    reply.writeInt(0);
                    return true;
                default:
                    reply.writeString(oaid);
                    return true;
            }
        }

        /** Calls IOAIDCallBack.handleResult(0, bundle) on the callback the caller handed over. */
        private void sendHonorCallback(Parcel data, Bundle bundle) {
            IBinder callback = null;
            try {
                int start = data.dataPosition();
                data.setDataPosition(0);
                skipInterfaceToken(data);
                callback = data.readStrongBinder();
                data.setDataPosition(start);
            } catch (Throwable e) {
                return;
            }
            if (callback == null) {
                return;
            }
            Parcel out = Parcel.obtain();
            Parcel in = Parcel.obtain();
            try {
                out.writeInterfaceToken(HONOR_CALLBACK);
                out.writeInt(0);
                // An in Bundle travels as a typed object: a 1 for "present", then the parcel.
                // writeBundle would leave that marker out, and the generated Stub, which reads an
                // int and then Bundle.CREATOR.createFromParcel, would parse from the wrong offset.
                out.writeInt(1);
                bundle.writeToParcel(out, 0);
                callback.transact(HONOR_HANDLE_RESULT, out, in, IBinder.FLAG_ONEWAY);
            } catch (Throwable e) {
                Slog.w(TAG, "HONOR callback failed: " + e.getMessage());
            } finally {
                out.recycle();
                in.recycle();
            }
        }

        private String valueFor(int index, Parcel data) {
            String kind = readIdentifierKind(data);
            if (kind != null) {
                // OPPO and close relatives pass the kind as an argument: getSerID(pkg, sign, type).
                if (kind.startsWith("D")) {
                    return aaid;
                }
                if (kind.startsWith("G")) {
                    return vaid;
                }
                return oaid;
            }
            // Elsewhere the method order is getOAID(), getVAID(), getAAID().
            if (index == 0) {
                return oaid;
            }
            if (index == 1) {
                return vaid;
            }
            return aaid;
        }
    }

    /** Huawei's isOaidTrackLimited() is the second method of its identifier interface. */
    private static boolean isTrackLimited(String descriptor, int index) {
        if (descriptor == null) {
            return false;
        }
        return descriptor.toLowerCase(Locale.US).contains("opendevice") && index == 1;
    }

    /**
     * Reads the interface name an AIDL proxy wrote. The token is a strict-mode int, the calling
     * work source from Android 10, a header int from Android 11, and then the name. Returns null
     * when the parcel does not start with a token, which happens with hand-written binders.
     */
    private static String readInterfaceDescriptor(Parcel data) {
        if (data == null) {
            return null;
        }
        int start = data.dataPosition();
        try {
            data.setDataPosition(0);
            skipTokenHeader(data);
            String descriptor = data.readString();
            data.setDataPosition(start);
            return descriptor != null && descriptor.indexOf('.') > 0 ? descriptor : null;
        } catch (Throwable e) {
            try {
                data.setDataPosition(start);
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /**
     * Reads the OUID/GUID/DUID argument OPPO passes to getSerID. The scan starts after the
     * interface token, so the header fields are skipped first.
     */
    private static String readIdentifierKind(Parcel data) {
        if (data == null) {
            return null;
        }
        int start = data.dataPosition();
        try {
            data.setDataPosition(0);
            skipInterfaceToken(data);
            String kind = null;
            for (int i = 0; i < 3; i++) {
                String value = data.readString();
                if (value == null) {
                    continue;
                }
                String upper = value.toUpperCase(Locale.US);
                if ("OUID".equals(upper) || "OAID".equals(upper)) {
                    kind = "OUID";
                    break;
                }
                if ("GUID".equals(upper) || "VAID".equals(upper)) {
                    kind = "GUID";
                    break;
                }
                if ("DUID".equals(upper) || "AAID".equals(upper)) {
                    kind = "DUID";
                    break;
                }
            }
            data.setDataPosition(start);
            return kind;
        } catch (Throwable e) {
            try {
                data.setDataPosition(start);
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /** The header fields a Parcel.writeInterfaceToken call puts before the interface name. */
    private static void skipTokenHeader(Parcel data) {
        data.readInt();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            data.readInt();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            data.readInt();
        }
    }

    /** Positions the parcel on the first argument, past the header fields and the interface name. */
    private static void skipInterfaceToken(Parcel data) {
        skipTokenHeader(data);
        data.readString();
    }

    private static String derived(String label, String fallback) {
        try {
            String value = DeviceIdentifiers.hex(label, 32);
            return TextUtils.isEmpty(value) ? hashOf(fallback, label) : value;
        } catch (Throwable e) {
            return hashOf(fallback, label);
        }
    }

    private static String hashOf(String seed, String label) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest((seed + "|" + label).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Throwable e) {
            return seed;
        }
    }

    private static String packageName() {
        try {
            return BActivityThread.getAppPackageName();
        } catch (Throwable e) {
            return "";
        }
    }

    private static boolean isEnabled() {
        try {
            if (!BlackBoxCore.get().isBlackProcess()) {
                return false;
            }
            return !TextUtils.isEmpty(BActivityThread.getAppPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    private static Handler handler() {
        Handler handler = sHandler;
        if (handler == null) {
            synchronized (OaidSpoof.class) {
                handler = sHandler;
                if (handler == null) {
                    HandlerThread thread = new HandlerThread("oaid-spoof");
                    thread.start();
                    handler = new Handler(thread.getLooper());
                    sHandler = handler;
                }
            }
        }
        return handler;
    }
}

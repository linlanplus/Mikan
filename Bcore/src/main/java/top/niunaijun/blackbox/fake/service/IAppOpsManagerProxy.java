package top.niunaijun.blackbox.fake.service;

import android.app.AppOpsManager;
import android.annotation.TargetApi;
import android.content.AttributionSource;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.app.BRAppOpsManager;
import black.android.os.BRServiceManager;
import black.com.android.internal.app.BRIAppOpsServiceStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;


public class IAppOpsManagerProxy extends BinderInvocationStub {
    public IAppOpsManagerProxy() {
        super(BRServiceManager.get().getService(Context.APP_OPS_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder call = BRServiceManager.get().getService(Context.APP_OPS_SERVICE);
        return BRIAppOpsServiceStub.get().asInterface(call);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        if (BRAppOpsManager.get(null)._check_mService() != null) {
            AppOpsManager appOpsManager = (AppOpsManager) BlackBoxCore.getContext().getSystemService(Context.APP_OPS_SERVICE);
            try {
                BRAppOpsManager.get(appOpsManager)._set_mService(getProxyInvocation());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        replaceSystemService(Context.APP_OPS_SERVICE);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        Class<?> result = method.getReturnType();
        if (name.startsWith("check") || name.startsWith("note") ||
                name.startsWith("start") || name.startsWith("finish")) {
            if (result == int.class) return AppOpsManager.MODE_ALLOWED;
            if (result == void.class) return null;
            // S changed note/start operations from int to SyncNotedAppOp, including proxy variants.
            if ("android.app.SyncNotedAppOp".equals(result.getName())) {
                try {
                    return NoteOperation.allowed(method, args);
                } catch (ReflectiveOperationException e) {
                    Slog.w(TAG, "Using system AppOps implementation for " + name, e);
                }
            }
        }

        try {
            MethodParameterUtils.replaceFirstAppPkg(args);
            MethodParameterUtils.replaceLastUid(args);
            return super.invoke(proxy, method, args);
        } catch (Exception e) {
            Slog.w(TAG, "AppOps call failed: " + name, e);
            if (result == int.class) return AppOpsManager.MODE_ALLOWED;
            if (result == void.class) return null;
            throw e;
        }
    }

    /** Only loaded on systems whose Binder interface returns SyncNotedAppOp. */
    @TargetApi(31)
    private static final class NoteOperation {
        static Object allowed(Method method, Object[] args) throws ReflectiveOperationException {
            Class<?>[] params = method.getParameterTypes();
            // startOperation has a leading binder token. noteOperation starts with the op code.
            int op = params.length > 0 && params[0] == IBinder.class ? 1 : 0;
            if (params.length <= op + 1 || params[op] != int.class) {
                throw new NoSuchMethodException(method.getName());
            }
            String pkg;
            String tag;
            if (params[op + 1] == int.class && params.length > op + 3 &&
                    params[op + 2] == String.class && params[op + 3] == String.class) {
                pkg = (String) args[op + 2];
                tag = (String) args[op + 3];
            } else {
                Object value = args[op + 1];
                AttributionSource source;
                if (value instanceof AttributionSource) {
                    source = (AttributionSource) value;
                } else if (value != null && "android.content.AttributionSourceState".equals(params[op + 1].getName())) {
                    source = AttributionSource.class.getConstructor(params[op + 1]).newInstance(value);
                } else {
                    throw new NoSuchMethodException(method.getName());
                }
                AttributionSource proxied = source.getNext();
                if (proxied != null) source = proxied;
                pkg = source.getPackageName();
                tag = source.getAttributionTag();
            }
            // The public two-argument constructor defaults to MODE_IGNORED on S and later.
            return method.getReturnType().getConstructor(int.class, int.class, String.class, String.class)
                    .newInstance(AppOpsManager.MODE_ALLOWED, args[op], tag,
                            pkg != null ? pkg : BlackBoxCore.getHostPkg());
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}

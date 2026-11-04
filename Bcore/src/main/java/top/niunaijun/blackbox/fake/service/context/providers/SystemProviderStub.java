package top.niunaijun.blackbox.fake.service.context.providers;

import android.os.Bundle;
import android.os.IInterface;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import black.android.content.BRAttributionSource;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.device.DeviceSpoofManager;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.utils.compat.ContextCompat;


public class SystemProviderStub extends ClassInvocationStub implements BContentProvider {
    private IInterface mBase;
    private String mAppPkg;

    @Override
    public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
        if (contentProviderProxy != null && Proxy.isProxyClass(contentProviderProxy.getClass())
                && Proxy.getInvocationHandler(contentProviderProxy) instanceof SystemProviderStub) {
            return contentProviderProxy;
        }
        mBase = contentProviderProxy;
        mAppPkg = appPkg;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        boolean isCall = "call".equals(method.getName());
        if (args != null && args.length > 0) {
            Object caller = args[0];
            if (method.getParameterTypes()[0] == String.class) {
                // Only the first string identifies the caller in legacy call() signatures.
                if (isCall || (caller instanceof String && !isSystemProviderAuthority((String) caller))) {
                    args[0] = BlackBoxCore.getHostPkg();
                }
            } else if (caller != null && caller.getClass() == BRAttributionSource.getRealClass()) {
                int uid = BActivityThread.getUid();
                String pkg = mAppPkg != null ? mAppPkg : BlackBoxCore.getHostPkg();
                ContextCompat.fixAttributionSourceState(caller, pkg, uid > 0 ? uid : BlackBoxCore.getHostUid());
            }
        }
        if (isCall) {
            Bundle spoofed = spoofedSettingResult(args);
            if (spoofed != null) {
                return spoofed;
            }
        }
        try {
            return method.invoke(mBase, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    // Settings.X.getString reaches the provider as call(..., "GET_<table>", key, extras).
    private static Bundle spoofedSettingResult(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (!(args[i] instanceof String) || !(args[i + 1] instanceof String)) {
                continue;
            }
            String callMethod = (String) args[i];
            if (!callMethod.startsWith("GET_")) {
                continue;
            }
            String value = DeviceSpoofManager.getSettingOverride(callMethod.substring(4), (String) args[i + 1]);
            if (value == null) {
                return null;
            }
            Bundle result = new Bundle();
            result.putString("value", value);
            return result;
        }
        return null;
    }

    private boolean isSystemProviderAuthority(String authority) {
        if (authority == null) return false;
        
        return authority.equals("settings") || 
               authority.equals("media") || 
               authority.equals("downloads") || 
               authority.equals("contacts") || 
               authority.equals("call_log") || 
               authority.equals("telephony") || 
               authority.equals("calendar") || 
               authority.equals("browser") || 
               authority.equals("user_dictionary") || 
               authority.equals("applications") ||
               authority.startsWith("com.android.") ||
               authority.startsWith("android.");
    }
}

package top.niunaijun.blackbox.fake.service.libcore;

import android.os.Process;
import android.system.ErrnoException;
import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.InetSocketAddress;

import java.lang.reflect.Method;

import black.libcore.io.BRLibcore;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.fake.network.ProxyConfig;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Reflector;


public class OsStub extends ClassInvocationStub {
    public static final String TAG = "OsStub";
    private Object mBase;

    public OsStub() {
        mBase = BRLibcore.get().os();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRLibcore.get()._set_os(proxyInvocation);
    }

    @Override
    protected void onBindMethod() {
    }

    @Override
    public boolean isBadEnv() {
        return BRLibcore.get().os() != getProxyInvocation();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (ProxyConfig.routesTcp() && "connect".equals(method.getName()) && args != null
                && args.length >= 2 && args[0] instanceof FileDescriptor) {
            InetSocketAddress target = null;
            if (args.length == 3 && args[1] instanceof InetAddress && args[2] instanceof Integer) {
                target = new InetSocketAddress((InetAddress) args[1], (Integer) args[2]);
            } else if (args[1] instanceof InetSocketAddress) {
                target = (InetSocketAddress) args[1];
            }
            if (target != null && !target.isUnresolved()) {
                InetAddress ip = target.getAddress();
                int scope = ip instanceof Inet6Address ? ((Inet6Address) ip).getScopeId() : 0;
                int result = NativeCore.tryProxyConnect((FileDescriptor) args[0], ip.getAddress(), target.getPort(), scope);
                if (result < 0) throw new ErrnoException("connect", -result);
                if (result > 0) return null;
            }
        }
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null)
                    continue;
                if (args[i] instanceof String && ((String) args[i]).startsWith("/")) {
                    String orig = (String) args[i];
                    args[i] = IOCore.get().redirectPath(orig);



                }
            }
        }
        return super.invoke(proxy, method, args);
    }

    @ProxyMethod("getuid")
    public static class getuid extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int callUid = (int) method.invoke(who, args);
            return getFakeUid(callUid);
        }
    }

    @ProxyMethod("stat")
    public static class stat extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object invoke = null;
            try {
                invoke = method.invoke(who, args);
            } catch (Throwable e) {
                throw e.getCause();
            }
            Reflector.with(invoke).field("st_uid").set(getFakeUid(-1));
            return invoke;
        }
    }

    private static int getFakeUid(int callUid) {
        if (callUid > 0 && callUid <= Process.FIRST_APPLICATION_UID)
            return callUid;

        AppConfig appConfig = BlackBoxCore.getAppConfig();
        if (appConfig != null && appConfig.buid > 0) {
            return appConfig.buid;
        }

        if (BActivityThread.isThreadInit() && BActivityThread.currentActivityThread().isInit()) {
            return BActivityThread.getBAppId();
        } else {
            return BlackBoxCore.getHostUid();
        }
    }
}

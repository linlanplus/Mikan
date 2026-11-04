package top.niunaijun.blackbox.fake.service.context;

import java.lang.reflect.Method;
import android.net.Uri;
import top.niunaijun.blackbox.media.BlackBoxMediaContract;

import black.android.content.BRIContentServiceStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class ContentServiceStub extends BinderInvocationStub {

    public ContentServiceStub() {
        super(BRServiceManager.get().getService("content"));
    }

    @Override
    protected Object getWho() {
        return BRIContentServiceStub.get().asInterface(BRServiceManager.get().getService("content"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("content");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("registerContentObserver")
    public static class RegisterContentObserver extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0 && args[0] instanceof Uri) {
                Uri uri = (Uri) args[0];
                if (BlackBoxMediaContract.PUBLIC_AUTHORITY.equals(uri.getAuthority())) {
                    args[0] = uri.buildUpon().authority(BlackBoxMediaContract.getPrivateAuthority()).build();
                    return method.invoke(who, args);
                }
                if (BlackBoxMediaContract.getPrivateAuthority().equals(uri.getAuthority())) return method.invoke(who, args);
            }
            return 0;
        }
    }

    @ProxyMethod("notifyChange")
    public static class NotifyChange extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
}

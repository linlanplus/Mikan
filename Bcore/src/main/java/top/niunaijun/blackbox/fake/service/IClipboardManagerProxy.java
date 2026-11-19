package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.Build;

import java.lang.reflect.Method;

import black.android.content.BRClipboardManager;
import black.android.content.BRClipboardManagerOreo;
import black.android.content.BRIClipboardStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;

/** System clipboard calls must carry the identity of the real Binder caller. */
public class IClipboardManagerProxy extends BinderInvocationStub {
    public IClipboardManagerProxy() {
        super(BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIClipboardStub.get().asInterface(
                BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE));
    }

    @Override
    protected void inject(Object base, Object proxy) {
        replaceSystemService(Context.CLIPBOARD_SERVICE);
        // Also update a ClipboardManager created before the service-cache hook.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Object manager = BlackBoxCore.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                BRClipboardManagerOreo.get(manager)._set_mService((android.os.IInterface) proxy);
            }
        } else {
            BRClipboardManager.get()._set_sService((android.os.IInterface) proxy);
        }
    }

    @Override
    public boolean isBadEnv() {
        return BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE) != this;
    }

    @ProxyMethods({"setPrimaryClip", "clearPrimaryClip", "getPrimaryClip",
            "getPrimaryClipDescription", "hasPrimaryClip", "hasClipboardText",
            "addPrimaryClipChangedListener", "removePrimaryClipChangedListener"})
    public static class ClipboardIdentity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object[] actual = args.clone();
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (types[i] != String.class) continue;
                // These methods put callingPackage before attributionTag/userId.
                actual[i] = BlackBoxCore.getHostPkg();
                int next = i + 1;
                if (next < types.length && types[next] == String.class) {
                    actual[next++] = null; // A guest attribution tag is not declared by the host.
                }
                if (next < types.length && types[next] == int.class) {
                    actual[next] = BlackBoxCore.getHostUserId();
                }
                break;
            }
            return method.invoke(who, actual);
        }
    }
}

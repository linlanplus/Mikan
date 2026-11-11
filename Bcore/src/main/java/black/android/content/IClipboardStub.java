package black.android.content;

import android.os.IBinder;
import android.os.IInterface;

import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BStaticMethod;

@BClassName("android.content.IClipboard$Stub")
public interface IClipboardStub {
    @BStaticMethod
    IInterface asInterface(IBinder binder);
}

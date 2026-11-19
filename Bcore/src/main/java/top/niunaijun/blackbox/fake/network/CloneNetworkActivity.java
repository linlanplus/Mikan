package top.niunaijun.blackbox.fake.network;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.*;
import top.niunaijun.blackbox.BlackBoxCore;

/** Launcher and shortcut entry for an assigned clone. */
public final class CloneNetworkActivity extends Activity {
    private static final int PERMIT_INSTALL = 1, INSTALL = 2, CONSENT = 3;
    public static boolean openIfSelected(Context ctx, String pkg, int user) {
        try {
            if (ProxyConfig.selectedId(user, pkg).isEmpty())
                return false;
        } catch (Exception ignored) { /* A damaged catalog must fail closed. */
        }
        ctx.startActivity(new Intent(ctx, CloneNetworkActivity.class)
                .putExtra("pkg", pkg)
                .putExtra("user", user)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state == null)
            prepare();
    }
    private String guardPackage() {
        return getPackageName() + ".networkguard";
    }
    private void prepare() {
        try {
            if (getPackageManager().getPackageInfo(guardPackage(), 0).versionCode < 1) {
                install();
                return;
            }
            if (getPackageManager().checkSignatures(getPackageName(), guardPackage())
                != PackageManager.SIGNATURE_MATCH) {
                stop("网络组件签名不匹配，已阻断联网");
                return;
            }
            if (CloneVpnService.network(this) != null)
                connect();
            else
                startActivityForResult(
                    new Intent().setClassName(guardPackage(), "uk.lqstudio.guard.ConsentActivity"), CONSENT);
        } catch (PackageManager.NameNotFoundException missing) {
            new AlertDialog.Builder(this)
                .setTitle("安装小橘网络组件")
                .setMessage("首次使用分身代理需要安装内置网络组件并授权 VPN。组件让系统阻止分身绕过节点。")
                .setPositiveButton("安装", (d, w) -> install())
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
        }
    }
    private void install() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                           Uri.parse("package:" + getPackageName())),
                    PERMIT_INSTALL);
                return;
            }
            File dir = new File(getCacheDir(), "network-guard");
            if (!dir.isDirectory() && !dir.mkdirs())
                throw new IOException();
            File apk = new File(dir, "mikan-network-guard.apk");
            try (InputStream in = getAssets().open(apk.getName());
                OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            startActivityForResult(new Intent(Intent.ACTION_INSTALL_PACKAGE)
                                       .setData(uri)
                                       .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                                       .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                INSTALL);
        } catch (Exception e) {
            stop("网络组件安装失败，已阻断联网");
        }
    }
    @Override
    protected void onActivityResult(int req, int result, Intent intent) {
        super.onActivityResult(req, result, intent);
        if (req == PERMIT_INSTALL && Build.VERSION.SDK_INT >= 26
            && getPackageManager().canRequestPackageInstalls())
            install();
        else if (req == INSTALL && result == RESULT_OK)
            prepare();
        else if (req == CONSENT && result == RESULT_OK)
            connect();
        else
            stop("分身代理未就绪，已阻断联网");
    }
    private void stop(String reason) {
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
        finish();
    }
    private void connect() {
        String pkg = getIntent().getStringExtra("pkg");
        int user = getIntent().getIntExtra("user", -1);
        if (pkg == null || user < 0) {
            finish();
            return;
        }
        new Thread(() -> {
            try {
                long until = SystemClock.elapsedRealtime() + 12000;
                while (BlackBoxCore.getBActivityManager().getService().cloneNetwork() == null) {
                    if (SystemClock.elapsedRealtime() >= until)
                        throw new IOException();
                    SystemClock.sleep(100);
                }
                String server = ProxyConfig.unpinnedServer(user, pkg);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed())
                        return;
                    if (server == null) {
                        launchGuest(pkg, user, null);
                        return;
                    }
                    new AlertDialog.Builder(this)
                        .setTitle("解析节点服务器地址")
                        .setMessage("需要通过当前设备网络解析节点服务器 " + server
                            + "，才能首次连接。分身访问的其他域名只通过所选节点查询。")
                        .setPositiveButton("解析并连接", (dialog, which) -> launchGuest(pkg, user, server))
                        .setNegativeButton("保持断网", (dialog, which) -> launchGuest(pkg, user, null))
                        .setOnCancelListener(dialog -> finish())
                        .show();
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> stop("分身代理未就绪，已阻断联网"));
            }
        }, "prepare-clone-network").start();
    }

    private void launchGuest(String pkg, int user, String approvedHost) {
        new Thread(() -> {
            try {
                try {
                    ProxyConfig.pinForLaunch(user, pkg, approvedHost);
                } catch (Exception ignored) {
                    runOnUiThread(
                        () -> Toast.makeText(this, "节点地址不可用，已阻断联网", Toast.LENGTH_LONG).show());
                }
                Intent launch = BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(pkg, user);
                if (launch == null)
                    throw new IOException();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        BlackBoxCore.get().startActivity(launch, user);
                        finish();
                    }
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> stop("分身代理未就绪，已阻断联网"));
            }
        }, "launch-routed-clone").start();
    }
}

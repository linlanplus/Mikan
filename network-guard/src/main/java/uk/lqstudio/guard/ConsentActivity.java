package uk.lqstudio.guard;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;

public final class ConsentActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent ask = VpnService.prepare(this);
        if (ask == null)
            ready();
        else if (state == null)
            startActivityForResult(ask, 1);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK)
            ready();
        else
            finish();
    }

    private void ready() {
        Intent service = new Intent(this, GuardService.class);
        if (Build.VERSION.SDK_INT >= 26)
            startForegroundService(service);
        else
            startService(service);
        setResult(RESULT_OK);
        finish();
    }
}

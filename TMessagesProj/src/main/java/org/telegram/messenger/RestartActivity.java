package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Runs in the ":restart" process; see {@link ProcessRestarter}. */
public class RestartActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int mainPid = getIntent().getIntExtra(ProcessRestarter.EXTRA_MAIN_PID, -1);
        if (mainPid > 0 && mainPid != android.os.Process.myPid()) {
            android.os.Process.killProcess(mainPid);
        }
        final Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(launch);
        }
        finish();
        Runtime.getRuntime().exit(0);
    }
}

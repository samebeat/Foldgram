package org.telegram.messenger;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Restarts the whole app process, the way a force close does.
 *
 * Needed because the foldable render stall is process-wide: once it happens, even freshly
 * recreated activities in the same process draw no frames, so only a new process recovers.
 * A plain "schedule relaunch + exit" doesn't work on Android 14+ (the relaunch is a blocked
 * background activity start), so a visible activity hands over to RestartActivity, which runs
 * in its own ":restart" process, kills this one and launches the app again from the foreground.
 */
public final class ProcessRestarter {

    static final String EXTRA_MAIN_PID = "fold7.main_pid";
    private static final String RESTART_PROCESS_SUFFIX = ":restart";

    private ProcessRestarter() {
    }

    /**
     * @param foregroundActivity a currently visible activity, so the app can be reopened;
     *                           null when the app is in the background - then the process just
     *                           exits and the START_STICKY NotificationsService brings it back.
     */
    public static void restart(Activity foregroundActivity, String reason) {
        Log.e("fold7diag", "restarting app process: " + reason);
        if (foregroundActivity != null && !foregroundActivity.isFinishing()) {
            try {
                Intent intent = new Intent(foregroundActivity, RestartActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra(EXTRA_MAIN_PID, android.os.Process.myPid());
                foregroundActivity.startActivity(intent);
                // RestartActivity kills this process; exit anyway if that doesn't happen.
                AndroidUtilities.runOnUIThread(() -> Runtime.getRuntime().exit(0), 3000);
                return;
            } catch (Throwable e) {
                Log.e("fold7diag", "RestartActivity failed", e);
            }
        }
        Runtime.getRuntime().exit(0);
    }

    public static boolean isRestartProcess(Context context) {
        String name = null;
        if (Build.VERSION.SDK_INT >= 28) {
            name = Application.getProcessName();
        } else {
            try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
                byte[] buffer = new byte[256];
                int length = in.read(buffer);
                int end = 0;
                while (end < length && buffer[end] != 0) {
                    end++;
                }
                name = new String(buffer, 0, end, StandardCharsets.US_ASCII);
            } catch (Throwable ignore) {
            }
        }
        return name != null && name.endsWith(RESTART_PROCESS_SUFFIX);
    }
}

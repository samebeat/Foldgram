package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.LaunchActivity;

import java.util.Locale;

/**
 * Foldgram gets no FCM push (Telegram's Firebase project only serves Telegram's own signature),
 * so messages arrive through NotificationsService. Android stops background services about a
 * minute after the app leaves the foreground ("Stopping service due to app idle") unless the app
 * is exempt from battery optimization, which silently breaks notifications. So after login, ask
 * the user for that exemption, and restart the service whenever the app comes to the foreground.
 */
public final class BackgroundConnectionPrompt {

    private static final String PREFS = "foldgram_battery";
    private static final String KEY_LAST_ASKED = "lastAsked";
    private static final long ASK_AGAIN_AFTER = 7L * 24 * 60 * 60 * 1000;

    private static boolean askedThisSession;

    private BackgroundConnectionPrompt() {
    }

    public static void onResume() {
        ApplicationLoader.startPushService();
        AndroidUtilities.runOnUIThread(BackgroundConnectionPrompt::maybeAsk, 1500);
    }

    private static void maybeAsk() {
        if (Build.VERSION.SDK_INT < 23 || askedThisSession) {
            return;
        }
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || activity.isFinishing() || !UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
            return;
        }
        if (!MessagesController.getGlobalNotificationsSettings().getBoolean("pushService", true)) {
            return;
        }
        final Context context = ApplicationLoader.applicationContext;
        final PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(context.getPackageName())) {
            return;
        }
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        if (Math.abs(now - prefs.getLong(KEY_LAST_ASKED, 0)) < ASK_AGAIN_AFTER) {
            return;
        }
        prefs.edit().putLong(KEY_LAST_ASKED, now).apply();
        askedThisSession = true;

        final boolean german = "de".equals(Locale.getDefault().getLanguage());
        final String title = german ? "Benachrichtigungen im Hintergrund" : "Notifications in the background";
        final String message = german
                ? "Foldgram kann keine Google-Push-Benachrichtigungen nutzen, die funktionieren nur für die offizielle Telegram-App. "
                  + "Stattdessen hält Foldgram im Hintergrund eine eigene Verbindung zu Telegram offen.\n\n"
                  + "Android beendet diese Verbindung etwa eine Minute nach dem Verlassen der App, wenn Foldgram nicht im Hintergrund laufen darf. "
                  + "Erlaube es bitte auf dem nächsten Bildschirm, sonst kommen keine Benachrichtigungen an."
                : "Foldgram can't use Google push notifications, they only work for Telegram's own app. "
                  + "Instead, Foldgram keeps its own connection to Telegram open in the background.\n\n"
                  + "Android closes that connection about a minute after you leave the app unless Foldgram may run in the background. "
                  + "Please allow it on the next screen, otherwise notifications won't arrive.";
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(german ? "Weiter" : "Continue", (dialog, which) -> openExemptionRequest(activity))
                .setNegativeButton(german ? "Später" : "Not now", null)
                .show();
    }

    @SuppressLint("BatteryLife")
    private static void openExemptionRequest(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignore) {
            }
        }
    }
}

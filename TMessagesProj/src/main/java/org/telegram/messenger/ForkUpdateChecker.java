package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Private-fork update notifier.
 *
 * The official in-app updater is disabled in this build (BuildVars.CHECK_UPDATES == false)
 * because APKs from Telegram's servers are signed with their certificate and can't be
 * installed over this privately signed build. Instead, this checker looks at the latest
 * commit on DrKLO/Telegram master (commit messages are "update to <version> (<build>)")
 * and posts a local notification when a newer upstream build than the one this APK was
 * built from is available, so the fork can be rebuilt on the PC.
 */
public class ForkUpdateChecker {

    private static final String PREFS = "forkupdate";
    private static final String KEY_LAST_CHECK = "lastCheck";
    private static final String KEY_NOTIFIED_BUILD = "notifiedBuild";
    private static final long CHECK_INTERVAL = 20 * 60 * 60 * 1000L; // 20h
    private static final String API_URL = "https://api.github.com/repos/DrKLO/Telegram/commits/master";
    private static final String COMMITS_URL = "https://github.com/DrKLO/Telegram/commits/master";
    private static final String CHANNEL_ID = "fork_updates";
    private static final int NOTIFICATION_ID = 78451;

    private static final Pattern VERSION_PATTERN = Pattern.compile("update to ([0-9.]+) \\((\\d+)\\)");

    private static volatile boolean checking;

    public static void check(Context context) {
        if (checking || context == null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        final SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final long now = System.currentTimeMillis();
        final long lastCheck = prefs.getLong(KEY_LAST_CHECK, 0);
        if (Math.abs(now - lastCheck) < CHECK_INTERVAL) {
            return;
        }
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(API_URL).openConnection();
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "telegram-private-fork-update-check");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                if (connection.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null && sb.length() < 262144) {
                            sb.append(line);
                        }
                    }
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                    JSONObject commit = new JSONObject(sb.toString()).getJSONObject("commit");
                    String message = commit.getString("message");
                    Matcher matcher = VERSION_PATTERN.matcher(message);
                    if (matcher.find()) {
                        String version = matcher.group(1);
                        int build = Integer.parseInt(matcher.group(2));
                        if (build > BuildConfig.UPSTREAM_BUILD_CODE && build != prefs.getInt(KEY_NOTIFIED_BUILD, 0)) {
                            prefs.edit().putInt(KEY_NOTIFIED_BUILD, build).apply();
                            AndroidUtilities.runOnUIThread(() -> showNotification(appContext, version, build));
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                checking = false;
            }
        });
    }

    @SuppressLint("MissingPermission")
    private static void showNotification(Context context, String version, int build) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Fork-Updates", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Benachrichtigt, wenn eine neue Telegram-Version auf GitHub verfügbar ist");
                manager.createNotificationChannel(channel);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(COMMITS_URL));
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle("Neue Telegram-Version verfügbar")
                    .setContentText("Upstream ist jetzt bei " + version + " (" + build + ") — Fork am PC neu bauen.")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(
                            "Telegram " + version + " (Build " + build + ") ist auf GitHub verfügbar. " +
                            "Diese private Version wurde aus Build " + BuildConfig.UPSTREAM_BUILD_CODE + " gebaut. " +
                            "Zum Aktualisieren den Fork am PC neu bauen und installieren."))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            manager.notify(NOTIFICATION_ID, notification);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}

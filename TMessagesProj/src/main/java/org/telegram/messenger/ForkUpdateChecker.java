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

/**
 * Foldgram update notifier.
 *
 * The official in-app updater is disabled (BuildVars.CHECK_UPDATES == false) because APKs
 * from Telegram's servers are signed with Telegram's certificate and can't be installed
 * over Foldgram. Instead, at most once per CHECK_INTERVAL this asks the GitHub API for the
 * latest Foldgram release (tag foldgram-&lt;upstream build&gt;-&lt;revision&gt;) and posts a
 * local notification linking to the release page when it is newer than this build.
 * The request carries no user data.
 */
public class ForkUpdateChecker {

    private static final String PREFS = "forkupdate";
    private static final String KEY_LAST_CHECK = "lastCheck";
    private static final String KEY_NOTIFIED_TAG = "notifiedTag";
    private static final long CHECK_INTERVAL = 20 * 60 * 60 * 1000L; // 20h
    private static final String CHANNEL_ID = "fork_updates";
    private static final int NOTIFICATION_ID = 78451;

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
        final String repo = BuildConfig.FOLDGRAM_GITHUB_REPO;
        Utilities.globalQueue.postRunnable(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "Foldgram-update-check");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                final int code = connection.getResponseCode();
                if (code == 200 || code == 404) {
                    // 404 = no release published yet; don't retry before the next interval either
                    prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
                }
                if (code == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null && sb.length() < 262144) {
                            sb.append(line);
                        }
                    }
                    final String tag = new JSONObject(sb.toString()).optString("tag_name", null);
                    final ForkReleaseVersion latest = ForkReleaseVersion.parse(tag);
                    if (latest != null
                            && latest.isNewerThan(BuildConfig.UPSTREAM_BUILD_CODE, BuildConfig.FOLDGRAM_REVISION)
                            && !latest.toTag().equals(prefs.getString(KEY_NOTIFIED_TAG, null))) {
                        prefs.edit().putString(KEY_NOTIFIED_TAG, latest.toTag()).apply();
                        // Built from the known repo and the validated tag only - never a URL from the response.
                        final String releaseUrl = "https://github.com/" + repo + "/releases/tag/" + latest.toTag();
                        AndroidUtilities.runOnUIThread(() -> showNotification(appContext, latest, releaseUrl));
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
    private static void showNotification(Context context, ForkReleaseVersion latest, String releaseUrl) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Foldgram updates", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Tells you when a new Foldgram release is available on GitHub");
                manager.createNotificationChannel(channel);
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl));
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.notification)
                    .setContentTitle("Foldgram update available")
                    .setContentText("Release " + latest.toTag() + " is available on GitHub. Tap to download.")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            manager.notify(NOTIFICATION_ID, notification);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}

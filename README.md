# Telegram for Galaxy Fold (unofficial fork)

An **unofficial** fork of [Telegram for Android](https://github.com/DrKLO/Telegram) that fixes the long-standing
freeze on Samsung Galaxy Fold devices: after rotating, folding/unfolding, or reopening the app from a notification,
Telegram would sometimes end up as a blank, unresponsive screen that only a force close could fix.

This is not affiliated with or endorsed by Telegram. All changes live on the `fold7` branch as a few small commits on
top of the official source, so they are easy to review and to rebase onto new upstream releases.

### What was wrong

Diagnosed live via ADB on a Galaxy Z Fold 7 while frozen: the app was running, the view hierarchy was intact and there
was no ANR, yet not a single frame was rendered and input was ignored. Forcing a relaunch made it die with an
`OutOfMemoryError` — the 512 MB large heap was full. Telegram only implements `onLowMemory()` (which modern Android rarely
delivers in time) and ignores `onTrimMemory()`, so caches grow until the GC thrashes and the UI starves. Folding,
rotating or opening a notification merely makes it visible, because those rebuild the UI and need memory.

### Fixes in this fork

- **Memory pressure handling:** `onTrimMemory()` clears in-memory caches; a 30 s heap watchdog purges caches above 80 %
  heap usage and cleanly restarts the app if usage stays above 92 % (logcat tag `fold7diag` shows the heap history).
- **Render watchdog:** after resume, window focus and fold transitions the app verifies a frame is actually drawn within
  1.5 s, and recreates itself once instead of staying frozen.
- **No forced refresh rate on foldables:** `preferredRefreshRate` is no longer pinned on devices with a hinge sensor.
- **Fold/rotation layout:** `density` handled in `configChanges` (cover and main screen differ), a fragile static
  tablet-mode latch replaced, display metrics re-synced on resume and after configuration-change bursts.
- **Self-built APK essentials:** background connection on by default (FCM doesn't work for non-official signatures),
  Firebase SMS verification disabled (it can't complete and stalled the login), in-app updater replaced by a daily
  check of the upstream repository.

### Building

1. Clone with submodules: `git clone --recurse-submodules -b fold7 <this repo>`
2. Install Android Studio (SDK 36, NDK 27.2.12479018, CMake 3.22.1) and a JDK 17–21.
3. **Get your own `api_id` / `api_hash`** at [my.telegram.org](https://my.telegram.org) — the sample credentials in
   the source are flood-limited and login will fail with `API_ID_PUBLISHED_FLOOD`. Put them, together with your own
   signing keystore passwords, into `~/.gradle/gradle.properties` (never into the repo):
   ```properties
   TELEGRAM_APP_ID=1234567
   TELEGRAM_APP_HASH=0123456789abcdef0123456789abcdef
   RELEASE_STORE_PASSWORD=...
   RELEASE_KEY_PASSWORD=...
   ```
   and place your keystore (alias `androidkey`) at `TMessagesProj/config/private-fold7.keystore`. Without it the
   build falls back to the public sample keystore — fine for testing, never for distribution.
4. Build: `gradlew :TMessagesProj_AppStandalone:assembleAfatStandalone` (arm64 only).
   On Windows, `update-fork.ps1` pulls the latest upstream, rebases the fork's commits, builds and optionally
   installs on a connected Fold (`-Install`).

The license is unchanged: GNU GPL v2 or later, see [LICENSE](LICENSE).

---

## Telegram messenger for Android

[Telegram](https://telegram.org) is a messaging app with a focus on speed and security. It’s superfast, simple and free.
This repo contains the official source code for [Telegram App for Android](https://play.google.com/store/apps/details?id=org.telegram.messenger).

## Creating your Telegram Application

We welcome all developers to use our API and source code to create applications on our platform.
There are several things we require from **all developers** for the moment.

1. [**Obtain your own api_id**](https://core.telegram.org/api/obtaining_api_id) for your application.
2. Please **do not** use the name Telegram for your app — or make sure your users understand that it is unofficial.
3. Kindly **do not** use our standard logo (white paper plane in a blue circle) as your app's logo.
3. Please study our [**security guidelines**](https://core.telegram.org/mtproto/security_guidelines) and take good care of your users' data and privacy.
4. Please remember to publish **your** code too in order to comply with the licences.

### API, Protocol documentation

Telegram API manuals: https://core.telegram.org/api

MTproto protocol manuals: https://core.telegram.org/mtproto

### Compilation Guide

**Note**: In order to support [reproducible builds](https://core.telegram.org/reproducible-builds), this repo contains dummy release.keystore,  google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio 2025.1.4, Android NDK 27.2.12479018 and Android SDK 36.

1. Clone the Telegram source code with its submodules:
   ```bash
   git clone --recursive --shallow-submodules https://github.com/DrKLO/Telegram.git Telegram
   ```
   In case you forgot the `--recursive` flag, change to the `Telegram` directory and run:
   ```bash
   git submodule init && git submodule update --init --recursive --depth=1
   ```
2. Copy your release.keystore into TMessagesProj/config
3. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your  release.keystore
4.  Go to https://console.firebase.google.com/, create two android apps with application IDs org.telegram.messenger and org.telegram.messenger.beta, turn on firebase messaging and download google-services.json, which should be copied to the same folder as TMessagesProj.
5. Open the project in the Studio (note that it should be opened, NOT imported).
6. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java – there’s a link for each of the variables showing where and which data to obtain.
7. You are ready to compile Telegram.

### Localization

We moved all translations to https://translations.telegram.org/en/android/. Please use it.

# Foldgram

[![Release](https://img.shields.io/github/v/release/samebeat/Foldgram)](https://github.com/samebeat/Foldgram/releases)
[![License: GPL v2](https://img.shields.io/badge/license-GPL--2.0-blue)](LICENSE)

**Foldgram is an unofficial Telegram client for Samsung Galaxy Fold devices.** It uses the Telegram API and is part of
the Telegram ecosystem, but it is not made, endorsed or supported by Telegram. It is the official
[Telegram for Android](https://github.com/DrKLO/Telegram) source code plus a handful of small fixes, kept as separate
commits on the `fold7` branch so they are easy to review and to rebase onto every new upstream release.

Foldgram adds no ads, no analytics, no tracking and no monetization of any kind. Telegram's own features, including
official sponsored messages in channels, are unchanged.

## The bug it fixes

On Galaxy Folds (seen from the Fold 3 to the Fold 7), Telegram sometimes ends up as a blank, unresponsive screen after
rotating, folding/unfolding, or reopening the app from a notification. There is no crash and no error; only a force
close helps.

Diagnosed live via ADB on a Galaxy Z Fold 7 while frozen: the process is running, the activity is resumed, the view
hierarchy is intact, and there is no ANR, yet not a single frame is rendered. Android itself names the reason:
`Canceling draw. cancelDueToPreDrawListener=true` and `mLastPerformTraversalsFailedReason=predraw_org.telegram.ui.ChatActivity$104`.

That listener is registered in `ChatActivity.fixLayout()`. On tablets, which includes an unfolded Fold, it returns
`false` on purpose to skip exactly one frame and then removes itself via `avatarContainer.getViewTreeObserver()`. If the
chat header was detached or replaced in the meantime, for example when opening a chat from a notification moves it
between the panes of the tablet layout, that call returns a different observer and the removal silently does nothing.
The listener stays on the window and cancels every frame from then on, until the process dies (force close).
`RecyclerItemsEnterAnimator` (the chat list's enter animation) has the same pattern.

Ruled out along the way: a per-app frame rate override, Telegram's forced `preferredRefreshRate`, and memory exhaustion
(a full heap seen once was a side effect of an early, looping version of the watchdog below).

## What Foldgram changes

- **The fix:** both pre-draw listeners remove themselves from the observer they were registered on and never cancel more
  than one frame, so a missed removal can no longer freeze the window.
- **Chats not opening in the right pane:** a second, separate bug. In release builds Telegram's `NotificationCenter`
  delivered events on the thread that posted them, so the notification thread could make chat views call
  `requestLayout()` off the main thread. The window then never laid itself out again: the app kept drawing, but a tapped
  chat stayed invisible (black right pane on an unfolded Fold) and taps on the chat list were ignored. Foldgram hands such
  events to the main thread, and a layout watchdog re-requests layout from the window root if a request stays pending.
- **Render watchdog (safety net):** after resume, window focus and fold transitions Foldgram checks that a frame is actually drawn
  within 3 s while the activity is resumed, its window is shown and the screen is on. If not, it restarts its own process
  and reopens itself, which is
  what a force close does, at most once every 3 minutes. It logs the window state when it fires
  (`adb logcat -s fold7diag`), in case another code path ever blocks drawing.
- **Memory pressure handling:** `onTrimMemory()` clears in-memory caches; a heap watchdog (every 30 s) purges caches above
  80 % heap usage and restarts the app if usage stays above 92 %.
- **No forced refresh rate on foldables:** `preferredRefreshRate` is no longer pinned on devices with a hinge sensor.
- **Fold/rotation layout:** `density` handled in `configChanges` (cover and main screen differ), a fragile static
  tablet-mode latch replaced, display metrics re-synced on resume and after bursts of configuration changes.
- **Runs next to official Telegram:** own package name (`io.github.samebeat.foldgram`), own contacts-sync account type,
  own name and icon.
- **Works without Telegram's signing certificate:** notifications through the background connection (Firebase push only
  works for Telegram's own signature), Firebase SMS verification disabled (it can't complete and stalled the login),
  Telegram's in-app updater replaced by a check for new Foldgram releases.

## Install

Download the APK from [Releases](https://github.com/samebeat/Foldgram/releases) (arm64, Android 5.0 or newer) and open
it. Foldgram installs next to official Telegram; you log in once with your phone number.

**Verify before installing** (optional, recommended): the SHA-256 of each APK is listed in its release, and every release
is signed with the same certificate:

```
SHA-256: fd41108487d88b9a47f5970718e35059028f6806c2c15efd72e1e74440183033
```

Check with `apksigner verify --print-certs Foldgram-*.apk` (Android SDK build-tools).

**Allow background activity, or notifications won't arrive.** Foldgram can't use Google push (see below), so it keeps
its own connection open, and Android 8+ stops it about a minute after you leave the app unless Foldgram is exempt from
battery optimization. After login Foldgram asks for this; you can also set it under Settings > Apps > Foldgram >
Battery > *Unrestricted* (Samsung) or *Battery optimization > Don't optimize* (other devices).

### Known limitations

- No Firebase push (Telegram's push only works for its own signed app): notifications arrive through a persistent
  background connection, which uses somewhat more battery and needs the battery exemption described above.
- Login codes sent by SMS aren't filled in automatically; type them in.
- Features that rely on Google services restricted to Telegram's own app (for example Google Maps tiles, passkeys) may
  not work.
- Updates don't come from the Play Store. Once a day Foldgram asks the GitHub API for the latest release (no user data
  is sent) and shows a notification when a newer one is available.

## Building it yourself

1. Clone with submodules: `git clone --recurse-submodules -b fold7 https://github.com/samebeat/Foldgram.git`
2. Install Android Studio (SDK 36, NDK 27.2.12479018, CMake 3.22.1) and a JDK 17 to 21.
3. **Get your own `api_id` / `api_hash`** at [my.telegram.org](https://my.telegram.org). Telegram's API terms require
   every app to use its own, and the sample credentials in the source are flood-limited (login fails with
   `API_ID_PUBLISHED_FLOOD`). Put them, together with your signing keystore passwords, into
   `~/.gradle/gradle.properties`, never into the repo:
   ```properties
   TELEGRAM_APP_ID=1234567
   TELEGRAM_APP_HASH=0123456789abcdef0123456789abcdef
   RELEASE_STORE_PASSWORD=...
   RELEASE_KEY_PASSWORD=...
   ```
   and place your keystore (alias `androidkey`) at `TMessagesProj/config/private-fold7.keystore`. Without it the build
   falls back to the public sample keystore: fine for testing, never for distribution. If you publish your own build,
   also change `FOLDGRAM_PACKAGE`, `FOLDGRAM_APP_NAME` and `FOLDGRAM_GITHUB_REPO` in `foldgram.properties`.
4. Build the task `:TMessagesProj_AppStandalone:assembleAfatStandalone` (arm64 only), e.g. `./gradlew` on Linux/macOS or
   `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain` on Windows.

### Maintaining the fork

- `update-fork.ps1` (Windows) fetches the latest upstream source, rebases the Foldgram commits onto it, updates
  submodules, builds, and optionally installs on a connected Fold (`-Install`) or publishes a GitHub release
  (`-Release`, needs the GitHub CLI). Release tags are `foldgram-<upstream build>-<revision>`; the revision lives in
  `foldgram.properties`.
- The *Upstream watch* workflow checks daily whether official Telegram has a newer build and opens an issue if so.

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

# Porting Berrak to current Firefox Focus (the Google Play line)

Berrak is built on the archived mozilla-mobile/firefox-android monorepo at
Focus 125. That base cannot reach Google Play:

| Play requirement (new apps) | Focus 125 | Current Focus (release 159) |
|---|---|---|
| targetSdk 36 since 2026-08-31 | 34 | 37 (`android-components/.config.yml`) |
| 16 KB page-aligned native libraries for targetSdk ≥ 35 | GeckoView 125 libs are 4 KB-aligned (`readelf -l libxul.so` → `0x1000`) and crash on 16 KB devices | fixed in Gecko 144 (bug 1959478) |

Raising `targetSdk` on the 125 tree does not help: GeckoView 125's native
code is the blocker. Berrak has to move to the code Mozilla maintains today,
which lives in mozilla-firefox/firefox under `mobile/android/focus-android`
(with android-components beside it in `mobile/android/android-components`).

## What has to move

17 commits on `httpx` (`git log fe8a71cd..httpx`), 183 files. By weight:

- **Rebrand strings** (about 60 % of the diff, `app/src/main/res/values-*`):
  "Firefox Focus"/"Firefox Klar" → "Berrak" across locales. Port by script,
  not by patch: the upstream string files have changed a lot since 125.
- **Branding art** (`app/src/klar/res`, `app/src/debug/res`): copy over.
- **httpx integration** (about 10 Kotlin files; see the table in
  README-HTTPX.md): HttpxExtension, the interceptor rewrite, the toolbar and
  URL-input display mapping, the proxy-mode setting. Port by hand; the
  Focus code around them has changed in 34 releases.
- **Privacy removals**: Glean upload, Socorro, Sentry, Nimbus endpoint, Play
  Core, the crash-report checkbox. Upstream still has `lib-crash-sentry`, so
  each removal is redone.
- **No Google Play Services**: same as here: exclude the
  `com.google.android.gms` group, set `security.webauth.webauthn=false`
  through the GeckoRuntime config file.
- **Build/release**: applicationId, signing, versionCode, the GitHub Actions
  workflow, fastlane metadata.
- **Bundled extension**: GeckoView now supports MV3 built-ins; the MV2
  manifest can stay until it stops working.

## How the new tree builds

Focus in mozilla-firefox is not a standalone Gradle project any more: its
`settings.gradle` reads the mach configuration (`gradle/mozconfig.gradle`).
The fastest route is a GeckoView **artifact build** (prebuilt Gecko binaries
from Mozilla's CI, Java/Kotlin built locally):

```
git clone --depth 1 -b release https://github.com/mozilla-firefox/firefox berrak-ff
cd berrak-ff
./mach --no-interactive bootstrap --application-choice "GeckoView/Firefox for Android Artifact Mode"
./mach build
cd mobile/android/focus-android && ./gradlew :app:assembleFocusDebug
```

F-Droid's main repository will later need a full Gecko build from source
(no prebuilt artifacts). The artifact build is enough for Play and
IzzyOnDroid.

## Plan

1. Clone the `release` branch, bootstrap, and build stock Focus in artifact
   mode. This proves the toolchain.
2. Start a `berrak` branch in that tree. Port the build identity first
   (applicationId, signing, versionCode), then the privacy removals and the
   gms exclusion, then branding, then the httpx integration.
3. Bring the unit tests (`org.mozilla.focus.httpx.*`) and run the on-device
   httpx and proxy-mode flows from README-HTTPX.md on a 16 KB-page emulator
   as well as the 4 KB one.
4. CI: the workflow has to bootstrap mach; cache `~/.mozbuild`.
5. Release as Berrak 1.0 (new versionCode base above the 0.x line so it
   upgrades in place; same signing key).
6. Rebases: follow `release` per Firefox version (every 4 weeks). Keep
   the Berrak changes as a short, ordered patch series so each rebase is
   mechanical.

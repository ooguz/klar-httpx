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
# Not --no-system-changes: that also skips the Android SDK. Without it the
# SDK lands in ~/.mozbuild/android-sdk-linux; no sudo was needed on Ubuntu.
./mach --no-interactive bootstrap --application-choice mobile_android_artifact_mode
cat > mozconfig <<'MOZ'
ac_add_options --enable-project=mobile/android
ac_add_options --enable-artifact-builds
ac_add_options --target=x86_64-linux-android   # one build per ABI
mk_add_options MOZ_OBJDIR=./objdir-x86_64
MOZ
./mach build                                     # about 10 minutes
systemd-run --user --scope -p MemoryMax=10G -p MemorySwapMax=2G \
  nice -n 10 ./mach gradle -p mobile/android/focus-android \
  --max-workers=3 -Dorg.gradle.parallel=false \
  "-Dorg.gradle.jvmargs=-Xmx5g -XX:MaxMetaspaceSize=1536m" \
  -Pkotlin.compiler.execution.strategy=in-process :app:assembleKlarDebug
```

The limits on the Gradle step matter: upstream's own settings (7 GB heap,
parallel, a worker per core, a separate Kotlin daemon) exhausted a 30 GB
laptop that had other work open. The cgroup cap makes a too-large build fail
on its own instead of taking the desktop with it.

Verified 2026-11-02 with stock Focus 159.0.1 (`klar` flavour, x86_64):
`targetSdkVersion 37`, `libxul.so`/`libmozglue.so` LOAD segments aligned to
`0x4000`, `zipalign -c -P 16` passes, and it renders a page on the
`Pixel_10` emulator (Android 17, `getconf PAGE_SIZE` = 16384), where Gecko
125 crashed. Give that emulator 5 GB (`-memory 5120`); at 3 GB Android's
low-memory killer takes the browser down within seconds. The stock APK
still carries `com.google.android.gms` (fido, common, internal), so the
exclusion described above is needed again.

F-Droid's main repository will later need a full Gecko build from source
(no prebuilt artifacts). The artifact build is enough for Play and
IzzyOnDroid.

## Where the port lives

`port/` in this repo holds the port as a patch series against Mozilla's tree:

- `port/UPSTREAM`: the mozilla-firefox/firefox commit and Firefox version the
  patches apply to (now `release` at 159.0.1, `ae0a6047`).
- `port/patches/`: five patches, in order:
  1. own application id (`dev.ooguz.berrak`) and release signing;
  2. nothing leaves the device, no proprietary libraries: no Socorro or
     Sentry, no daily usage ping, Nimbus without a server, no Google Play
     Services or Play Core, WebAuthn off, help/privacy/terms links to this
     repo's docs;
  3. name, art and wording: Berrak's art on upstream's resource names, and
     `tools/berrak/rebrand_strings.py`, which rewrites the English strings
     that claim Mozilla authorship, terms or data collection and deletes
     their translations;
  4. httpx:// through the bundled xmpp-httpx WebExtension, plus proxy mode;
  5. the bundled extension at 0.2.6.
- `port/build.sh`: fetches the pinned commit (shallow), applies the patches
  and builds in artifact mode with the memory limits above. It resets only
  a work tree it created itself (`port/work/` by default, git-ignored).

The applied patches reproduce the development tree exactly (same git tree
hash, `39705d02`).

## Rebasing onto a new Firefox release

In a full work tree (`port/build.sh :app:assembleKlarDebug` makes one, or a
clone with the `berrak` branch):

1. `git fetch --depth 1 origin <new release commit>` and
   `git rebase --onto <new> <old> berrak`.
2. Conflicts in `values-*/strings.xml` (patch 3): take upstream's files and
   rerun `python3 mobile/android/focus-android/tools/berrak/rebrand_strings.py`.
   It stops with an error when upstream renamed one of its keys.
3. Build, run `:app:testKlarDebugUnitTest --tests "org.mozilla.focus.httpx.*"
   --tests "org.mozilla.focus.utils.SupportUtilsTest"`, and repeat the device
   checks below.
4. `git format-patch --binary --no-signature -o port/patches <new>..berrak`
   (delete the old patches first) and update all of `port/UPSTREAM`.

## Status (2026-11-02)

Verified on the `Pixel_10` emulator (Android 17, 16 KB pages), klarDebug:
Berrak's onboarding, icon and wording; the demo gateway flow from
README-HTTPX.md (connection dialog, start page with its image, a relative
link, back, a POST form); a WebAuthn probe page sees no
`PublicKeyCredential` and the app stays up; the dex holds no
`com.google.android.gms`, `io.sentry` or `com.google.android.play` classes.

Found on the way: GeckoView 159 never navigates a frame inside an extension
tab (srcdoc, `blob:`, `data:` and `https:` frames all stay on `about:blank`).
The extension rendered pages through `srcdoc`, so every page stayed blank.
Fixed in xmpp-httpx 7d97822 (pages are written into the frame); the bundled
0.2.6 has the fix.

Still to do, in order:

1. A signed `klarRelease` build (R8) for each ABI, and a check that R8 needs
   no rules beyond the gms/checker-qual `-dontwarn`s.
2. Proxy mode on the device against an n146 exit.
3. CI: `port/build.sh` on GitHub Actions, caching `~/.mozbuild`; release
   APKs per ABI on tags. Play wants an App Bundle; with artifact builds that
   means one objdir per ABI merged into one bundle, which is not solved yet.
4. Store listing assets (screenshots from the new build).
5. Release as Berrak 1.0, signed with the same key: its versionCodes
   (`40DDDHHmm`, upstream's 2026 scheme) are above 0.x's, so it upgrades in
   place.

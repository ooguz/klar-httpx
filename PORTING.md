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
- `port/patches/`: six patches, in order:
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
  5. the bundled extension at 0.2.6;
  6. proxy mode never loads a page directly while the extension is still
     installing (see Status).
- `port/build.sh`: fetches the pinned commit (shallow), applies the patches
  and builds in artifact mode with the memory limits above. It resets only
  a work tree it created itself (`port/work/` by default, git-ignored).
  By default it builds every Play ABI at once (next section) and writes the
  signed per-ABI APKs and the App Bundle; `BERRAK_ABI=x86_64` builds one
  ABI, faster, for the emulator.

The applied patches reproduce the development tree exactly (same git tree
hash, `cafeeb22`).

On GitHub, `.github/workflows/berrak-port.yml` runs `port/build.sh`: on a
push that touches `port/` (outputs kept a week as workflow artifacts), on a
manual dispatch, and on a `v1.x` or later tag, which must be signed and gets
the per-ABI APKs and the AAB attached to its release. `v0.x` tags stay with
`berrak.yml` (the Focus 125 line). The job frees the runner's unused
toolchains, puts the work tree on the larger disk and caches `~/.mozbuild`
without the emulator; the checks after the build are `targetSdkVersion`,
16 KB alignment, a `libxul.so` in every APK and the Berrak signature.

## One build for every ABI (the App Bundle)

An artifact build normally holds the Gecko of its one `--target` ABI. Set
`MOZ_ANDROID_FAT_AAR_ARCHITECTURES` in the mozconfig and `./mach build`
also fetches GeckoView for each listed ABI from Mozilla's CI (the same
release build: all three came from 159.0.20261031152827) and merges their
native libraries into one "fat" GeckoView, which is what Mozilla's own
`build-fat-aar` task does:

```
ac_add_options --enable-project=mobile/android
ac_add_options --enable-artifact-builds
ac_add_options --target=aarch64-linux-android
mk_add_options MOZ_OBJDIR=./objdir-all
export MOZ_ANDROID_FAT_AAR_ARCHITECTURES=armeabi-v7a,arm64-v8a,x86_64
```

From that objdir, `:app:assembleKlarRelease` writes a working APK per ABI
(each with its own `libxul.so`) and `:app:bundleKlarRelease` the App Bundle
with all three. Run them as separate Gradle invocations: with a bundle task
in the same run, the APK comes out as one 600 MB universal APK. The AAB is
284 MB; bundletool puts the per-device download at about 105 MB (Play
allows 200 MB). `armeabi-v7a`'s `libxul.so` is 4 KB-aligned, which is
fine: Play's 16 KB rule is for 64-bit libraries.

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

## Status

2026-11-02, on the `Pixel_10` emulator (Android 17, 16 KB pages), klarDebug:
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

Release build (2026-11-20), `klarRelease` x86_64 with R8, signed with the
Berrak key (certificate SHA-256 `3b8a8920…f0f2`, the same as 0.x): R8 needs
no rules beyond the gms/checker-qual `-dontwarn`s; `targetSdkVersion 37`,
`zipalign -c -P 16` passes; the dex defines no `com.google.android.gms`,
`io.sentry` or `com.google.android.play` class (GeckoView's WebAuthn code
still names the FIDO types, but that code never runs with WebAuthn off).
On the `Pixel_10` emulator the release build passed onboarding and **proxy
mode** against an n146 exit (n146-proxy's e2e Prosody, `exit.localhost`):
with "Browse the web through the httpx exit" on, `https://example.com/`
shows the connection dialog, then loads through the exit; an IANA page
loads with its images and stylesheet, a link and back work; with the exit
stopped, the same load fails with `remote-server-timeout: Component
unavailable`, so nothing went direct.

Found there: a link from another app that cold-starts the browser can
arrive before the bundled extension has finished installing (it did on
the first start after an update). The interceptor then had nowhere to send
it and let it load **directly**, outside the exit. Patch 6 holds such a
load and loads it again once the extension is in place; it never falls
back to a direct load. After the fix, the same upgrade-then-link sequence
went through the exit. 0.x had the same code and has the same fix.

`port/build.sh` itself, run from scratch on 2026-11-21 (new work tree,
fetch, patches, bootstrap, the fat build and both Gradle runs): three
signed per-ABI APKs, each with its own `libxul.so`, and the AAB.

The App Bundle from the fat objdir, signed with the Berrak key
(`jarsigner -verify`), installed through bundletool as an upgrade over the
x86_64 build (same key, higher versionCode) and browsed through the exit.

Still known: with proxy mode on, search suggestions (off unless the user
turns them on when asked) are still fetched directly from the search
engine. Proxy mode covers page loads.

Pass `-PversionName` to release builds (build.sh does); without it the
versionName is `null`. A single-ABI objdir also writes split APKs for the
other ABIs, without `libxul.so`; they do not work.

Still to do, in order:

1. Store listing assets. Phone screenshots from the port build are in
   `fastlane/metadata/android/en-US/images/phoneScreenshots/` (1080×2160,
   Play's 2:1 limit: welcome, an httpx:// page, a web page through the
   exit, the proxy-mode setting); beside them `icon.png` (512×512) and
   `featureGraphic.png` (1024×500), rendered with Inkscape from
   `fastlane/art/*.svg` (text in Noto Sans). Done unless the screenshots
   are redone with a nicer httpx:// page than the demo fixture.
2. Play Console: when creating the app, choose to upload Berrak's own
   signing key (Play App Signing, "use your own key") so that Play's
   installs and the GitHub/IzzyOnDroid APKs share one certificate and can
   update each other.
3. Release as Berrak 1.0, signed with the same key: its versionCodes
   (`40DDDHHmm`, upstream's 2026 scheme) are above 0.x's, so it upgrades in
   place.

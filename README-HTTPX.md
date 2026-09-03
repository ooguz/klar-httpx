# Berrak — a Firefox Focus fork that speaks httpx:// (XEP-0332)

A fork of [mozilla-mobile/firefox-android](https://github.com/mozilla-mobile/firefox-android)
(125.0a1, the archived monorepo) whose Focus/Klar Android browser can navigate
`httpx://` URLs — HTTP tunneled over XMPP, as implemented by the
[xmpp-httpx](../n146) library. Distributed under the name **Berrak**
(applicationId `dev.ooguz.berrak`), with Mozilla branding removed — see
§Branding below.

## How it works

Gecko never learns the httpx scheme. Instead:

1. The xmpp-httpx project's WebExtension browser (`examples/webext`, built with an
   Android-specific MV2 manifest) is bundled as a **built-in GeckoView extension**
   at `focus-android/app/src/main/assets/extensions/httpx/`. Its page does
   everything: XMPP over WebSocket, XEP-0332 fetching, HTML/CSS sanitization,
   forms, caching, downloads.
2. `HttpxExtension` (`focus-android/app/src/main/java/org/mozilla/focus/httpx/`)
   installs it at engine creation (`Components.kt`) and records its
   `moz-extension://<uuid>/` base URL.
3. `AppContentInterceptor` rewrites any top-level `httpx://` or `ext+httpx://`
   navigation — typed, linked, or from a VIEW intent — to
   `moz-extension://<uuid>/browser.html?embedded=1#<httpx-url>`. Subframe
   requests are deliberately not rewritten (web content must not embed the
   privileged page). The `?embedded=1` flag makes the extension hide its own
   tab strip and URL bar, so the app's toolbar is the only chrome.
4. The toolbar shows the httpx URL, not the internal moz-extension URL
   (`DisplayToolbar.urlFormatter` in `BrowserToolbarIntegration`, plus the
   edit-mode seed in `UrlInputFragment`).

Typed input already worked: `URLStringUtils.isURLLike` accepts
`httpx://user@host/…` including the userinfo form.

## Fork changes (all under `focus-android/`)

| File | Change |
|---|---|
| `app/src/main/assets/extensions/httpx/` | the bundled extension (built page + Android MV2 manifest) |
| `app/src/main/java/org/mozilla/focus/httpx/HttpxExtension.kt` | new: install, URL rewrite, display mapping |
| `app/src/main/java/org/mozilla/focus/Components.kt` | install the built-in at engine creation |
| `app/src/main/java/org/mozilla/focus/engine/AppContentInterceptor.kt` | httpx → extension-page rewrite |
| `app/src/main/java/org/mozilla/focus/browser/integration/BrowserToolbarIntegration.kt` | display-URL reverse mapping |
| `app/src/main/java/org/mozilla/focus/fragment/UrlInputFragment.kt` | edit-mode seed reverse mapping |
| `app/src/klar/res/values/app.xml` | app name → "Berrak" |
| `app/build.gradle` | klar applicationId → `dev.ooguz.berrak`; conditional Berrak release signing; warnings-as-errors off |
| `tools/gradle/versionCode.gradle` | the yDDDHHmm versionCode scheme overflowed 32 bits in 2026; minutes dropped when out of range |
| `tools/update-httpx-extension.sh` (repo root) | rebuild + refresh the bundled extension from the xmpp-httpx repo |

One integration subtlety worth knowing: the rewrite must return
`InterceptionResponse.Url` with **only** `LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE`.
The default flags include `EXTERNAL`, which makes Gecko run the load with a null
triggering principal — and a null principal may not link to a `moz-extension://`
page ("Security Error: Content at moz-nullprincipal:… may not load or link to
moz-extension://…"). Verified on-device; the fix is in `HttpxExtension.intercept`.

The Android manifest of the bundled extension differs from the desktop one:
MV2 (GeckoView 125 built-ins are MV2-only), no background script (the XMPP
connection lives in the page; the desktop background script only did desktop
toolbar/omnibox glue), no `downloads` permission (GeckoView has no
`browser.downloads`; the extension's `<a download>` fallback is fully wired in
android-components), and the same CSP override that keeps `ws://` connections
from being upgraded to `wss://`.

## Building

```sh
cd focus-android
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 GLEAN_PYTHON=/usr/bin/python3 \
  ./gradlew :app:assembleKlarDebug
```

APKs land in `focus-android/app/build/outputs/apk/klar/debug/` (one per ABI).
The applicationId is `dev.ooguz.berrak.debug`, so it installs alongside both
stock Klar and a release Berrak.

### Release (signed) build

```sh
cd focus-android
BERRAK_KEYSTORE_PROPERTIES=~/Belgeler/bireysel/klar-httpx-signing/keystore.properties \
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 GLEAN_PYTHON=/usr/bin/python3 \
  ./gradlew -PversionName=0.1.0 :app:assembleKlarRelease
```

`BERRAK_KEYSTORE_PROPERTIES` points at a Java properties file with
`storeFile`/`storePassword`/`keyAlias`/`keyPassword`; without it the release
APKs are built unsigned, as upstream. Always pass `-PversionName` — without it
the APK silently keeps a debug-style versionName. ABI splits are on, so four
APKs are produced (arm64-v8a is the one most phones want); versionCodes are
generated from the build hour, so don't cut two releases within the same hour.

Two build-time network quirks of this archived tree:

- **nimbus-fml**: the Nimbus gradle plugin downloads the `nimbus-fml` CLI for
  version `125.20240317050356`, whose artifacts have been pruned everywhere.
  Pre-seed the plugin's cache with the binary from app-services **125.0**
  (same March 2024 code):
  `https://archive.mozilla.org/pub/app-services/releases/125.0/nimbus-fml.zip`
  → unzip `x86_64-unknown-linux-musl/release/nimbus-fml`, `chmod +x`, and place
  it where the failing build's "Checking fml binaries in …" log line points
  (the plugin skips the download when the binary is already there).
- **Glean**: set `GLEAN_PYTHON=/usr/bin/python3` or the plugin bootstraps its
  own Miniconda.

GeckoView `125.0.20240317231404` still downloads from maven.mozilla.org (2024
nightly — consider mirroring the AAR).

## Trying it against the xmpp-httpx demo gateway

```sh
# In the xmpp-httpx repo: Prosody + demo site (ws://localhost:15280)
npm run demo

# Emulator/device: map the device's localhost to the host
adb reverse tcp:15280 tcp:15280

# In the app: navigate httpx://web@httpx.localhost/ — the extension's
# settings dialog opens; enter:
#   service   ws://localhost:15280/xmpp-websocket
#   JID       alice@localhost
#   password  e2e-alice
```

`ws://` to **localhost** is exempt from Gecko's secure-context WebSocket rule;
other cleartext hosts (e.g. `10.0.2.2`) are not — use `adb reverse`, `wss://`,
or flip `network.websocket.allowInsecureFromHTTPS` via a GeckoView
`configFilePath` YAML if you must.

## Known limitations / notes

- **Extension install races a cold-start VIEW intent**: if an httpx URL arrives
  before the built-in finishes installing, the navigation falls through to the
  unknown-protocol error page; reload once the app is up.
- **Focus's erase does not wipe the XMPP credentials**: the extension stores
  them in `storage.local`, which is profile-backed and survives "erase"
  (Focus's erase only closes private tabs). Wiping means reinstalling or
  clearing app data — or a future settings hook.
- **Back navigation works**: in embedded mode the page *pushes* a session
  history entry per page-to-page navigation (upstream webext ≥ the 2026-08-28
  build), so the system back gesture and the toolbar's back walk httpx pages.
  On the first page the session history holds a single entry — the boot entry
  is upgraded in place — so back there still triggers Focus's erase-and-leave,
  as a normal page would.
- **Loading feedback**: the extension draws a thin byte-progress bar at the
  top of the page area while a body transfers over XMPP (extension ≥ 0.2.3) —
  deliberately outside its own (hidden) chrome, since Klar's toolbar has no
  window onto the page's transfers. Determinate when the response carries a
  usable Content-Length, a sliding shimmer otherwise.
- **Updating the bundled extension**: GeckoView's `ensureBuiltIn` is a no-op
  while the id+version match what is installed — every asset refresh must bump
  `version` in the bundled manifest.json or devices keep the old files.
- **"Block JavaScript" in Focus settings kills the extension page too** (the
  setting is engine-global).
- **Inline `<img>` over httpx shows its alt text on GeckoView** (observed with
  extension 0.2.3 on API 34, identical in debug and release builds, so not an
  R8/rebrand artifact): the extension fetches the image — the gateway logs the
  GET — but the blob URL does not render inside the sandboxed iframe. CSS
  `url()` backgrounds and favicons are unaffected in desktop Chromium's smoke;
  this is a GeckoView-path issue to chase in the xmpp-httpx repo.
- Startup pref for smoke tests: first-run UI is skipped by writing
  `firstrun_shown=false` (inverted semantics) into the app's default shared
  prefs, or by tapping through once.

## Branding

"Firefox", "Focus" and "Klar" are Mozilla trademarks; MPL-2.0 permits
redistributing the code but grants no trademark rights, so the distributable
build is rebranded **Berrak** (Turkish for *clear* — a nod to Klar's German).
What the rebrand changed, all in the `klar` flavor + `src/main`:

- **Name and id**: `app_name` → Berrak; flavor-level
  `applicationId "dev.ooguz.berrak"` replaces `org.mozilla.*`; the static
  launcher shortcuts' stale `targetPackage` (broken since the `.httpx`
  suffix) fixed along the way.
- **Art**: original droplet identity (launcher adaptive + legacy mipmaps,
  wordmark, splash, erase-notification icon, onboarding art, search-widget
  pills) generated into `app/src/klar/res/`, shadowing the fox-flame art in
  `src/main`. The Mozilla-tinted `src/debug` launcher icons were deleted so
  debug builds pick the same overlays (build-type res beats flavor res).
- **Strings**: about/rights texts now state this is an independent fork not
  produced by Mozilla; the "Mozilla" settings category is "Berrak"; the
  tab-crash screen's "Send crash report to Mozilla" checkbox is hidden
  (and relabeled) when no crash service is compiled in — it would have
  sent nothing; stale Firefox/Mozilla translations purged from the locale
  files in two passes (by edited-string name, then by brand words in
  translation bodies — 780 entries total; they fall back to English).
- **Services**: telemetry upload hard-disabled (`GleanMetricsService`), the
  unconditional Socorro crash reporter removed (`Components.kt`), the
  Data Choices settings section dropped, the advertising-ID permission
  stripped from the manifest, Mozilla's issue/PR/security templates and
  bot workflows deleted, and help/privacy links point at this fork's docs
  ([PRIVACY.md](PRIVACY.md)) instead of Mozilla's SUMO pages — a fork must
  not feed Mozilla's data pipelines nor present Mozilla's privacy notice
  as its own. The splash background uses the Berrak palette.

Kept deliberately: the `org.mozilla.focus` Java package namespace (internal,
not user-visible; renaming it would make every upstream diff useless), the
GeckoView user agent (functional), factual "based on Firefox Focus" texts,
and the "Download Firefox" open-in banner (nominative use — it installs the
real Firefox).

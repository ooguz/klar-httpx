# Klar httpx — a Firefox Klar fork that speaks httpx:// (XEP-0332)

A fork of [mozilla-mobile/firefox-android](https://github.com/mozilla-mobile/firefox-android)
(125.0a1, the archived monorepo) whose Focus/Klar Android browser can navigate
`httpx://` URLs — HTTP tunneled over XMPP, as implemented by the
[xmpp-httpx](../n146) library.

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
| `app/src/klar/res/values/app.xml` | app name → "Klar httpx" |
| `app/build.gradle` | klar applicationIdSuffix → `.klar.httpx`; warnings-as-errors off |
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
The applicationId is `org.mozilla.klar.httpx.debug`, so it installs alongside
stock Klar.

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
- **Back navigation**: in embedded mode the extension's own back/forward
  buttons, tab strip and history drawer are hidden, and the page mirrors
  navigation with `history.replaceState` — so Gecko's session history holds a
  single entry and the system back gesture triggers Focus's erase-and-leave
  behavior instead of walking httpx pages. Upstream would need to push (not
  replace) history entries in embedded mode for the app's back to work.
- **Updating the bundled extension**: GeckoView's `ensureBuiltIn` is a no-op
  while the id+version match what is installed — every asset refresh must bump
  `version` in the bundled manifest.json or devices keep the old files.
- **"Block JavaScript" in Focus settings kills the extension page too** (the
  setting is engine-global).
- Startup pref for smoke tests: first-run UI is skipped by writing
  `firstrun_shown=false` (inverted semantics) into the app's default shared
  prefs, or by tapping through once.

## Trademark note

"Firefox" and "Klar" are Mozilla trademarks. This fork is for local/personal
builds; renamed branding and icons are required before any distribution.

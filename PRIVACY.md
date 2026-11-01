# Berrak — Privacy Notice

Berrak is an independent fork of Mozilla's open-source Firefox Focus (Klar),
extended to browse `httpx://` pages (HTTP over XMPP, XEP-0332). It is not
produced or endorsed by Mozilla, and this notice — not Mozilla's — describes
what Berrak does with your data.

## What leaves your device

- **Nothing is sent to us.** Berrak has no telemetry: Glean upload is
  hard-disabled in code, the crash reporter submits to no one (Mozilla's
  Socorro service and the Sentry client were both removed from this fork,
  and the crash screen offers no "send" checkbox), and there is no
  experiments/Nimbus endpoint. Google's Play Core library, used upstream
  only for a store review prompt, is removed too, and so is every other
  Google Play Services library: Berrak ships only free software, which is
  also why WebAuthn (passkeys, security keys) is switched off. One
  cosmetic note: after a crash, Android may briefly show a "gathering crash
  telemetry" notification — that is Glean *recording a crash counter
  locally*; with upload disabled it never leaves the device.
- **Web traffic** goes to the sites you visit, exactly as in Firefox Focus:
  page loads, the tracker-blocklist behavior, safe-browsing checks and
  GeckoView's platform services follow the upstream Focus defaults you can
  control in Settings.
- **httpx:// traffic** goes to the XMPP server you configure inside the
  httpx page's connection settings, over WebSocket. Your requests are visible
  to that XMPP server and to the httpx gateway that serves the content, like
  any origin server would see them.
- **Proxy mode** (Settings → Advanced → "Browse the web through the httpx
  exit", off by default) sends ordinary web addresses to the n146 exit set
  in the httpx connection settings. The exit fetches each page itself,
  HTTPS included, so it sees every page you open this way and what you type
  into it. Turn it on only for an exit you trust with that.

## What stays on your device

- Browsing state follows Focus's private-browsing model: the erase button
  wipes tabs, cookies and history.
- **XMPP credentials** (service URL, JID, password) that you enter for
  httpx browsing are stored by the bundled extension in its local extension
  storage. Note that Focus's erase button does **not** wipe them — clearing
  the app's data (or reinstalling) does.

## Questions

Open an issue at <https://github.com/ooguz/klar-httpx/issues>.

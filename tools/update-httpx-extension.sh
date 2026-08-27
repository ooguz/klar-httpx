#!/usr/bin/env bash
# Rebuild the httpx WebExtension from the xmpp-httpx repo and refresh the
# bundled copy under focus-android/app/src/main/assets/extensions/httpx/.
#
# The manifest.json there is Android-specific and maintained by hand in THIS
# repo (no background script — the XMPP connection lives in the page; no
# "downloads" permission — GeckoView has no browser.downloads, the extension's
# <a download> fallback is used instead). Only the built page and its assets
# are copied.
#
# Usage: tools/update-httpx-extension.sh [path-to-xmpp-httpx-repo]
set -euo pipefail

SRC="${1:-$HOME/Belgeler/bireysel/n146}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HERE/focus-android/app/src/main/assets/extensions/httpx"

[ -d "$SRC/examples/webext" ] || { echo "xmpp-httpx repo not found at $SRC" >&2; exit 1; }

npm --prefix "$SRC/examples/webext" run build

rm -rf "$DEST/assets"
cp "$SRC/examples/webext/dist/app/browser.html" "$DEST/browser.html"
cp -r "$SRC/examples/webext/dist/app/assets" "$DEST/assets"

echo "Refreshed $DEST from $SRC (manifest.json left untouched)"

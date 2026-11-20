/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.httpx

import android.util.Log
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSession.LoadUrlFlags
import mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse

/**
 * Bundles the httpx browser WebExtension (XEP-0332 — HTTP over XMPP) and routes
 * httpx:// URLs to it.
 *
 * The extension is the xmpp-httpx project's browser UI, built from its
 * examples/webext sources with an Android-specific manifest (see
 * assets/extensions/httpx/). It fetches pages over an XMPP connection and renders
 * them inside its own sanitized viewport, so Gecko never needs to understand the
 * httpx scheme: the app rewrites any top-level httpx:// navigation to
 * moz-extension://<uuid>/browser.html#<httpx-url> and maps that URL back to the
 * httpx form wherever it is shown to the user.
 */
object HttpxExtension {
    private const val LOG_TAG = "HttpxExtension"

    const val EXTENSION_ID = "httpx-browser@xmpp-httpx.example"
    const val EXTENSION_URL = "resource://android/assets/extensions/httpx/"
    // ?embedded=1 hides the extension page's own tab strip and URL bar — the
    // app's toolbar is the chrome (see the webext's embedded.ts).
    private const val PAGE = "browser.html?embedded=1"

    private val lock = Any()

    /** moz-extension://<uuid>/ once the built-in install has completed. */
    @Volatile
    var baseUrl: String? = null
        private set

    /**
     * Loads that arrived before the install completed and must not load
     * directly, the latest per session (see [intercept]). Guarded by [lock].
     */
    private val pending = LinkedHashMap<EngineSession, String>()

    fun install(engine: Engine) {
        engine.installBuiltInWebExtension(
            EXTENSION_ID,
            EXTENSION_URL,
            onSuccess = { extension ->
                val base = extension.getMetadata()?.baseUrl
                Log.d(LOG_TAG, "httpx extension installed at $base")
                if (base != null) {
                    for ((session, uri) in installed(base)) {
                        // Reloaded through the interceptor, which now routes it.
                        runCatching { session.loadUrl(uri) }
                            .onFailure { Log.w(LOG_TAG, "could not resume a held load", it) }
                    }
                }
            },
            onError = { throwable ->
                // Held loads stay held: an httpx:// or exit-routed page never
                // falls back to a direct load.
                Log.e(LOG_TAG, "failed to install the httpx extension", throwable)
            },
        )
    }

    /** Records the extension's base URL and hands back the held loads. */
    internal fun installed(base: String): List<Pair<EngineSession, String>> =
        synchronized(lock) {
            baseUrl = base
            pending.toList().also { pending.clear() }
        }

    /** Back to the state before install (tests only). */
    internal fun resetForTest() {
        synchronized(lock) {
            baseUrl = null
            pending.clear()
        }
    }

    private fun isHttpxUrl(uri: String): Boolean =
        uri.startsWith("httpx://") || uri.startsWith("ext+httpx://")

    private fun isWebUrl(uri: String): Boolean =
        uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)

    /**
     * The extension page URL that renders [uri], or null when the page does not
     * handle it. An httpx:// URL always is handled. An ordinary http(s):// URL is
     * handled only while [routeWebThroughExit] — the "browse the web through the
     * httpx exit" setting — is on: the page then fetches it through the exit
     * account in its own connection settings (xmpp-httpx's proxy mode), and
     * reports there when no exit is configured. The URL is kept verbatim in the
     * fragment, which is what the page reads back and what [toDisplayUrl] shows.
     */
    internal fun pageUrlFor(uri: String, routeWebThroughExit: Boolean, base: String): String? {
        if (!handles(uri, routeWebThroughExit)) return null
        return "$base$PAGE#$uri"
    }

    private fun handles(uri: String, routeWebThroughExit: Boolean): Boolean =
        isHttpxUrl(uri) || (routeWebThroughExit && isWebUrl(uri))

    /**
     * An [InterceptionResponse.Url] sending this navigation to the extension
     * page, or null when the page does not handle [uri] (see [pageUrlFor]).
     *
     * Before the extension has finished installing (a link from another app
     * can cold-start the browser faster than that), a navigation the page
     * must handle is denied and held, and [install] loads it again once the
     * page exists. Letting it through instead would load a web URL directly
     * while the user has asked for every page to go through the exit.
     */
    fun intercept(
        session: EngineSession,
        uri: String,
        routeWebThroughExit: Boolean = false,
    ): InterceptionResponse? {
        val base = synchronized(lock) {
            baseUrl ?: run {
                if (!handles(uri, routeWebThroughExit)) return null
                pending[session] = uri
                Log.d(LOG_TAG, "holding a load until the httpx extension is installed")
                return InterceptionResponse.Deny
            }
        }
        val target = pageUrlFor(uri, routeWebThroughExit, base) ?: return null
        // The default flags include EXTERNAL, which makes Gecko load the URL
        // with a null triggering principal — and a null principal may not link
        // to a moz-extension page. Bypassing the load-URI delegate is still
        // required so the rewritten load does not re-enter this interceptor.
        return InterceptionResponse.Url(
            target,
            flags = LoadUrlFlags.select(LoadUrlFlags.LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE),
        )
    }

    /**
     * Maps the extension page's internal URL back to the httpx:// URL it renders,
     * for user-facing display. Any other URL is returned unchanged. The fragment
     * is the httpx URL verbatim (the page maintains it unencoded), so no decoding
     * is applied; an empty fragment (the extension's start page) displays as the
     * bare scheme.
     */
    fun toDisplayUrl(url: CharSequence): CharSequence {
        val base = baseUrl ?: return url
        val value = url.toString()
        if (!value.startsWith("$base$PAGE")) return url
        return value.substringAfter('#', missingDelimiterValue = "").ifEmpty { "httpx://" }
    }
}

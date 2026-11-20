/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.httpx

import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.RequestInterceptor.InterceptionResponse
import mozilla.components.support.test.mock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HttpxExtensionTest {
    private val base = "moz-extension://0123/"
    private val page = "${base}browser.html?embedded=1#"

    @Test
    fun `httpx URLs always open in the extension page`() {
        assertEquals(
            "${page}httpx://server@example.org/index.html?x=1",
            HttpxExtension.pageUrlFor("httpx://server@example.org/index.html?x=1", false, base),
        )
        assertEquals(
            "${page}ext+httpx://server@example.org/",
            HttpxExtension.pageUrlFor("ext+httpx://server@example.org/", true, base),
        )
    }

    @Test
    fun `web URLs open in the extension page only while routing is on`() {
        assertNull(HttpxExtension.pageUrlFor("https://example.org/a?b=c", false, base))
        assertEquals(
            "${page}https://example.org/a?b=c",
            HttpxExtension.pageUrlFor("https://example.org/a?b=c", true, base),
        )
        assertEquals(
            "${page}HTTP://example.org/",
            HttpxExtension.pageUrlFor("HTTP://example.org/", true, base),
        )
    }

    @Test
    fun `other schemes are never rewritten`() {
        for (uri in listOf("about:blank", "mailto:a@b.c", "${base}browser.html?embedded=1#https://x/", "ftp://x/")) {
            assertNull(uri, HttpxExtension.pageUrlFor(uri, true, base))
        }
    }

    @After
    fun reset() = HttpxExtension.resetForTest()

    @Test
    fun `before the install, a routed web load is held, not loaded directly`() {
        val session = mock<EngineSession>()
        assertSame(
            InterceptionResponse.Deny,
            HttpxExtension.intercept(session, "https://example.org/", routeWebThroughExit = true),
        )
        assertSame(
            InterceptionResponse.Deny,
            HttpxExtension.intercept(session, "httpx://server@example.org/", routeWebThroughExit = false),
        )
        // The latest load per session is the one resumed.
        assertEquals(listOf(session to "httpx://server@example.org/"), HttpxExtension.installed(base))
        assertTrue(HttpxExtension.installed(base).isEmpty())
    }

    @Test
    fun `before the install, loads the page does not handle go through`() {
        val session = mock<EngineSession>()
        assertNull(HttpxExtension.intercept(session, "https://example.org/", routeWebThroughExit = false))
        assertNull(HttpxExtension.intercept(session, "about:blank", routeWebThroughExit = true))
        assertTrue(HttpxExtension.installed(base).isEmpty())
    }

    @Test
    fun `after the install, handled loads go to the extension page`() {
        HttpxExtension.installed(base)
        val response = HttpxExtension.intercept(mock(), "https://example.org/", routeWebThroughExit = true)
        assertEquals("${page}https://example.org/", (response as InterceptionResponse.Url).url)
        assertNull(HttpxExtension.intercept(mock(), "https://example.org/", routeWebThroughExit = false))
    }
}

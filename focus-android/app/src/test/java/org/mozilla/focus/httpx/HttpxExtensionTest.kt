/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.httpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}

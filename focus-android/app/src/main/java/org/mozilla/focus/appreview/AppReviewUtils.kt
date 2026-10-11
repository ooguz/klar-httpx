/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.focus.appreview

import android.app.Activity
import android.content.Context

/**
 * Berrak: upstream asked for a Google Play in-app review after a few launches,
 * through Google's proprietary Play Core library, and fell back to opening the
 * store page of Firefox Focus. Berrak is not that app and is not distributed
 * through Google Play, so the prompt is gone and the library with it. The two
 * entry points stay so their callers need no change.
 */
class AppReviewUtils {
    companion object {
        /** No store review prompt in Berrak. */
        @Suppress("UNUSED_PARAMETER")
        fun showAppReview(activity: Activity) = Unit

        /** Nothing to count without a review prompt. */
        @Suppress("UNUSED_PARAMETER")
        fun addAppOpenings(context: Context) = Unit
    }
}

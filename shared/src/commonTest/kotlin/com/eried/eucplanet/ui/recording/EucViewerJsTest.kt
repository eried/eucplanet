package com.eried.eucplanet.ui.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The View online loader both phones run: it waits for the hook and hands the trip over. */
class EucViewerJsTest {
    private val js = EucViewerJs.load("trip_20260601_202431.csv", EucViewerJs.FETCH_SOURCE)

    @Test fun theScriptIsWholeJavaScript() {
        assertEquals(js.count { it == '{' }, js.count { it == '}' })
        assertEquals(js.count { it == '(' }, js.count { it == ')' })
        assertTrue(js.trimEnd().endsWith("})();"))
    }

    @Test fun itWaitsForTheViewersHookThenDeliversTheTripUnderItsName() {
        assertTrue("typeof window.loadFileFromBase64 === 'function'" in js)
        assertTrue("setTimeout(go, 100)" in js, "polls for the hook")
        assertTrue("window.loadFileFromBase64(b64, 'trip_20260601_202431.csv')" in js)
    }

    @Test fun androidFetchesTheInterceptedPathAndDeliversItsBase64() {
        assertTrue("fetch('${EucViewerJs.TRIP_PATH}')" in js)
        assertTrue("deliver(s.substring(s.indexOf(',')+1))" in js)
        assertEquals("/__euc_trip.csv", EucViewerJs.TRIP_PATH)
        assertEquals("https://eucviewer.ried.no/?embedded", EucViewerJs.VIEWER_URL)
    }
}

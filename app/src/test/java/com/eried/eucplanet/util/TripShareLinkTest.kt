package com.eried.eucplanet.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Spec test vectors for the short share link (`#d-…`). Producer side is what
 * the app uses; the decode + round trip mirror the viewer so the format stays
 * honest in both directions.
 */
class TripShareLinkTest {

    private val directUrl =
        "https://dl.dropboxusercontent.com/scl/fi/d08vshn4piz4rew9gd93k/" +
            "trip_20260708_163439.csv?rlkey=wrsgt7shhka0o3tuafrgo50bn&dl=1"
    private val token = "d-d08vshn4piz4rew9gd93k-76jn2ivsf-wrsgt7shhka0o3tuafrgo50bn"

    @Test fun encodes_the_canonical_vector() {
        // 20260708163439 in base36 is 76jn2ivsf.
        assertEquals(token, TripShareLink.encodeToken(directUrl))
        assertEquals(
            "https://eucviewer.ried.no/#$token",
            TripShareLink.shortViewerUrl(directUrl),
        )
    }

    @Test fun round_trips_both_directions() {
        assertEquals(token, TripShareLink.encodeToken(directUrl))
        assertEquals(directUrl, TripShareLink.decodeToken(token))
        assertEquals(directUrl, TripShareLink.decodeToken("#$token"))
    }

    // --- Must NOT encode (fall back to ?file=) ---

    @Test fun rejects_extra_query_parameter() {
        assertNull(
            TripShareLink.encodeToken(
                "https://dl.dropboxusercontent.com/scl/fi/d08vshn4piz4rew9gd93k/" +
                    "trip_20260708_163439.csv?rlkey=wrsgt7shhka0o3tuafrgo50bn&st=abc123&dl=1",
            ),
        )
    }

    @Test fun rejects_non_trip_filename() {
        assertNull(
            TripShareLink.encodeToken(
                "https://dl.dropboxusercontent.com/scl/fi/d08vshn4piz4rew9gd93k/" +
                    "ride_20260708_163439.csv?rlkey=wrsgt7shhka0o3tuafrgo50bn&dl=1",
            ),
        )
    }

    @Test fun rejects_wrong_timestamp_shape() {
        assertNull(
            TripShareLink.encodeToken(
                "https://dl.dropboxusercontent.com/scl/fi/d08vshn4piz4rew9gd93k/" +
                    "trip_20260708_1634.csv?rlkey=wrsgt7shhka0o3tuafrgo50bn&dl=1",
            ),
        )
    }

    @Test fun rejects_wrong_host() {
        assertNull(
            TripShareLink.encodeToken(
                "https://www.dropbox.com/scl/fi/d08vshn4piz4rew9gd93k/" +
                    "trip_20260708_163439.csv?rlkey=wrsgt7shhka0o3tuafrgo50bn&dl=1",
            ),
        )
    }

    @Test fun rejects_uppercase_rlkey() {
        assertNull(
            TripShareLink.encodeToken(
                "https://dl.dropboxusercontent.com/scl/fi/d08vshn4piz4rew9gd93k/" +
                    "trip_20260708_163439.csv?rlkey=WRSGT7shhka0o3tuafrgo50bn&dl=1",
            ),
        )
    }

    @Test fun rejects_missing_dl_or_dl_zero() {
        assertNull(
            TripShareLink.encodeToken(
                "https://dl.dropboxusercontent.com/scl/fi/d08vshn4piz4rew9gd93k/" +
                    "trip_20260708_163439.csv?rlkey=wrsgt7shhka0o3tuafrgo50bn&dl=0",
            ),
        )
    }

    @Test fun rejects_fragment() {
        assertNull(
            TripShareLink.encodeToken(
                "https://dl.dropboxusercontent.com/scl/fi/d08vshn4piz4rew9gd93k/" +
                    "trip_20260708_163439.csv?rlkey=wrsgt7shhka0o3tuafrgo50bn&dl=1#x",
            ),
        )
    }

    // --- Must NOT decode (invalid tokens) ---

    @Test fun rejects_ts36_overflowing_14_digits() {
        // base36 value has 15 digits.
        assertNull(TripShareLink.decodeToken("d-abc-zzzzzzzzz-def"))
    }

    @Test fun rejects_empty_ts36() {
        assertNull(TripShareLink.decodeToken("d-abc--def"))
    }

    @Test fun rejects_uppercase_tag() {
        assertNull(TripShareLink.decodeToken("D-abc-76jn2ivsf-def"))
    }
}

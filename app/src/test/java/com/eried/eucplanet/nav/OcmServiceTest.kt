package com.eried.eucplanet.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic guards for the Open Charge Map response parser. No network. */
class OcmServiceTest {

    private val sample = """
        [{
          "ID": 12345,
          "AddressInfo": {"Title": "Storo Storsenter"},
          "OperatorInfo": {"Title": "Mer"},
          "UsageCost": "Free",
          "StatusType": {"Title": "Operational"},
          "NumberOfPoints": 4,
          "Connections": [
            {"ConnectionType": {"Title": "Type 2"}, "PowerKW": 22.0},
            {"ConnectionType": {"Title": "CCS"}, "PowerKW": 50.0},
            {"ConnectionType": {"Title": "Type 2"}, "PowerKW": 22.0}
          ],
          "MediaItems": [
            {"ItemThumbnailURL": "https://ocm/thumb1.jpg", "ItemURL": "https://ocm/1.jpg", "IsVideo": false},
            {"ItemURL": "https://ocm/vid.mp4", "IsVideo": true}
          ],
          "UserComments": [
            {"UserName": "Alice", "Rating": 5, "Comment": "Worked great",
             "DateCreated": "2025-03-01T12:00:00Z", "CheckinStatusType": {"Title": "Charged successfully"}},
            {"UserName": "Bob", "Rating": 3, "Comment": "Slow", "DateCreated": "2025-02-01T08:00:00Z"}
          ]
        }]
    """.trimIndent()

    @Test fun parseFirst_mapsAllFields() {
        val c = OcmService.parseFirst(sample)!!
        assertEquals(12345L, c.ocmId)
        assertEquals("Storo Storsenter", c.title)
        assertEquals("Mer", c.operator)
        assertEquals("Free", c.usageCost)
        assertEquals("Operational", c.status)
        assertEquals(4, c.numberOfPoints)
        assertEquals("https://map.openchargemap.io/?id=12345", c.ocmUrl)
    }

    @Test fun connectors_groupedWithQuantity() {
        val c = OcmService.parseFirst(sample)!!
        // two Type 2 entries collapse to "2×", distinct CCS stays separate
        assertEquals("2× Type 2 · 22 kW, CCS · 50 kW", c.connectors)
    }

    @Test fun ratings_averagedAndCounted() {
        val c = OcmService.parseFirst(sample)!!
        assertEquals(4.0, c.avgRating!!, 0.001)
        assertEquals(2, c.ratingCount)
    }

    @Test fun comments_parsedWithCheckinAndShortDate() {
        val c = OcmService.parseFirst(sample)!!
        assertEquals(2, c.comments.size)
        val alice = c.comments.first { it.user == "Alice" }
        assertEquals(5, alice.rating)
        assertEquals("Charged successfully", alice.checkin)
        assertEquals("2025-03-01", alice.date) // truncated to the day
    }

    @Test fun photos_excludeVideos_carryBothUrls() {
        val c = OcmService.parseFirst(sample)!!
        assertEquals(1, c.photos.size)
        val photo = c.photos.single()
        // Thumbnail used for the inline image, full URL for tap-to-open.
        assertEquals("https://ocm/thumb1.jpg", photo.thumbnailUrl)
        assertEquals("https://ocm/1.jpg", photo.fullUrl)
    }

    @Test fun emptyArray_isNull_andUnratedCommentHasNullRating() {
        assertNull(OcmService.parseFirst("[]"))
        val noRating = """[{"ID":1,"UserComments":[{"UserName":"X","Comment":"hi"}]}]"""
        val c = OcmService.parseFirst(noRating)!!
        assertNull(c.avgRating)
        assertTrue(c.comments.single().rating == null)
    }
}

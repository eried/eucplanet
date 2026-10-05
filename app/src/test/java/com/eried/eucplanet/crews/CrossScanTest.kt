package com.eried.eucplanet.crews

import com.eried.eucplanet.share.ShareLinks
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two QR codes this app deals in, each held up to the other one's scanner.
 *
 * Both scanners refused the wrong code correctly and both said the same unhelpful thing: that
 * it was not a valid code. It is a perfectly valid code; it is the other one. The scanners now
 * recognise that case and name it, and what makes that possible is exactly the pair of facts
 * below -- each parser returns null for the other's link, and the OTHER parser returns non-null
 * for it. A `misfit` recogniser is only correct while both halves hold.
 *
 * This lives in a unit test and not on a phone because the misfit path fires on a decoded
 * frame: reaching it by hand means printing a code and pointing a camera at it, and reaching
 * it on an emulator means injecting an image into a virtual scene. The logic it guards is two
 * pure functions.
 */
class CrossScanTest {

    private val pass = "https://eucstats.ried.no/p/QCW4GC"

    // Generated rather than typed. A hand-written one failed this test on its first run:
    // BOTH halves of the fragment are 22-character base64url, and the key I invented was ten,
    // so `ShareLinks.parse` returned null and the test accused the recogniser of a fault that
    // was in the fixture. Asking the app for a link is also the only way this stays true if
    // the format moves.
    private val share = ShareLinks.format(ShareLinks.newLink())

    @Test
    fun `a crew pass is not a share link`() {
        assertNull("the share scanner would have opened a map room on a pairing code",
            ShareLinks.parse(pass))
    }

    @Test
    fun `a crew pass is recognised as one, so the share scanner can say so`() {
        assertNotNull("the share scanner cannot name what it refused", PairLink.parse(pass))
    }

    @Test
    fun `a share link is not a crew pass`() {
        assertNull("the crew scanner would have tried to pair against the share host",
            PairLink.parse(share))
    }

    @Test
    fun `a share link is recognised as one, so the crew scanner can say so`() {
        assertNotNull("the crew scanner cannot name what it refused", ShareLinks.parse(share))
    }

    @Test
    fun `something that is neither stays unrecognised by both`() {
        // a wifi config QR, a product barcode, a URL from anywhere else: the scanners keep
        // looking and say the ordinary "not a code we know", which is the honest answer here
        for (junk in listOf(
            "WIFI:T:WPA;S:HomeNet;P:hunter2;;",
            "5901234123457",
            "https://example.com/anything",
            "",
        )) {
            assertNull("PairLink claimed $junk", PairLink.parse(junk))
            assertNull("ShareLinks claimed $junk", ShareLinks.parse(junk))
        }
    }
}

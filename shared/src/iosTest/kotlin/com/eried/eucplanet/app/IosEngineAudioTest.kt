package com.eried.eucplanet.app

import com.eried.eucplanet.audio.EngineProfile
import com.eried.eucplanet.audio.TestGrainBanks
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull

/**
 * The iPhone's engine output reads the grain banks from the bundle, and they
 * are the very files in res/raw. The playback itself is checked in the app
 * on the simulator (docs/IOS_SYNC.md, EUC_ENGINE): in the standalone test
 * process an AVAudioEngine does not start (avfaudio error -10851).
 */
class IosEngineAudioTest {

    @Test fun everyRecordedEngineHasItsBankInTheBundle() {
        for (asset in EngineProfile.PROFILES.mapNotNull { it.grainAsset }.toSet()) {
            val bundled = IosEngineAudio.bundledBank(asset)
            assertNotNull(bundled, asset)
            assertContentEquals(TestGrainBanks.bytes("$asset.bin"), bundled, asset)
        }
    }
}

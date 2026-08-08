package com.eried.eucplanet.data

import com.eried.eucplanet.data.store.OverlayPresetJson
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.hud.protocol.ReplaySourceType
import com.eried.eucplanet.hud.protocol.ViewportConfig
import com.eried.eucplanet.hud.protocol.ViewportLayout
import com.eried.eucplanet.hud.protocol.ViewportReplayFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayPresetJsonReplayTest {

    @Test fun videoReplayFace_roundTrips() {
        val preset = OverlayPreset(
            layout = ViewportLayout.COLUMNS_2,
            viewports = listOf(
                ViewportConfig(), // pane 0: camera live, no replay -> transparent
                ViewportConfig(
                    replay = ViewportReplayFace(
                        source = ReplaySourceType.VIDEO,
                        videoUri = "content://x/y/clip.mp4",
                        videoFit = "STRETCH",
                        videoOffsetMs = 4200L,
                        videoEdge = "LOOP",
                    )
                ),
            ),
        )
        val loaded = OverlayPresetJson.fromJson(OverlayPresetJson.toJson(preset))
        assertNull(loaded.viewports[0].replay)
        val r = loaded.viewports[1].replay!!
        assertEquals(ReplaySourceType.VIDEO, r.source)
        assertEquals("content://x/y/clip.mp4", r.videoUri)
        assertEquals("STRETCH", r.videoFit)
        assertEquals(4200L, r.videoOffsetMs)
        assertEquals("LOOP", r.videoEdge)
    }

    @Test fun missingVideoEdge_defaultsToFreeze() {
        val json = OverlayPresetJson.toJson(
            OverlayPreset(
                viewports = listOf(
                    ViewportConfig(replay = ViewportReplayFace(source = ReplaySourceType.VIDEO))
                )
            )
        )
        (json.getJSONArray("viewports").getJSONObject(0).getJSONObject("replay")).remove("videoEdge")
        val loaded = OverlayPresetJson.fromJson(json)
        assertEquals("FREEZE", loaded.viewports[0].replay!!.videoEdge)
    }

    @Test fun missingReplay_loadsAsNull_transparentByDefault() {
        val json = OverlayPresetJson.toJson(OverlayPreset(viewports = listOf(ViewportConfig())))
        (json.getJSONArray("viewports").getJSONObject(0)).remove("replay")
        val loaded = OverlayPresetJson.fromJson(json)
        assertNull(loaded.viewports[0].replay)
    }
}

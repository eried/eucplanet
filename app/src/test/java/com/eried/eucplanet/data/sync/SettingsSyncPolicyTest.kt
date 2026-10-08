package com.eried.eucplanet.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSyncPolicyTest {
    private val P = "phone"; private val R = "remote"; private val B = "base"
    @Test fun remoteMissing_uploads() =
        assertEquals(SettingsSyncAction.UPLOAD, SettingsSyncPolicy.decide(P, null, B))
    @Test fun equal_isNoOp() =
        assertEquals(SettingsSyncAction.NONE, SettingsSyncPolicy.decide("x", "x", B))
    @Test fun phoneChangedOnly_uploads() =
        assertEquals(SettingsSyncAction.UPLOAD, SettingsSyncPolicy.decide(P, B, B))
    @Test fun remoteChangedOnly_applies() =
        assertEquals(SettingsSyncAction.APPLY, SettingsSyncPolicy.decide(B, R, B))
    @Test fun bothChanged_conflicts() =
        assertEquals(SettingsSyncAction.CONFLICT, SettingsSyncPolicy.decide(P, R, B))
    @Test fun bothChangedToSame_isNoOp() =
        assertEquals(SettingsSyncAction.NONE, SettingsSyncPolicy.decide("same", "same", B))
}

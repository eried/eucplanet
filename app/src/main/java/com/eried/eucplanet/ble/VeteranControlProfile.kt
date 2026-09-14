package com.eried.eucplanet.ble

/** Model-specific command mapping; transport, capabilities and UI stay unchanged. */
internal interface VeteranControlProfile {
    fun setLight(on: Boolean): ByteArray
    fun setLightFollowup(on: Boolean): ByteArray?

    companion object {
        fun forModel(model: VeteranModel?): VeteranControlProfile =
            if (model == VeteranModel.NOSFET_AEON) AeonControlProfile else DefaultVeteranControlProfile
    }
}

internal object DefaultVeteranControlProfile : VeteranControlProfile {
    override fun setLight(on: Boolean): ByteArray = VeteranCommands.setHighBeam(on)
    override fun setLightFollowup(on: Boolean): ByteArray = VeteranCommands.setHighBeamCompanion(on)
}

internal object AeonControlProfile : VeteranControlProfile {
    // Aeon 503002 capture: ASCII toggles OFF/LOW without the binary acknowledgement.
    // This is not a high-beam selector and is independent of panel key-tone volume.
    override fun setLight(on: Boolean): ByteArray = VeteranCommands.setLight(on)
    override fun setLightFollowup(on: Boolean): ByteArray? = null
}

package com.eried.eucplanet.data.eucstats

/** One attestation result: the token to send + the request hash it was bound to. */
data class AttestationToken(val type: String, val token: String, val requestHash: String)

/** Swappable attestation provider (Play Integrity on Android; iOS App Attest later). */
interface Attestation {
    suspend fun token(requestHash: String): AttestationToken
}

/** Stub used until Play Integrity is configured; the server accepts it in stub mode. */
class StubAttestation : Attestation {
    override suspend fun token(requestHash: String) =
        AttestationToken("play_integrity", "", requestHash)
}

/**
 * The iPhone's stand-in until App Attest is wired: the right type, no token.
 * The server accepts it in stub mode, as it accepts [StubAttestation].
 */
class StubAppAttest : Attestation {
    override suspend fun token(requestHash: String) =
        AttestationToken("app_attest", "", requestHash)
}

package com.eried.eucplanet.data.eucstats

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider
import com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---------------------------------------------------------------------------
// Play Integrity implementation (config-gated; used only when a non-zero
// EUCSTATS_GCP_PROJECT_NUMBER is configured, see the provider in EucStatsModule).
// ---------------------------------------------------------------------------

class PlayIntegrityAttestation(
    private val context: Context,
    private val cloudProjectNumber: Long,
) : Attestation {
    @Volatile private var provider: StandardIntegrityTokenProvider? = null

    override suspend fun token(requestHash: String): AttestationToken {
        val p = provider ?: prepareProvider().also { provider = it }
        val token = suspendCancellableCoroutine { cont ->
            p.request(StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build())
                .addOnSuccessListener { cont.resume(it.token()) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return AttestationToken("play_integrity", token, requestHash)
    }

    private suspend fun prepareProvider(): StandardIntegrityTokenProvider =
        suspendCancellableCoroutine { cont ->
            IntegrityManagerFactory.createStandard(context)
                .prepareIntegrityToken(
                    PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber).build()
                )
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
}

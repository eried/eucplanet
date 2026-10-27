package com.eried.eucplanet.ui.recording

import androidx.lifecycle.ViewModel
import com.eried.eucplanet.data.repository.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads a recorded trip's CSV bytes for the embedded EUC Viewer WebView. */
class EucViewerViewModel(
    private val tripRepository: TripRepository
) : ViewModel() {

    /** Returns (CSV bytes, file name) for [tripId], or null if the file is gone. */
    suspend fun tripPayload(tripId: Long): Pair<ByteArray, String>? =
        withContext(Dispatchers.IO) {
            val trip = tripRepository.getTripById(tripId) ?: return@withContext null
            val file = tripRepository.getTripFile(trip)
            if (!file.exists()) return@withContext null
            file.readBytes() to file.name
        }
}

package com.eried.eucplanet.ui.recording

import androidx.lifecycle.ViewModel
import com.eried.eucplanet.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Loads a recorded trip's CSV bytes for the embedded EUC Viewer WebView. */
@HiltViewModel
class EucViewerViewModel @Inject constructor(
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

package com.eried.eucplanet.ui.recording

import android.util.Base64
import androidx.lifecycle.ViewModel
import com.eried.eucplanet.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Loads a recorded trip's CSV as base64 for the embedded EUC Viewer WebView. */
@HiltViewModel
class EucViewerViewModel @Inject constructor(
    private val tripRepository: TripRepository
) : ViewModel() {

    /** Returns (base64 CSV, file name) for [tripId], or null if it is missing. */
    suspend fun tripPayload(tripId: Long): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            val trip = tripRepository.getTripById(tripId) ?: return@withContext null
            val file = tripRepository.getTripFile(trip)
            if (!file.exists()) return@withContext null
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) to file.name
        }
}

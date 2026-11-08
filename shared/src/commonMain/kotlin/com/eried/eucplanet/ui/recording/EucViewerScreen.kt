package com.eried.eucplanet.ui.recording

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import com.eried.eucplanet.platform.Log
import com.eried.eucplanet.platform.stringResource
import com.eried.eucplanet.resources.Res
import com.eried.eucplanet.resources.studio_replay_cd_back
import com.eried.eucplanet.resources.trip_action_view_online
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

private const val TAG = "EucViewer"

/** The trip's CSV for the embedded viewer: its bytes and file name. */
class EucViewerPayload(val bytes: ByteArray, val name: String)

/** Loads a recorded trip's CSV bytes for the embedded EUC Viewer. */
class EucViewerViewModel(private val trips: RecordingTrips) : ViewModel() {

    /** The CSV and file name for [tripId], or null if the trip or its file is gone. */
    suspend fun tripPayload(tripId: Long): EucViewerPayload? = withContext(Dispatchers.IO) {
        val trip = trips.getTripById(tripId) ?: return@withContext null
        val bytes = trips.readTripBytes(trip) ?: return@withContext null
        EucViewerPayload(bytes, trip.fileName)
    }
}

/** What the viewer screen asks of the platform's web view. */
interface EucViewerHost {
    /**
     * The web view showing [url]. [payload] is the trip once it is read
     * (null until then), for a platform that serves it to the page on
     * request. [onPage] gets the page to call into; [onLoaded] runs when the
     * document has loaded.
     */
    @Composable
    fun ViewerWebView(
        url: String,
        payload: () -> EucViewerPayload?,
        onPage: (TripMapPage) -> Unit,
        onLoaded: () -> Unit,
        modifier: Modifier,
    )

    /**
     * Hands [payload] to [page] and returns the JavaScript that reads it
     * back and calls `deliver(base64)` with it ([EucViewerJs.load]).
     */
    fun tripSource(page: TripMapPage, payload: EucViewerPayload): String
}

/**
 * Hosts the EUC Viewer (eucviewer.ried.no) embedded in a web view and feeds
 * it the trip's CSV through the viewer's documented
 * `window.loadFileFromBase64` hook (see the viewer's INTEGRATION.md). How the
 * bytes reach the page is the platform's ([EucViewerHost.tripSource]): a long
 * ride is several MB, more than one injected script carries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EucViewerScreen(
    tripId: Long,
    onBack: () -> Unit,
    viewModel: EucViewerViewModel,
    host: EucViewerHost,
) {
    var payload by remember { mutableStateOf<EucViewerPayload?>(null) }
    var page by remember { mutableStateOf<TripMapPage?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    LaunchedEffect(tripId) {
        val p = viewModel.tripPayload(tripId)
        payload = p
        Log.i(TAG, "trip $tripId payload: ${p?.bytes?.size ?: -1} bytes, name=${p?.name}")
    }

    LaunchedEffect(pageReady, payload) {
        val p = payload
        val pg = page
        if (pageReady && p != null && pg != null) {
            pg.evaluate(EucViewerJs.load(p.name, host.tripSource(pg, p)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.trip_action_view_online)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.studio_replay_cd_back)
                        )
                    }
                }
            )
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize().padding(pad)) {
            host.ViewerWebView(
                url = EucViewerJs.VIEWER_URL,
                payload = { payload },
                onPage = { page = it },
                onLoaded = { pageReady = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

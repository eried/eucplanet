package com.eried.eucplanet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.repository.SettingsRepository
import com.eried.eucplanet.flic.FlicManager
import com.eried.eucplanet.service.WheelService
import com.eried.eucplanet.ui.navigation.NavGraph
import com.eried.eucplanet.ui.theme.EucPlanetTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var flicManager: FlicManager

    private val settingsFlow: StateFlow<AppSettings?> get() = _settings.asStateFlow()
    private val _settings = MutableStateFlow<AppSettings?>(null)

    private var volumeUpDownTime = 0L
    private var volumeDownDownTime = 0L
    private var volumeUpHoldFired = false
    private var volumeDownHoldFired = false
    private val holdThresholdMs = 600L

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted/denied - UI will react accordingly */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMissingPermissions()

        lifecycleScope.launch {
            settingsRepository.settings.collect {
                val first = _settings.value == null
                _settings.value = it
                if (first) {
                    val want = if (it.language.isBlank()) "en" else it.language
                    if (com.eried.eucplanet.util.LocaleHelper.current() != want) {
                        com.eried.eucplanet.util.LocaleHelper.apply(want)
                    }
                    // If user wants announcements regardless of connection, start the
                    // foreground service now so the voice loop runs even with no wheel.
                    if (it.voiceEnabled && !it.voiceOnlyWhenConnected) {
                        startForegroundService(Intent(this@MainActivity, WheelService::class.java))
                    }
                }
            }
        }

        setContent {
            val s by _settings.collectAsState()
            EucPlanetTheme(
                themeMode = s?.themeMode ?: "dark",
                accentColor = s?.accentColor ?: "blue"
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val s = _settings.value
        if (s != null && s.volumeKeysEnabled &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val now = System.currentTimeMillis()
            if (event?.repeatCount == 0) {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> { volumeUpDownTime = now; volumeUpHoldFired = false }
                    KeyEvent.KEYCODE_VOLUME_DOWN -> { volumeDownDownTime = now; volumeDownHoldFired = false }
                }
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP ->
                        if (!volumeUpHoldFired && now - volumeUpDownTime >= holdThresholdMs) {
                            volumeUpHoldFired = true
                            flicManager.dispatchActionByName(s.volumeUpHold)
                        }
                    KeyEvent.KEYCODE_VOLUME_DOWN ->
                        if (!volumeDownHoldFired && now - volumeDownDownTime >= holdThresholdMs) {
                            volumeDownHoldFired = true
                            flicManager.dispatchActionByName(s.volumeDownHold)
                        }
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val s = _settings.value
        if (s != null && s.volumeKeysEnabled &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP ->
                    if (!volumeUpHoldFired) flicManager.dispatchActionByName(s.volumeUpClick)
                KeyEvent.KEYCODE_VOLUME_DOWN ->
                    if (!volumeDownHoldFired) flicManager.dispatchActionByName(s.volumeDownClick)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

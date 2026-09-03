package com.eried.eucplanet.tpms

import com.eried.eucplanet.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where a paired sensor is remembered between runs.
 *
 * Pairing was in memory only, so a rider who scanned, found their cap and
 * closed the app had to scan again next time, forever. The address is worth
 * exactly one field, and [com.eried.eucplanet.data.model.TpmsSettings] has had
 * it all along - nothing ever wrote to it.
 *
 * An interface so [TpmsRepository] stays a plain object a unit test can build
 * with no Android and no DataStore behind it.
 */
interface TpmsPairingStore {

    /** Hands back the remembered addresses, once, whenever it can. */
    fun load(onLoaded: (List<String>) -> Unit)

    /** Remembers exactly this set of sensors, replacing whatever was stored. */
    fun saveAll(addresses: List<String>)

    /** Remembers nothing. The default in tests. */
    object None : TpmsPairingStore {
        override fun load(onLoaded: (List<String>) -> Unit) = Unit
        override fun saveAll(addresses: List<String>) = Unit
    }
}

/** The real one: a field in the rider's settings, like every other preference. */
@Singleton
class SettingsTpmsPairingStore @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : TpmsPairingStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun load(onLoaded: (List<String>) -> Unit) {
        scope.launch {
            val t = settingsRepository.get().tpms
            // A rider upgrading from the single-sensor build keeps their cap:
            // the old field is read as a one-item list when the new one is
            // empty.
            onLoaded(t.pairedAddresses.ifEmpty { listOfNotNull(t.pairedAddress) })
        }
    }

    override fun saveAll(addresses: List<String>) {
        scope.launch {
            settingsRepository.update { current ->
                if (current.tpms.pairedAddresses == addresses) current
                else current.copy(
                    tpms = current.tpms.copy(
                        pairedAddresses = addresses,
                        // The old single slot follows the first sensor so a
                        // downgrade still finds something.
                        pairedAddress = addresses.firstOrNull(),
                    )
                )
            }
        }
    }
}

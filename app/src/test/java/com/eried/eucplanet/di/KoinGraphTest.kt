package com.eried.eucplanet.di

import android.content.Context
import androidx.work.WorkerParameters
import com.eried.eucplanet.ble.gps.DragyAdapter
import com.eried.eucplanet.ble.gps.ExternalGpsAdapter
import com.eried.eucplanet.ble.gps.RaceBoxAdapter
import com.eried.eucplanet.ble.radar.RadarAdapter
import com.eried.eucplanet.ble.radar.VariaAdapter
import com.eried.eucplanet.data.eucstats.EucStatsQualifiers
import com.eried.eucplanet.stripComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.core.annotation.KoinInternalApi
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify
import java.io.File

/**
 * Koin builds the graph at run time, so a constructor parameter nobody
 * provides compiles fine and crashes the app the first time that object is
 * asked for. These tests move that failure into the build.
 */
class KoinGraphTest {

    /**
     * Every definition's constructor parameters resolve. The extra types are
     * the ones the graph does not define itself: the Android context (from
     * androidContext), WorkerParameters (handed in by the worker factory), and
     * the generic types Koin keys by their erased class, which the modules
     * provide by name (the adapter sets, the eucstats lambdas and strings) or
     * as a Lazy through inject().
     */
    @OptIn(KoinExperimentalAPI::class)
    @Test fun `every definition can be built`() {
        module { includes(appModules) }.verify(
            extraTypes = listOf(
                Context::class,
                WorkerParameters::class,
                Lazy::class,
                Set::class,
                String::class,
                Function0::class,
                Function1::class,
            ),
        )
    }

    /**
     * [verify] accepts any Set, String or lambda, so the named ones are
     * checked here: each qualifier has a definition, and the adapter sets hold
     * every adapter in the order the multibindings listed them.
     */
    @OptIn(KoinInternalApi::class)
    @Test fun `every named definition is present`() {
        val koin = koinApplication { modules(appModules) }.koin
        val qualifiers = koin.instanceRegistry.instances.values
            .mapNotNull { it.beanDefinition.qualifier }
            .toSet()
        for (q in listOf(
            DiQualifiers.ExternalGpsAdapters,
            DiQualifiers.RadarAdapters,
            EucStatsQualifiers.AppVersion,
            EucStatsQualifiers.OsVersion,
            EucStatsQualifiers.TripFileBytes,
            EucStatsQualifiers.Clock,
        )) {
            assertTrue("no definition named $q", q in qualifiers)
        }
        assertEquals(
            listOf(RaceBoxAdapter::class, DragyAdapter::class),
            koin.get<Set<ExternalGpsAdapter>>(DiQualifiers.ExternalGpsAdapters).map { it::class },
        )
        assertEquals(
            listOf(VariaAdapter::class),
            koin.get<Set<RadarAdapter>>(DiQualifiers.RadarAdapters).map { it::class },
        )
    }

    /**
     * Every type the app asks Koin for outside the modules is defined: each
     * ViewModel (koinViewModel), each worker (the worker factory), and each
     * `get()` in an Android component or other KoinComponent.
     */
    @OptIn(KoinInternalApi::class)
    @Test fun `everything the app asks for is defined`() {
        val koin = koinApplication { modules(appModules) }.koin
        val defined = koin.instanceRegistry.instances.values
            .flatMap { listOf(it.beanDefinition.primaryType) + it.beanDefinition.secondaryTypes }
            .mapNotNull { it.simpleName }
            .toSet()

        val sources = listOf("src/main/java", "src/debug/java")
            .flatMap { File(it).walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .filter { it.parentFile?.name != "di" }
            .associateWith { stripComments(it.readText()) }

        val requested = mutableMapOf<String, String>()
        for ((file, src) in sources) {
            val asked = Regex("class\\s+(\\w+)\\s*\\([^)]*\\)\\s*:\\s*(?:ViewModel|CoroutineWorker)\\(")
                .findAll(src).map { it.groupValues[1] } +
                Regex("\\bval\\s+\\w+\\s*:\\s*([\\w.]+)\\s*=\\s*get\\(\\)").findAll(src)
                    .map { it.groupValues[1].substringAfterLast('.') } +
                Regex("\\bget<([\\w.]+)>\\(\\)").findAll(src)
                    .map { it.groupValues[1].substringAfterLast('.') }
            asked.forEach { requested[it] = file.name }
        }
        // A floor, so a change in how the sources are written cannot leave
        // this scan finding nothing and passing on an empty list.
        assertTrue("only ${requested.size} requested types found", requested.size >= 50)
        val missing = requested.filterKeys { it !in defined }
        assertTrue("asked of Koin but not defined in any module: $missing", missing.isEmpty())
    }
}

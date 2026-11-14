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
     * the generic types Koin keys by their erased class (the automations take
     * their clock and sun as lambdas), which the modules
     * provide by name (the adapter sets, the eucstats lambdas and strings) or
     * as a Lazy through inject(). The offline tile store's folder and the
     * offline router's timeout and engine, and the crews pairing HTTP client,
     * are built in their definitions.
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
                Function2::class,
                Function3::class,
                java.io.File::class,
                Long::class,
                com.eried.eucplanet.platform.HttpClient::class,
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

        // The shared module's view models too: Android's Koin builds them.
        val sources = listOf("src/main/java", "src/debug/java", "../shared/src/commonMain/kotlin")
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

    /**
     * `singleOf(::X)` asks Koin for every constructor parameter, the optional
     * and nullable ones too, but [verify] skips those. DropboxRepository's
     * optional test transport passed verify and crashed the app at start.
     */
    @OptIn(KoinInternalApi::class)
    @Test fun `constructor references get every parameter they ask for`() {
        val koin = koinApplication { modules(appModules) }.koin
        val definitions = koin.instanceRegistry.instances.values.map { it.beanDefinition }
        val defined = definitions
            .flatMap { listOf(it.primaryType) + it.secondaryTypes }
            .toSet()
        val byName = definitions.associateBy { it.primaryType.simpleName }

        val referenced = File("src/main/java/com/eried/eucplanet/di")
            .walkTopDown().filter { it.extension == "kt" }
            .flatMap { f ->
                Regex("\\b(?:singleOf|factoryOf|viewModelOf|workerOf)\\(::(\\w+)\\)")
                    .findAll(stripComments(f.readText())).map { it.groupValues[1] }
            }
            .toSet()
        assertTrue("only ${referenced.size} constructor references found", referenced.size >= 30)

        val missing = mutableListOf<String>()
        for (name in referenced) {
            val ctor = byName[name]?.primaryType?.constructors?.singleOrNull() ?: continue
            for (p in ctor.parameters) {
                if (!p.isOptional && !p.type.isMarkedNullable) continue
                val type = p.type.classifier as? kotlin.reflect.KClass<*> ?: continue
                if (type !in defined) missing += "$name(${p.name}: ${type.simpleName})"
            }
        }
        assertTrue("optional parameters Koin cannot provide: $missing", missing.isEmpty())
    }
}

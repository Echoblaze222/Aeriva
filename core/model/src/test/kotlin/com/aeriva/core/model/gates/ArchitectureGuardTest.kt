package com.aeriva.core.model.gates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AI 4 test gate (Phase 4 test-gate specification, group AG).
 *
 * Fitness tests over the repository's own source and manifests. They encode
 * POLICY, not a snapshot of today's files, so a legitimate slice (for example
 * S3 adding INTERNET to network:monitor) does not have to edit this file.
 * They live in core:model because that is a pure JVM module whose tests run
 * in every CI job; they read sibling modules through the repository root.
 *
 * Every scan asserts it found something to scan, so a moved directory cannot
 * turn a guard into a silent pass.
 */
class ArchitectureGuardTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun mainSources(): List<File> =
        root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "kt" && it.invariantSeparatorsPath.contains("/src/main/") }
            .toList()

    private fun mainManifests(): List<File> =
        root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.name == "AndroidManifest.xml" && it.invariantSeparatorsPath.endsWith("/src/main/AndroidManifest.xml") }
            .toList()

    private fun relative(file: File): String = file.relativeTo(root).invariantSeparatorsPath

    /** Removes block and line comments so prose in KDoc cannot trigger a guard. */
    private fun code(file: File): String =
        file.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("(?<!:)//.*"), "")

    private fun offenders(files: List<File>, pattern: Regex): List<String> =
        files.filter { pattern.containsMatchIn(code(it)) }.map { relative(it) }

    @Test
    fun scansAreNotVacuous() {
        assertTrue("no main sources found under $root", mainSources().size >= 10)
        assertTrue("no main manifests found under $root", mainManifests().size >= 2)
    }

    @Test
    fun coreModel_importsOnlyJdkKotlinAndItself() {
        val coreModel = mainSources().filter { relative(it).startsWith("core/model/src/main/") }
        assertTrue("core:model sources not found", coreModel.isNotEmpty())
        val allowed = listOf("java.", "kotlin.", "com.aeriva.core.model")
        val bad = coreModel.flatMap { file ->
            code(file).lines()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .map { it.removePrefix("import ").trim() }
                .filter { imp -> allowed.none { imp.startsWith(it) } }
                .map { "${relative(file)}: $it" }
        }
        assertEquals("core:model must stay Android-free and dependency-free: $bad", emptyList<String>(), bad)
    }

    @Test
    fun noPacketLossTypeIsDeclaredAnywhere() {
        // A packet-loss claim needs a calibrated loss model (Decision D4-6 / stage D). Until then no
        // type may carry that name. The PACKET_LOSS enum constant in the capability list is not a type.
        val bad = offenders(mainSources(), Regex("\\b(class|interface|object)\\s+\\w*PacketLoss\\w*"))
        assertEquals("packet-loss types are forbidden before stage D calibration: $bad", emptyList<String>(), bad)
    }

    @Test
    fun networkQualityMeasured_isNeverConstructedByProductionCode() {
        // Decision D4-11: nothing populates NetworkQuality.Measured. Only the declaration file may mention it.
        val bad = mainSources()
            .filterNot { relative(it).endsWith("core/model/NetworkQuality.kt") }
            .filter { Regex("NetworkQuality\\.Measured\\s*\\(").containsMatchIn(code(it)) }
            .map { relative(it) }
        assertEquals("no code path may synthesize a Measured quality score: $bad", emptyList<String>(), bad)
    }

    @Test
    fun noCustomTlsTrustOrHostnameOverrides() {
        // Decision D3-9 / D1-7: certificate validation stays the platform default.
        val bad = offenders(
            mainSources(),
            Regex("\\bX509TrustManager\\b|\\bHostnameVerifier\\b|\\bcheckServerTrusted\\b|\\bsetHostnameVerifier\\b")
        )
        assertEquals("custom trust or hostname verification needs a recorded security review: $bad", emptyList<String>(), bad)
    }

    @Test
    fun noThirdPartyMeasurementEndpointsAreHardCoded() {
        // Decisions D2-2 / D2-9: measurement traffic goes only to endpoints the project runs.
        val bad = offenders(
            mainSources(),
            Regex("measurementlab|speed\\.cloudflare|generate_204|connectivitycheck\\.|fast\\.com|speedtest\\.net", RegexOption.IGNORE_CASE)
        )
        assertEquals("third-party measurement hosts are forbidden: $bad", emptyList<String>(), bad)
    }

    @Test
    fun internetPermission_isDeclaredOnlyInNetworkMonitor_andNeverInAppOrOtherLibraries() {
        // Decision D1-1: the permission lives in the module that opens sockets, so it is auditable in one place.
        val declaring = mainManifests()
            .filter { Regex("android\\.permission\\.INTERNET").containsMatchIn(it.readText()) }
            .map { relative(it) }
        val allowed = setOf("network/monitor/src/main/AndroidManifest.xml")
        assertEquals("INTERNET may only be declared by network:monitor: $declaring", emptyList<String>(), declaring.filterNot { it in allowed })
    }

    @Test
    fun noMainManifestOrNetworkSecurityConfigPermitsCleartext() {
        // Decision D1-7: cleartext is refused in every non-debug source set.
        val manifestBad = mainManifests()
            .filter { Regex("usesCleartextTraffic\\s*=\\s*\"true\"").containsMatchIn(it.readText()) }
            .map { relative(it) }
        val xmlBad = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" }
            .filter { it.isFile && it.extension == "xml" && it.invariantSeparatorsPath.contains("/src/main/res/xml/") }
            .filter { Regex("cleartextTrafficPermitted\\s*=\\s*\"true\"").containsMatchIn(it.readText()) }
            .map { relative(it) }
            .toList()
        assertEquals("cleartext must not be permitted in main manifests: $manifestBad", emptyList<String>(), manifestBad)
        assertEquals("cleartext must not be permitted in main network security config: $xmlBad", emptyList<String>(), xmlBad)
    }

    @Test
    fun onlyTheMeasurementModuleConstructsMeasurementResults() {
        // A measurement must come from a probe. Any other module building a Succeeded is a fabrication risk.
        val bad = mainSources()
            .filterNot { relative(it).startsWith("network/monitor/src/main/") }
            .filter { Regex("(?<!class )\\bSucceeded\\s*\\(").containsMatchIn(code(it)) }
            .map { relative(it) }
        assertEquals("only network:monitor may construct Succeeded measurement results: $bad", emptyList<String>(), bad)
    }

    @Test
    fun noFakeMockOrStubTypesLiveInProductionSources() {
        val bad = offenders(mainSources(), Regex("\\b(class|object|interface)\\s+(Fake|Mock|Stub)\\w*"))
        assertEquals("test doubles belong in test source sets: $bad", emptyList<String>(), bad)
    }

    @Test
    fun noCodePathClaimsToTellNxdomainApartFromResolverFailure_andNoDnsMeasurementTypeExists() {
        // Decisions D5-4 and D4-10: NOT_RESOLVED cannot separate NXDOMAIN, and DNS stays an estimation with no type of its own.
        val nxdomain = offenders(mainSources(), Regex("NXDOMAIN", RegexOption.IGNORE_CASE))
        val dnsType = offenders(mainSources(), Regex("\\b(class|interface|object)\\s+\\w*Dns(Measurement|Latency|Timing)\\w*"))
        assertEquals("no NXDOMAIN claim is possible from the platform exception: $nxdomain", emptyList<String>(), nxdomain)
        assertEquals("DNS gets no measurement type in Phase 4: $dnsType", emptyList<String>(), dnsType)
    }
}

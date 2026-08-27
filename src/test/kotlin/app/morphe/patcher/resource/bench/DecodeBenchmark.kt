/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.bench

import app.morphe.patcher.resource.coder.ArsclibResourceCoder
import app.morphe.patcher.resource.processor.StringsXmlEscapeProcessor
import app.morphe.patcher.resource.processor.StringsXmlSanitizeProcessor
import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.xml.XMLFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Not a unit test: a decode-phase benchmark, enabled only when the environment variable
 * DECODE_BENCH_APK points at an APK file. Run with:
 *
 * DECODE_BENCH_APK=/path/to/orig.apk DECODE_BENCH_REPS=3 TEST_MAX_HEAP=2g \
 *   flock /home/jack/.cache/morphe-heavy.lock ./gradlew :test --tests "*DecodeBenchmark*"
 */
internal class DecodeBenchmark {
    private val apkPath: String? = System.getenv("DECODE_BENCH_APK")
    private val reps = System.getenv("DECODE_BENCH_REPS")?.toInt() ?: 3
    private val benchRoot = File(System.getenv("DECODE_BENCH_DIR") ?: "build/decode-bench")

    private val heapPools = java.lang.management.ManagementFactory.getMemoryPoolMXBeans()
        .filter { it.type == java.lang.management.MemoryType.HEAP }

    private fun resetPeakHeap() = heapPools.forEach { it.resetPeakUsage() }
    private fun peakHeapMb() = heapPools.sumOf { it.peakUsage.used } / (1024 * 1024)

    private fun freshDir(name: String): File {
        val dir = benchRoot.resolve(name)
        dir.deleteRecursively()
        dir.mkdirs()
        return dir
    }

    @Test
    fun `baseline decodeResources`() {
        assumeTrue(apkPath != null)
        val apkFile = File(apkPath!!)

        repeat(reps) { rep ->
            val workingDir = freshDir("baseline")
            System.gc()
            resetPeakHeap()
            val start = System.nanoTime()
            ArsclibResourceCoder(workingDir, apkFile).use { coder ->
                legacyDecode(coder, workingDir, apkFile)
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            println("BENCH baseline decodeResources rep=$rep ${elapsedMs}ms peakHeap=${peakHeapMb()}MB")
        }
        benchRoot.resolve("baseline").deleteRecursively()
    }

    @Test
    fun `lazy decodeResources`() {
        assumeTrue(apkPath != null)
        val apkFile = File(apkPath!!)

        repeat(reps) { rep ->
            val workingDir = freshDir("lazy")
            System.gc()
            resetPeakHeap()
            val start = System.nanoTime()
            ArsclibResourceCoder(workingDir, apkFile).use { coder ->
                coder.decodeResources()
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            println("BENCH lazy decodeResources rep=$rep ${elapsedMs}ms peakHeap=${peakHeapMb()}MB")
        }
        benchRoot.resolve("lazy").deleteRecursively()
    }

    @Test
    fun `lazy decode breakdown`() {
        assumeTrue(apkPath != null)
        val apkFile = File(apkPath!!)
        val workingDir = freshDir("lazy-breakdown")

        var t = System.nanoTime()
        fun lap(label: String) {
            val now = System.nanoTime()
            println("BENCH lazy-breakdown $label ${(now - t) / 1_000_000}ms")
            t = System.nanoTime()
        }

        val pending = mutableMapOf<File, app.morphe.patcher.resource.coder.PendingResourceFile>()
        ApkModule.loadApkFile(apkFile).use { apkModule ->
            lap("loadApkFile")
            val decoder = app.morphe.patcher.resource.coder.PlaceholderApkModuleXmlDecoder(apkModule, pending)
            decoder.setKeepResPath(false)
            decoder.setDexDecoder { _, _ -> }
            decoder.dexProfileDecoder = null
            decoder.decode(workingDir)
            lap("decode(placeholders)")
        }
        lap("closeModule")

        val coder = ArsclibResourceCoder(workingDir, apkFile)
        workingDir.resolve("resources").listFiles { f: File -> f.isDirectory }?.forEach { dir ->
            val packageJson = com.reandroid.json.JSONObject(dir.resolve("package.json"))
            coder.packageDirectories[packageJson.getString("package_name")] = dir
        }
        StringsXmlSanitizeProcessor(coder::getFile, coder.packageDirectories).process()
        lap("stringsSanitize")
        StringsXmlEscapeProcessor(coder::getFile, coder.packageDirectories).process()
        lap("stringsEscape")
        coder.fileSnapshotCache = coder.buildFileSnapshot()
        lap("buildFileSnapshot")
        coder.getPackageMetadata()
        lap("getPackageMetadata")
        println("BENCH lazy-breakdown pending=${pending.size}")
        coder.close()
        workingDir.deleteRecursively()
    }

    @Test
    fun `round trip equivalence`() {
        assumeTrue(apkPath != null)
        val apkFile = File(apkPath!!)

        fun hashTree(dir: File): Map<String, String> =
            dir.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(dir).invariantSeparatorsPath to sha256(it)
            }

        fun hashApkEntries(apk: File): Map<String, String> {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val result = mutableMapOf<String, String>()
            java.util.zip.ZipFile(apk).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    digest.reset()
                    zip.getInputStream(entry).use { stream ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    result[entry.name] = digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
            return result
        }

        fun compare(label: String, expected: Map<String, String>, actual: Map<String, String>) {
            val missing = expected.keys - actual.keys
            val extra = actual.keys - expected.keys
            val differing = expected.keys.intersect(actual.keys).filter { expected[it] != actual[it] }
            println(
                "BENCH compare $label: expected=${expected.size} actual=${actual.size} " +
                    "missing=${missing.size} extra=${extra.size} differing=${differing.size}",
            )
            (missing.take(10) + extra.take(10) + differing.take(20)).forEach { println("BENCH   diff: $it") }
            kotlin.test.assertTrue(
                missing.isEmpty() && extra.isEmpty() && differing.isEmpty(),
                "$label mismatch",
            )
        }

        // All decodes run before any encode: encodeResources mutates the global XmlCoder
        // setting, which changes how a later decode names and escapes things.
        val baselineDir = freshDir("rt-baseline")
        val baselineCoder = ArsclibResourceCoder(baselineDir, apkFile)
        legacyDecode(baselineCoder, baselineDir, apkFile)
        val baselineTree = hashTree(baselineDir)

        // Lazy decode with every file materialized must produce an identical tree.
        val materializedDir = freshDir("rt-materialized")
        ArsclibResourceCoder(materializedDir, apkFile).use { coder ->
            coder.decodeResources()
            val start = System.nanoTime()
            coder.materializePending(materializedDir)
            println("BENCH materialize-all ${(System.nanoTime() - start) / 1_000_000}ms")
            compare("decoded tree", baselineTree, hashTree(materializedDir))
        }
        benchRoot.resolve("rt-materialized").deleteRecursively()

        val lazyDir = freshDir("rt-lazy")
        val lazyCoder = ArsclibResourceCoder(lazyDir, apkFile)
        lazyCoder.decodeResources()

        // Encode both untouched trees; the lazy one reuses every entry from the original APK.
        val baselineOut = freshDir("rt-baseline-out")
        System.gc(); resetPeakHeap()
        var start = System.nanoTime()
        val baselineApk = baselineCoder.encodeResources(baselineOut)
        println("BENCH baseline encodeResources ${(System.nanoTime() - start) / 1_000_000}ms peakHeap=${peakHeapMb()}MB")
        val baselineApkHashes = hashApkEntries(baselineApk)
        baselineCoder.close()

        val lazyOut = freshDir("rt-lazy-out")
        System.gc(); resetPeakHeap()
        start = System.nanoTime()
        val lazyApk = lazyCoder.encodeResources(lazyOut)
        println("BENCH lazy encodeResources ${(System.nanoTime() - start) / 1_000_000}ms peakHeap=${peakHeapMb()}MB")
        lazyCoder.close()

        // Entry-level: every entry of the lazy output must equal either what the baseline encoder
        // produced, or the untouched original APK entry (re-encoding decoded XML is not
        // byte-preserving, copying the original entry is). resources.arsc must equal the baseline.
        val originalHashes = hashApkEntries(apkFile)
        val lazyHashes = hashApkEntries(lazyApk)
        val missing = baselineApkHashes.keys - lazyHashes.keys
        val extra = lazyHashes.keys - baselineApkHashes.keys
        var sameAsBaseline = 0; var sameAsOriginal = 0
        val unexplained = mutableListOf<String>()
        lazyHashes.forEach { (name, hash) ->
            when {
                hash == baselineApkHashes[name] -> sameAsBaseline++
                hash == originalHashes[name] -> sameAsOriginal++
                else -> unexplained += name
            }
        }
        println("BENCH encoded APK: entries=${lazyHashes.size} missing=${missing.size} extra=${extra.size} " +
            "sameAsBaseline=$sameAsBaseline sameAsOriginal=$sameAsOriginal unexplained=${unexplained.size} " +
            "arscMatchesBaseline=${lazyHashes["resources.arsc"] == baselineApkHashes["resources.arsc"]}")
        unexplained.take(20).forEach { println("BENCH   unexplained: $it") }
        kotlin.test.assertTrue(missing.isEmpty() && extra.isEmpty() && unexplained.isEmpty(), "encoded APK mismatch")
        kotlin.test.assertEquals(baselineApkHashes["resources.arsc"], lazyHashes["resources.arsc"], "resources.arsc")

        // Semantic: decoding both output APKs with the stock decoder must give identical text.
        fun stockDecodeTree(apk: File, name: String): Map<String, String> {
            val dir = freshDir(name)
            ApkModule.loadApkFile(apk).use { m ->
                val d = ApkModuleXmlDecoder(m).also { it.setKeepResPath(false) }
                d.setDexDecoder { _, _ -> }; d.dexProfileDecoder = null
                d.decode(dir)
            }
            return hashTree(dir).also { if (System.getenv("DECODE_BENCH_KEEP") != "1") dir.deleteRecursively() }
        }
        val redecodedBaseline = stockDecodeTree(baselineApk, "rt-redecode-baseline")
        val redecodedLazy = stockDecodeTree(lazyApk, "rt-redecode-lazy")
        val redecodedOriginal = stockDecodeTree(apkFile, "rt-redecode-original")
        // Reused binary XML resources must re-decode exactly like the original APK does (the
        // baseline's decode -> re-encode round trip is not fully faithful, e.g. it retypes the
        // string "0.9f" to a float). Everything else must re-decode exactly like the baseline.
        fun isResXml(path: String) =
            path.startsWith("resources/") && path.contains("/res/") && !path.contains("/res/values") && path.endsWith(".xml")
        compare(
            "re-decoded reused XML vs original",
            redecodedOriginal.filterKeys(::isResXml),
            redecodedLazy.filterKeys(::isResXml),
        )
        compare(
            "re-decoded rest vs baseline",
            redecodedBaseline.filterKeys { !isResXml(it) },
            redecodedLazy.filterKeys { !isResXml(it) },
        )
        val xmlDiffsVsBaseline = redecodedBaseline.keys.filter(::isResXml).count { redecodedBaseline[it] != redecodedLazy[it] }
        println("BENCH re-decoded XML differing from baseline output (baseline round-trip infidelity): $xmlDiffsVsBaseline")

        if (System.getenv("DECODE_BENCH_KEEP") != "1") {
            listOf("rt-baseline", "rt-baseline-out", "rt-lazy", "rt-lazy-out").forEach {
                benchRoot.resolve(it).deleteRecursively()
            }
        }
    }

    /**
     * The decode behavior before lazy decoding: [ApkModuleXmlDecoder] extracts everything, then
     * the strings.xml processors and the snapshots run, exactly like the old
     * [ArsclibResourceCoder.decodeResources]. With no pending files, the coder's encode side
     * behaves exactly like it did before the change.
     */
    private fun legacyDecode(coder: ArsclibResourceCoder, workingDir: File, apkFile: File) {
        ApkModule.loadApkFile(apkFile).use { apkModule ->
            val xmlDecoder = ApkModuleXmlDecoder(apkModule).also { it.setKeepResPath(false) }
            xmlDecoder.setDexDecoder { _, _ -> }
            xmlDecoder.dexProfileDecoder = null
            xmlDecoder.decode(workingDir)

            workingDir.resolve("resources").listFiles { f: File -> f.isDirectory }?.forEach { dir ->
                val packageJson = com.reandroid.json.JSONObject(dir.resolve("package.json"))
                coder.packageDirectories[packageJson.getString("package_name")] = dir
            }
        }

        StringsXmlSanitizeProcessor(coder::getFile, coder.packageDirectories).process()
        StringsXmlEscapeProcessor(coder::getFile, coder.packageDirectories).process()

        coder.fileSnapshotCache = coder.buildFileSnapshot()
        val pathMapJsonFile = workingDir.resolve("path-map.json")
        if (pathMapJsonFile.exists()) {
            coder.pathMap = app.morphe.patcher.resource.PathMap(pathMapJsonFile.readText(Charsets.UTF_8))
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `instrumented breakdown`() {
        assumeTrue(apkPath != null)
        val apkFile = File(apkPath!!)
        val workingDir = freshDir("breakdown")

        fun heapMb(): Long {
            System.gc()
            val rt = Runtime.getRuntime()
            return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        }

        var t = System.nanoTime()
        fun lap(label: String) {
            val now = System.nanoTime()
            println("BENCH $label ${(now - t) / 1_000_000}ms heap=${heapMb()}MB")
            t = System.nanoTime()
        }

        val module = ApkModule.loadApkFile(apkFile)
        lap("loadApkFile")
        val tableBlock = module.tableBlock
        lap("loadTableBlock")

        val decoder = ApkModuleXmlDecoder(module).also { it.setKeepResPath(false) }
        decoder.setDexDecoder { _, _ -> }
        decoder.dexProfileDecoder = null

        decoder.decodeAndroidManifest(workingDir)
        lap("decodeAndroidManifest")

        // Replicate decodeResourceTable with a per-kind split.
        val resourcesDir = workingDir.resolve("resources")
        val packageDirs = mutableMapOf<PackageBlock, File>()
        var pkgIndex = 1
        tableBlock.listPackages().forEach { packageBlock ->
            val dir = resourcesDir.resolve("package_$pkgIndex")
            pkgIndex++
            packageDirs[packageBlock] = dir
            packageBlock.toJson(false).write(dir.resolve("package.json"))
            packageBlock.serializePublicXml(dir.resolve("res/values/public.xml"))
        }
        lap("packageInfo+publicXml")

        val resFiles = module.listResFiles()
        lap("listResFiles(count=${resFiles.size})")

        val decodedEntries = HashMap<Int, MutableSet<ResConfig>>()
        var xmlCount = 0; var rawCount = 0
        var xmlBytes = 0L; var rawBytes = 0L
        var xmlNanos = 0L; var rawNanos = 0L
        resFiles.forEach { resFile ->
            val entry: Entry = resFile.pickOne()
            val packageBlock = entry.packageBlock
            val path = resFile.buildPath(PackageBlock.RES_DIRECTORY_NAME)
            resFile.filePath = path
            val outFile = packageDirs[packageBlock]!!.resolve(path)
            outFile.parentFile.mkdirs()
            val inputSource = resFile.inputSource
            val s = System.nanoTime()
            if (resFile.isBinaryXml) {
                val document = ResXmlDocument()
                document.readBytes(inputSource.openStream())
                document.setPackageBlock(packageBlock)
                val serializer = XMLFactory.newSerializer(outFile, document.encoding)
                document.serialize(serializer)
                serializer.flush()
                xmlNanos += System.nanoTime() - s
                xmlCount++; xmlBytes += outFile.length()
            } else {
                inputSource.write(outFile)
                rawNanos += System.nanoTime() - s
                rawCount++; rawBytes += outFile.length()
            }
            resFile.iterator().forEach { e ->
                if (!e.isNull) {
                    decodedEntries.getOrPut(e.resourceId) { HashSet() }.add(e.resConfig)
                }
            }
            decoder.addDecodedPath(inputSource.alias)
        }
        println("BENCH resFiles-xml count=$xmlCount bytes=$xmlBytes ${xmlNanos / 1_000_000}ms")
        println("BENCH resFiles-raw count=$rawCount bytes=$rawBytes ${rawNanos / 1_000_000}ms")
        lap("decodeResFiles(total)")

        XmlCoder.getInstance().VALUES_XML.decodeTable(resourcesDir, tableBlock) { entry ->
            decodedEntries[entry.resourceId]?.contains(entry.resConfig) == true
        }
        lap("decodeValues")

        decoder.decodeDexFiles(workingDir)
        lap("decodeDexFiles(noop)")

        var rootCount = 0; var rootBytes = 0L
        decoder.extractRootFiles(workingDir)
        workingDir.resolve("root").walkTopDown().filter { it.isFile }.forEach {
            rootCount++; rootBytes += it.length()
        }
        println("BENCH rootFiles count=$rootCount bytes=$rootBytes")
        lap("extractRootFiles")

        decoder.decodePathMap(workingDir)
        decoder.dumpSignatures(workingDir)
        lap("pathMap+signatures")

        module.close()
        lap("close")

        val totalFiles = workingDir.walkTopDown().count { it.isFile }
        val totalBytes = workingDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        println("BENCH total files=$totalFiles bytes=$totalBytes")
        workingDir.deleteRecursively()
    }
}

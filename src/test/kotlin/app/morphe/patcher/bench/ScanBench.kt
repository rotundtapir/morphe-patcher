/*
 * Benchmark harness for the encode-side scanDirectory phase. Not a test; run via the
 * `scanBench` Gradle task. Never shipped.
 */

package app.morphe.patcher.bench

import app.morphe.patcher.resource.coder.ArsclibResourceCoder
import java.io.File
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.measureTimeMillis

fun main(args: Array<String>) {
    val apk = File(args[0])
    val workRoot = File(args[1])
    val reps = args.getOrNull(2)?.toIntOrNull() ?: 1

    // Make patcher logs visible.
    Logger.getLogger("").apply {
        handlers.forEach { it.level = Level.ALL }
        if (handlers.isEmpty()) addHandler(ConsoleHandler().apply { level = Level.ALL })
    }

    repeat(reps) { rep ->
        val repDir = workRoot.resolve("rep$rep").apply { deleteRecursively(); mkdirs() }
        val workingDir = repDir.resolve("working")
        val outputDir = repDir.resolve("out").apply { mkdirs() }

        val coder = ArsclibResourceCoder(workingDir, apk)

        val decodeMs = measureTimeMillis { coder.decodeResources() }

        // Simulate a realistic patch: modify some res XMLs, a binary asset, add a file.
        simulatePatch(coder)

        System.gc()
        val encodeMs = measureTimeMillis {
            coder.encodeResources(outputDir)
        }
        println("BENCH rep=$rep decodeMs=$decodeMs encodeMs=$encodeMs " +
            "outSize=${outputDir.resolve("resources.apk").length()}")
        coder.close()
        if (rep < reps - 1) repDir.deleteRecursively()
    }
}

private fun simulatePatch(coder: ArsclibResourceCoder) {
    val pkgDir = coder.packageDirectories.values.first()
    val res = pkgDir.resolve("res")

    // Touch ~20 decoded XML resources (like patches editing layouts/values). Go through getFile
    // like a patch would, so lazily decoded trees materialize the file first.
    var touched = 0
    res.resolve("layout").listFiles { f: File -> f.extension == "xml" }
        .orEmpty().sortedBy { it.name }.take(20).forEach { placeholder ->
            val f = coder.getFile("res/layout/${placeholder.name}", null, false)
            f.writeText(f.readText())
            f.setLastModified(System.currentTimeMillis() + 5000)
            touched++
        }

    // Edit main strings.xml (very common for patches).
    val strings = coder.getFile("res/values/strings.xml", null, false)
    if (strings.isFile) {
        strings.writeText(
            strings.readText().replace("</resources>",
                "    <string name=\"morphe_bench_added\">bench</string>\n</resources>")
        )
        touched++
    }

    // Add a new raw resource under root (like extension assets).
    coder.otherResourcesRootDirectory.resolve("assets/morphe_bench.bin").apply {
        parentFile.mkdirs()
        writeBytes(ByteArray(4096) { it.toByte() })
    }
    println("BENCH simulated patch: touched=$touched files")
}

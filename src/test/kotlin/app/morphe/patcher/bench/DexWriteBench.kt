/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.bench

import app.morphe.patcher.InternalApi
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherContext
import app.morphe.patcher.dex.BytecodeMode
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger

/**
 * Standalone benchmark of the DEX write phase ([app.morphe.patcher.patch.BytecodePatchContext.get]).
 *
 * Loads an APK's dex files through the real [PatcherContext], marks a deterministic subset of
 * classes as modified (materialized [app.morphe.patcher.util.proxy.mutableTypes.MutableClass]es,
 * as real patches produce), then times `bytecodeContext.get()`.
 *
 * Usage (via the `dexWriteBench` Gradle task):
 *   -PbenchApk=/path/to/orig.apk -PbenchMode=STRIP_FAST -PbenchReps=3 -PbenchHeap=2g
 *
 * Prints per-rep phase timings (from log timestamps), total time, SHA-256 of every produced
 * classes*.dex (for byte-identity comparison across code versions), and peak sampled heap.
 */
object DexWriteBench {
    @JvmStatic
    @OptIn(InternalApi::class)
    fun main(args: Array<String>) {
        val apkFile = File(args[0])
        val mode = BytecodeMode.valueOf(args[1])
        val reps = args[2].toInt()
        val workRoot = File(args[3])
        val modifyEvery = if (args.size > 4) args[4].toInt() else 40

        require(apkFile.isFile) { "APK not found: $apkFile" }
        workRoot.mkdirs()

        // Capture patcher log messages with timestamps to segment phases.
        val events = mutableListOf<Pair<Long, String>>()
        val rootLogger = Logger.getLogger("app.morphe.patcher")
        rootLogger.level = Level.ALL
        rootLogger.addHandler(object : java.util.logging.Handler() {
            override fun publish(record: LogRecord) {
                synchronized(events) { events.add(System.nanoTime() to record.message) }
            }
            override fun flush() {}
            override fun close() {}
        })

        // Peak heap sampler.
        val memBean = ManagementFactory.getMemoryMXBean()
        val peakHeap = AtomicLong(0)
        val sampler = Thread {
            while (!Thread.interrupted()) {
                val used = memBean.heapMemoryUsage.used
                peakHeap.accumulateAndGet(used, ::maxOf)
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }.apply { isDaemon = true; start() }

        println("== DexWriteBench apk=${apkFile.name} mode=$mode reps=$reps modifyEvery=$modifyEvery maxHeap=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB ==")

        repeat(reps) { rep ->
            val tmp = workRoot.resolve("rep$rep").apply { deleteRecursively(); mkdirs() }
            val config = PatcherConfig(
                apkFile = apkFile,
                temporaryFilesPath = tmp,
                useBytecodeMode = mode,
            )
            config.bytecodeMode = mode
            val context = PatcherContext(config)

            val tDecode0 = System.nanoTime()
            context.bytecodeContext.decodeDexFiles()
            val tDecode1 = System.nanoTime()

            // Deterministically mark every Nth class as modified (sorted by descriptor),
            // materializing the MutableClass like real patches do.
            val allTypes = ArrayList<String>()
            context.bytecodeContext.classDefForEach { allTypes.add(it.type) }
            allTypes.sort()
            var modified = 0
            for (i in allTypes.indices step modifyEvery) {
                val mutable = context.bytecodeContext.patchClasses.mutableClassBy(allTypes[i])
                // Touch methods so lazy materialization happens now (as patching would).
                mutable.methods.forEach { it.implementation?.instructions?.size }
                modified++
            }
            val tMutate1 = System.nanoTime()

            events.clear()
            System.gc()
            val heapBeforeGet = memBean.heapMemoryUsage.used
            peakHeap.set(heapBeforeGet)

            val tGet0 = System.nanoTime()
            val result = context.bytecodeContext.get()
            val tGet1 = System.nanoTime()

            // Hash outputs for byte-identity comparison.
            val hashes = result.sortedBy { it.name }.map { dex ->
                val md = MessageDigest.getInstance("SHA-256")
                var size = 0L
                dex.stream.use { s ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = s.read(buf); if (n < 0) break
                        md.update(buf, 0, n); size += n
                    }
                }
                Triple(dex.name, size, md.digest().joinToString("") { "%02x".format(it) })
            }

            fun ms(a: Long, b: Long) = "%.0f".format((b - a) / 1e6)
            println("rep=$rep decode=${ms(tDecode0, tDecode1)}ms mutate($modified classes)=${ms(tDecode1, tMutate1)}ms GET=${ms(tGet0, tGet1)}ms peakHeapDuringGet=${peakHeap.get() / (1024 * 1024)}MB")
            synchronized(events) {
                events.forEach { (t, msg) -> println("    +${"%6.0f".format((t - tGet0) / 1e6)}ms  $msg") }
            }
            hashes.forEach { (name, size, sha) -> println("    OUT $name $size $sha") }

            context.close()
            tmp.deleteRecursively()
        }
        sampler.interrupt()
    }
}

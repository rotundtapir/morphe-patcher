/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 *
 * Original forked code:
 * https://github.com/LisoUseInAIKyrios/revanced-patcher
 */

package app.morphe.patcher

import app.morphe.patcher.dex.BytecodeMode
import app.morphe.patcher.patch.*
import app.morphe.patcher.resource.ResourceMode
import kotlinx.coroutines.flow.flow
import java.io.Closeable
import java.util.concurrent.ForkJoinPool
import java.util.logging.Logger

/**
 * A Patcher.
 *
 * @param config The configuration to use for the patcher.
 */
class Patcher(private val config: PatcherConfig) : Closeable {
    private val logger = Logger.getLogger(this::class.java.name)

    /**
     * The context containing the current state of the patcher.
     */
    val context = PatcherContext(config)

    /**
     * Add patches.
     *
     * @param patches The patches to add.
     */
    operator fun plusAssign(patches: Set<Patch<*>>) {
        // Add all patches to the executablePatches set.
        context.executablePatches += patches

        // Add all patches and their dependencies to the allPatches set.
        patches.forEach { patch ->
            fun Patch<*>.addRecursively() =
                also(context.allPatches::add).dependencies.forEach(Patch<*>::addRecursively)

            patch.addRecursively()
        }

        context.allPatches.let { allPatches ->
            // Check, if what kind of resource mode is required.
            config.resourceMode = if (allPatches.any { patch -> patch.anyRecursively { it is ResourcePatch } }) {
                ResourceMode.FULL
            } else if (allPatches.any { patch -> patch.anyRecursively { it is RawResourcePatch } }) {
                ResourceMode.RAW_ONLY
            } else {
                ResourceMode.NONE
            }

            config.bytecodeMode = if (allPatches.any { patch -> patch.anyRecursively { it is BytecodePatch } }) {
                config.useBytecodeMode
            } else {
                BytecodeMode.NONE
            }
        }
    }

    /**
     * Execute added patches.
     *
     * @return A flow of [PatchResult]s.
     */
    operator fun invoke() = flow {
        fun Patch<*>.execute(
            executedPatches: LinkedHashMap<Patch<*>, PatchResult>,
        ): PatchResult {
            // If the patch was executed before or failed, return it's the result.
            executedPatches[this]?.let { patchResult ->
                patchResult.exception ?: return patchResult

                return PatchResult(this, PatchException("The patch '$this' failed previously"))
            }

            // Recursively execute all dependency patches.
            dependencies.forEach { dependency ->
                dependency.execute(executedPatches).exception?.let {
                    return PatchResult(
                        this,
                        PatchException(
                            "The patch \"$this\" depends on \"$dependency\", which raised an exception:\n${it.stackTraceToString()}",
                        ),
                    )
                }
            }

            // Execute the patch.
            return try {
                execute(context)

                PatchResult(this)
            } catch (exception: PatchException) {
                PatchResult(this, exception)
            } catch (exception: Exception) {
                PatchResult(this, PatchException(exception))
            }.also { executedPatches[this] = it }
        }

        // Prevent decoding the app manifest twice if it is not needed.
        if (config.resourceMode != ResourceMode.NONE) {
            context.resourceContext.decodeResources(config.resourceMode)
        }

        if (config.bytecodeMode != BytecodeMode.NONE) {
            context.bytecodeContext.decodeDexFiles()
            preResolveFingerprints()
        }

        logger.info("Executing patches")

        val executedPatches = LinkedHashMap<Patch<*>, PatchResult>()

        context.executablePatches.sortedBy { it.name }.forEach { patch ->
            val patchResult = patch.execute(executedPatches)

            // If an exception occurred or the patch has no finalize block, emit the result.
            if (patchResult.exception != null || patch.finalizeBlock == null) {
                emit(patchResult)
            }
        }

        val succeededPatchesWithFinalizeBlock = executedPatches.values.filter {
            it.exception == null && it.patch.finalizeBlock != null
        }

        succeededPatchesWithFinalizeBlock.asReversed().forEach { executionResult ->
            val patch = executionResult.patch

            val result =
                try {
                    patch.finalize(context)

                    executionResult
                } catch (exception: PatchException) {
                    PatchResult(patch, exception)
                } catch (exception: Exception) {
                    PatchResult(patch, PatchException(exception))
                }

            if (result.exception != null) {
                emit(
                    PatchResult(
                        patch,
                        PatchException(
                            "The patch \"$patch\" raised an exception: ${result.exception.stackTraceToString()}",
                            result.exception,
                        ),
                    ),
                )
            } else if (patch in context.executablePatches) {
                emit(result)
            }
        }
    }

    /**
     * Resolve the fingerprints that patches declared with [BytecodePatchBuilder.fingerprints]
     * concurrently, before any patch executes. Resolution only reads the classes, and every
     * extension known ahead of time is merged first so the class set is final. Patches then
     * find their matches cached and execute in the usual order. Failures here are ignored:
     * the patch resolves the fingerprint again on use and reports the error itself.
     */
    private fun preResolveFingerprints() {
        val bytecodePatches = LinkedHashSet<BytecodePatch>()
        fun collect(patch: Patch<*>) {
            patch.dependencies.forEach(::collect)
            if (patch is BytecodePatch) bytecodePatches += patch
        }
        context.executablePatches.forEach(::collect)

        val fingerprints = bytecodePatches.flatMap { it.fingerprints }.distinct()
        if (fingerprints.isEmpty()) return

        val bytecodeContext = context.bytecodeContext
        bytecodePatches.forEach { bytecodeContext.mergeExtension(it, eagerOnly = true) }

        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_RESOLVE_THREADS)
        logger.info("Resolving ${fingerprints.size} fingerprints of ${bytecodePatches.size} patches on $threads threads")
        val pool = ForkJoinPool(threads)
        bytecodeContext.preResolvingFingerprints = true
        try {
            pool.submit {
                fingerprints.parallelStream().forEach { fingerprint ->
                    try {
                        with(bytecodeContext) { fingerprint.matchOrNull() }
                    } catch (_: Exception) {
                        // Resolved again, and reported, by the patch that uses it.
                    }
                }
            }.get()
        } finally {
            bytecodeContext.preResolvingFingerprints = false
            pool.shutdown()
        }
    }

    private companion object {
        /** Bounded: each concurrent scan decodes instructions, which costs heap on a phone. */
        private const val MAX_RESOLVE_THREADS = 4
    }

    override fun close() = context.close()

    /**
     * Compile and save patched APK files.
     *
     * @return The [PatcherResult] containing the patched APK files.
     */
    @OptIn(InternalApi::class)
    fun get(): PatcherResult {
        Fingerprint.clearFingerprints()
        context.allPatches.clear()
        context.executablePatches.clear()
        val dexFiles = context.bytecodeContext.get()
        context.bytecodeContext.close()
        val resFiles = context.resourceContext.get()
        return PatcherResult(dexFiles, resFiles)
    }
}

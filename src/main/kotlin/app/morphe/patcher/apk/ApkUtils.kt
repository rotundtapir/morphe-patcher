/*
 * Code hard forked from:
 * https://github.com/revanced/revanced-library/tree/06733072045c8016a75f232dec76505c0ba2e1cd
 */

package app.morphe.patcher.apk

import app.morphe.patcher.PatcherResult
import app.morphe.patcher.apk.ApkSigner.newApkSigner
import app.morphe.patcher.apk.ApkSigner.newKeyStore
import app.morphe.patcher.apk.ApkSigner.newPrivateKeyCertificatePair
import com.android.tools.build.apkzlib.zip.AlignmentRules
import com.android.tools.build.apkzlib.zip.StoredEntry
import com.android.tools.build.apkzlib.zip.ZFile
import com.android.tools.build.apkzlib.zip.ZFileOptions
import com.android.tools.build.apkzlib.zip.compress.DeflateExecutionCompressor
import java.io.File
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.logging.Logger
import java.util.zip.Deflater
import kotlin.time.Duration.Companion.days

/**
 * Utility functions to work with APK files.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
object ApkUtils {
    private val logger = Logger.getLogger(ApkUtils::class.java.name)

    private const val LIBRARY_EXTENSION = ".so"

    // Alignment for native libraries.
    private const val LIBRARY_ALIGNMENT = 1024 * 4

    // Alignment for all other files.
    private const val DEFAULT_ALIGNMENT = 4

    /**
     * apkzlib's default compressor runs every deflate on the calling thread, so writing the dex
     * files and the uncompiled resources is serialised on one core. [ZFile] assigns entry offsets
     * strictly in the order entries were added - it pops the pending queue only while the head's
     * compression has completed - so compressing off-thread does not affect layout. The deflate
     * level is left at apkzlib's default, since that is what determines the output bytes.
     */
    private fun newZFileOptions(executor: Executor) =
        ZFileOptions().setAlignmentRule(
            AlignmentRules.compose(
                AlignmentRules.constantForSuffix(LIBRARY_EXTENSION, LIBRARY_ALIGNMENT),
                AlignmentRules.constant(DEFAULT_ALIGNMENT),
            ),
        ).also { options ->
            options.setCompressor(
                DeflateExecutionCompressor(executor, Deflater.DEFAULT_COMPRESSION),
            )
        }

    /** Threads used to deflate entries. Kept small: each in-flight entry holds a buffer. */
    private val compressionThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /**
     * Applies the [PatcherResult] to the given [apkFile].
     *
     * The order of operation is as follows:
     * 1. Merge resources.apk compiled by AAPT over the target's existing resources.
     * 2. Write raw resources.
     * 3. Delete resources staged for deletion.
     * 4. Write patched dex files.
     * 5. Realign the APK.
     *
     * @param apkFile The file to apply the patched files to.
     */
    fun PatcherResult.applyTo(apkFile: File) {
        val compressionExecutor = Executors.newFixedThreadPool(compressionThreads) { runnable ->
            Thread(runnable, "apk-deflate").apply { isDaemon = true }
        }
        try {
            applyTo(apkFile, newZFileOptions(compressionExecutor))
        } finally {
            compressionExecutor.shutdown()
        }
    }

    private fun PatcherResult.applyTo(apkFile: File, zFileOptions: ZFileOptions) {
        ZFile.openReadWrite(apkFile, zFileOptions).use { targetApkZFile ->
            resources.let { resources ->
                // Add resources compiled by AAPT.
                resources.resourcesApk?.let { resourcesApk ->
                    ZFile.openReadOnly(resourcesApk).use { resourcesApkZFile ->
                        // Deliberately no blanket deletion of the target's res/ entries here.
                        //
                        // The encoder only writes resources.apk entries it actually had to rebuild;
                        // everything it could reuse verbatim is left in the target APK, so deleting
                        // res/ wholesale would throw away exactly the entries we avoided rebuilding.
                        // mergeFrom below replaces the ones that were rebuilt.
                        //
                        // A renamed resource leaves its old entry behind as dead weight rather than
                        // breaking anything, since the rebuilt table only refers to the new name.
                        // Resources genuinely staged for removal are handled by deleteResources.

                        targetApkZFile.mergeFrom(resourcesApkZFile) { entry ->
                            // Filter any dex files in case they were packaged inside resources.apk for some reason.
                            (entry.startsWith("classes") && entry.endsWith(".dex"))

                            // Filter any files that are already marked for deletion so we don't needlessly copy them,
                            // in case they made it into the resources.apk.
                            || entry in resources.deleteResources
                        }
                    }
                }

                // Add resources not compiled by AAPT.
                resources.otherResources?.let { otherResources ->
                    targetApkZFile.addAllRecursively(otherResources) { file ->
                        file.relativeTo(otherResources).invariantSeparatorsPath !in resources.doNotCompress
                    }
                }

                // Delete resources that were staged for deletion.
                if (resources.deleteResources.isNotEmpty()) {
                    targetApkZFile.entries().filter { entry ->
                        entry.centralDirectoryHeader.name in resources.deleteResources
                    }.forEach(StoredEntry::delete)
                }
            }

            // Run this after resource updates to ensure our dex files don't get overwritten.
            dexFiles.forEach { dexFile ->
                targetApkZFile.add(dexFile.name, dexFile.stream)
                dexFile.stream.close()
            }

            logger.info("Aligning APK")

            // Entries whose compression is still in flight have not been given a place in the
            // file yet, and realign() requires every entry to have one. With apkzlib's default
            // same-thread compressor that is implicit (add() compresses inline); once entries are
            // compressed off-thread it has to be made explicit, or realign() fails a Verify check.
            targetApkZFile.update()

            targetApkZFile.realign()

            logger.fine("Writing changes")
        }
    }

    /**
     * Creates a new private key and certificate pair and saves it to the keystore in [keyStoreDetails].
     *
     * @param privateKeyCertificatePairDetails The details for the private key and certificate pair.
     * @param keyStoreDetails The details for the keystore.
     *
     * @return The newly created private key and certificate pair.
     */
    private fun newPrivateKeyCertificatePair(
        privateKeyCertificatePairDetails: PrivateKeyCertificatePairDetails,
        keyStoreDetails: KeyStoreDetails,
    ) = newPrivateKeyCertificatePair(
        privateKeyCertificatePairDetails.commonName,
        privateKeyCertificatePairDetails.validUntil,
    ).also { privateKeyCertificatePair ->
        newKeyStore(
            setOf(
                ApkSigner.KeyStoreEntry(
                    keyStoreDetails.alias,
                    keyStoreDetails.password,
                    privateKeyCertificatePair,
                ),
            ),
        ).store(
            keyStoreDetails.keyStore.outputStream(),
            keyStoreDetails.keyStorePassword?.toCharArray(),
        )
    }

    /**
     * Reads the private key and certificate pair from an existing keystore.
     *
     * @param keyStoreDetails The details for the keystore.
     *
     * @return The private key and certificate pair.
     */
    private fun readPrivateKeyCertificatePairFromKeyStore(
        keyStoreDetails: KeyStoreDetails,
    ) = ApkSigner.readPrivateKeyCertificatePair(
        ApkSigner.readKeyStore(
            keyStoreDetails.keyStore.inputStream(),
            keyStoreDetails.keyStorePassword,
        ),
        keyStoreDetails.alias,
        keyStoreDetails.password,
    )

    /**
     * Signs [inputApkFile] with the given options and saves the signed apk to [outputApkFile].
     * If [KeyStoreDetails.keyStore] does not exist,
     * a new private key and certificate pair will be created and saved to the keystore.
     *
     * @param inputApkFile The apk file to sign.
     * @param outputApkFile The file to save the signed apk to.
     * @param signer The name of the signer.
     * @param keyStoreDetails The details for the keystore.
     */
    fun signApk(
        inputApkFile: File,
        outputApkFile: File,
        signer: String,
        keyStoreDetails: KeyStoreDetails,
    ) = newApkSigner(
        signer,
        if (keyStoreDetails.keyStore.exists()) {
            readPrivateKeyCertificatePairFromKeyStore(keyStoreDetails)
        } else {
            newPrivateKeyCertificatePair(PrivateKeyCertificatePairDetails(), keyStoreDetails)
        },
    ).signApk(inputApkFile, outputApkFile)

    /**
     * Details for a keystore.
     *
     * @param keyStore The file to save the keystore to.
     * @param keyStorePassword The password for the keystore.
     * @param alias The alias of the key store entry to use for signing.
     * @param password The password for recovering the signing key.
     */
    class KeyStoreDetails(
        val keyStore: File,
        val keyStorePassword: String? = null,
        val alias: String,
        val password: String,
    )

    /**
     * Details for a private key and certificate pair.
     *
     * @param commonName The common name for the certificate saved in the keystore.
     * @param validUntil The date until which the certificate is valid.
     */
    class PrivateKeyCertificatePairDetails(
        val commonName: String = "Morphe",
        val validUntil: Date = Date(System.currentTimeMillis() + (365.days * 8).inWholeMilliseconds * 24),
    )
}

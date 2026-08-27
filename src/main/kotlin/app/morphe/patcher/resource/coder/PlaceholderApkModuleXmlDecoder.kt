/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.coder

import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkModuleXmlDecoder
import com.reandroid.apk.ApkUtil
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.coder.xml.XmlCoder
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.xml.XMLFactory
import com.reandroid.xml.XmlIndentingSerializer
import java.io.File

/**
 * A file the decoder skipped, to be extracted or decoded from the original APK only if a patch
 * asks for it.
 *
 * @param originalPath The path of the entry inside the original APK archive.
 * @param isBinaryXml Whether the entry is a binary XML resource that must be decoded to text,
 * as opposed to a file whose bytes are copied as they are.
 * @param packageId The id of the resource package the entry belongs to, used to resolve resource
 * references while decoding binary XML. -1 for files outside the resource table.
 */
internal class PendingResourceFile(
    val originalPath: String,
    val isBinaryXml: Boolean,
    val packageId: Int,
)

/**
 * An [ApkModuleXmlDecoder] that defers the bulk of the extraction work.
 *
 * The resource table (values XML files, public.xml, package.json), the manifest and the various
 * metadata files are decoded eagerly, exactly like [ApkModuleXmlDecoder] would. File-backed
 * resources (layouts, drawables, raw files) and files at the APK root (libraries, assets) are
 * written as zero-byte placeholder files instead, and recorded in [pendingFiles].
 *
 * Placeholders keep every downstream directory scan working unchanged: the encoder still derives
 * the resource table entries for file resources from the paths on disk, file snapshots still
 * detect additions, modifications and deletions, and native library stripping still deletes the
 * (now empty) files. [ArsclibResourceCoder] fills in the real content on first access through
 * `getFile`, and entries whose placeholders were never materialized are copied into the output
 * APK straight from the original archive.
 */
internal class PlaceholderApkModuleXmlDecoder(
    apkModule: ApkModule,
    private val pendingFiles: MutableMap<File, PendingResourceFile>,
) : ApkModuleXmlDecoder(apkModule) {

    private val decodedEntries = HashMap<Int, MutableSet<ResConfig>>()

    override fun decodeResourceTable(mainDirectory: File) {
        val tableBlock = getApkModule().tableBlock

        // Decode package metadata and the pinned resource ids eagerly - the processors and the
        // encoder read them.
        tableBlock.listPackages().forEach { packageBlock ->
            val packageDirectory = packageDirectory(mainDirectory, packageBlock)
            packageBlock.toJson(false)
                .write(packageDirectory.resolve(PackageBlock.JSON_FILE_NAME))
            packageBlock.serializePublicXml(publicXmlFile(packageDirectory))
        }
        if (tableBlock.size() == 0) {
            val packageDirectory = mainDirectory
                .resolve(TableBlock.DIRECTORY_NAME)
                .resolve(PackageBlock.DIRECTORY_NAME_PREFIX + "1")
            tableBlock.pickOrEmptyPackage().serializePublicXml(publicXmlFile(packageDirectory))
        }
        addDecodedPath(TableBlock.FILE_NAME)

        // File-backed resources become placeholders. The decoded path is still computed and
        // assigned, so path-map.json records the original archive path of every renamed file.
        getApkModule().listResFiles().forEach { resFile ->
            val entry = resFile.pickOne()
            val packageBlock = entry.packageBlock
            val originalPath = resFile.inputSource.alias
            val decodedPath = resFile.buildPath(PackageBlock.RES_DIRECTORY_NAME)
            resFile.filePath = decodedPath

            val outFile = packageDirectory(mainDirectory, packageBlock)
                .resolve(decodedPath.replace('/', File.separatorChar))
            writePlaceholder(outFile)
            pendingFiles[outFile.absoluteFile] =
                PendingResourceFile(originalPath, resFile.isBinaryXml, packageBlock.id)

            addDecodedEntry(entry)
            // The renamed alias, matching what ApkModuleXmlDecoder records: extractRootFiles
            // checks the current alias of every input source against the decoded paths.
            addDecodedPath(resFile.inputSource.alias)
        }

        // The values (the resource table itself) are always decoded eagerly.
        XmlCoder.getInstance().VALUES_XML.decodeTable(
            mainDirectory.resolve(TableBlock.DIRECTORY_NAME),
            tableBlock,
            this,
        )

        decodeOverlayable(mainDirectory, tableBlock)
    }

    override fun extractRootFiles(mainDirectory: File) {
        val rootDirectory = mainDirectory.resolve(ApkUtil.ROOT_NAME)
        getApkModule().inputSources.forEach { inputSource ->
            if (containsDecodedPath(inputSource.alias)) return@forEach
            val file = inputSource.toFile(rootDirectory)
            writePlaceholder(file)
            pendingFiles[file.absoluteFile] =
                PendingResourceFile(inputSource.alias, isBinaryXml = false, packageId = -1)
            addDecodedPath(inputSource.alias)
        }
    }

    /**
     * The predicate [XmlCoder.ValuesXml.decodeTable] uses to skip table entries that are already
     * represented by a decoded file. Mirrors [ApkModuleXmlDecoder.test], which reads a private
     * map this class cannot fill.
     */
    override fun test(entry: Entry): Boolean =
        decodedEntries[entry.resourceId]?.contains(entry.resConfig) == true

    private fun addDecodedEntry(entry: Entry) {
        if (entry.isNull) return
        decodedEntries.getOrPut(entry.resourceId) { HashSet() }.add(entry.resConfig)
    }

    private fun decodeOverlayable(mainDirectory: File, tableBlock: TableBlock) {
        tableBlock.forEach { packageBlock ->
            val overlayableList = packageBlock.overlayableList
            if (overlayableList.isEmpty) return@forEach
            val file = packageDirectory(mainDirectory, packageBlock)
                .resolve(PackageBlock.RES_DIRECTORY_NAME)
                .resolve(PackageBlock.VALUES_DIRECTORY_NAME)
                .resolve("overlayable.xml")
            val serializer = XmlIndentingSerializer(XMLFactory.newSerializer(file))
            XMLFactory.setEnableIndentAttributes(serializer, false)
            overlayableList.serialize(serializer)
        }
    }

    private fun packageDirectory(mainDirectory: File, packageBlock: PackageBlock) =
        mainDirectory
            .resolve(TableBlock.DIRECTORY_NAME)
            .resolve(packageBlock.buildDecodeDirectoryName())

    private fun publicXmlFile(packageDirectory: File) =
        packageDirectory
            .resolve(PackageBlock.RES_DIRECTORY_NAME)
            .resolve(PackageBlock.VALUES_DIRECTORY_NAME)
            .resolve(PackageBlock.PUBLIC_XML)

    private fun writePlaceholder(file: File) {
        file.parentFile?.mkdirs()
        if (!file.createNewFile()) {
            // Overwrite like a real extraction would, if two entries map to the same path.
            file.writeBytes(ByteArray(0))
        }
    }
}

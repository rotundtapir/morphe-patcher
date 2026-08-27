/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import app.morphe.patcher.resource.copyNamespaces
import app.morphe.patcher.resource.parseXml
import app.morphe.patcher.resource.writeXml
import app.morphe.patcher.util.FileUtils.safelyMoveTo
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.util.logging.Logger

/**
 * Splits every `strings.xml` file of every values directory into styled and plain halves right before the encoder
 * scans the working directory.
 *
 * The encoder preloads styled strings by DOM-loading every file named `strings.xml`, and its DOM
 * inserts children in O(n) each, so a locale file with thousands of strings costs O(n²) even
 * though only a handful of them are styled. Moving the plain strings into a sibling `string.xml`
 * (which encodes into the same `string` type block, but is not matched by the preload) leaves the
 * preload with only the styled strings that it is actually after.
 *
 * The split is purely an encoder-input transform: entry names, types, and configurations are
 * unchanged, so the encoded resource table is the same as without the split.
 */
internal class StringsXmlStyledSplitProcessor(
    private val packageDirectories: Map<String, File>,
) {
    private val logger = Logger.getLogger(this::class.java.name)

    fun process() {
        var movedTotal = 0
        var fileCount = 0

        packageDirectories.values.forEach { packageDirectory ->
            packageDirectory.resolve("res").listFiles { file: File ->
                file.isDirectory && (file.name == "values" || file.name.startsWith("values-"))
            }?.forEach { valuesDirectory ->
                val stringsXml = valuesDirectory.resolve("strings.xml")
                if (!stringsXml.isFile) return@forEach

                // A patch created its own string.xml: leave the directory untouched rather than merge.
                if (valuesDirectory.resolve("string.xml").exists()) return@forEach

                val moved = split(stringsXml)
                if (moved > 0) {
                    movedTotal += moved
                    fileCount++
                }
            }
        }

        if (fileCount > 0) {
            logger.info("Moved $movedTotal plain strings out of $fileCount strings.xml files for encoding")
        }
    }

    /**
     * Splits [stringsXml] and returns the number of plain string elements moved to `string.xml`.
     * Leaves the file untouched when nothing needs moving.
     */
    private fun split(stringsXml: File): Int {
        val elements = mutableListOf<RecordedElement>()
        var rootAttributes = emptyList<RecordedAttribute>()

        stringsXml.parseXml { parser ->
            var event = parser.eventType
            var current: RecordedElement? = null

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.depth == 1) {
                            rootAttributes = recordAttributes(parser)
                        } else {
                            val element = current
                            if (element == null) {
                                current = RecordedElement(parser.name, recordAttributes(parser))
                            } else {
                                element.styled = true
                                element.events += RecordedEvent(
                                    EventKind.START_TAG,
                                    parser.name,
                                    attributes = recordAttributes(parser),
                                )
                            }
                        }
                    }

                    XmlPullParser.TEXT ->
                        current?.events?.add(RecordedEvent(EventKind.TEXT, parser.text))

                    XmlPullParser.CDSECT ->
                        current?.events?.add(RecordedEvent(EventKind.CDSECT, parser.text))

                    XmlPullParser.END_TAG -> {
                        val element = current
                        if (element != null) {
                            if (parser.depth == 2) {
                                elements += element
                                current = null
                            } else {
                                element.events += RecordedEvent(EventKind.END_TAG, parser.name)
                            }
                        }
                    }
                }

                event = parser.next()
            }
        }

        val plain = elements.filter { it.name == "string" && !it.styled }
        // Nothing worth moving: leave the file untouched.
        if (plain.isEmpty()) return 0

        val kept = elements.filter { it.name != "string" || it.styled }

        val plainFile = File(stringsXml.parentFile, "string.xml.tmp")
        val keptFile = File(stringsXml.parentFile, "strings.xml.tmp")

        writeValuesFile(plainFile, rootAttributes, plain)
        writeValuesFile(keptFile, rootAttributes, kept)

        plainFile.safelyMoveTo(File(stringsXml.parentFile, "string.xml"))
        keptFile.safelyMoveTo(stringsXml)

        return plain.size
    }

    private fun writeValuesFile(
        file: File,
        rootAttributes: List<RecordedAttribute>,
        elements: List<RecordedElement>,
    ) {
        file.writeXml { serializer ->
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "resources")
            rootAttributes.forEach { serializer.attribute(it.namespace, it.name, it.value) }

            elements.forEach { element -> element.replay(serializer) }

            serializer.endTag(null, "resources")
            serializer.endDocument()
        }
    }

    private fun recordAttributes(parser: XmlPullParser): List<RecordedAttribute> =
        (0 until parser.attributeCount).map { i ->
            RecordedAttribute(
                parser.getAttributeNamespace(i).ifEmpty { null },
                parser.getAttributeName(i),
                parser.getAttributeValue(i),
            )
        }

    private class RecordedAttribute(val namespace: String?, val name: String, val value: String)

    private enum class EventKind { START_TAG, TEXT, CDSECT, END_TAG }

    private class RecordedEvent(
        val kind: EventKind,
        val value: String,
        val attributes: List<RecordedAttribute> = emptyList(),
    )

    private class RecordedElement(val name: String, val attributes: List<RecordedAttribute>) {
        var styled = false
        val events = mutableListOf<RecordedEvent>()

        fun replay(serializer: XmlSerializer) {
            serializer.startTag(null, name)
            attributes.forEach { serializer.attribute(it.namespace, it.name, it.value) }
            events.forEach { event ->
                when (event.kind) {
                    EventKind.START_TAG -> {
                        serializer.startTag(null, event.value)
                        event.attributes.forEach { serializer.attribute(it.namespace, it.name, it.value) }
                    }
                    EventKind.TEXT -> serializer.text(event.value)
                    EventKind.CDSECT -> serializer.cdsect(event.value)
                    EventKind.END_TAG -> serializer.endTag(null, event.value)
                }
            }
            serializer.endTag(null, name)
        }
    }
}

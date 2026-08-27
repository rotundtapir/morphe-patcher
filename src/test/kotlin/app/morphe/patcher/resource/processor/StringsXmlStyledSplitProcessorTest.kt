/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patcher
 */

package app.morphe.patcher.resource.processor

import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class StringsXmlStyledSplitProcessorTest {
    @TempDir
    lateinit var tempDir: File

    private fun valuesDir(name: String) = tempDir.resolve("pkg/res/$name").apply { mkdirs() }

    private fun textOf(file: File, tag: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    @Test
    fun `plain strings move to string xml and styled strings stay`() {
        val strings = valuesDir("values-de").resolve("strings.xml").apply {
            writeText(
                """<?xml version="1.0" encoding="utf-8"?>
                <resources xmlns:tools="http://schemas.android.com/tools">
                    <!-- comment -->
                    <string name="plain">Hello &amp; "quoted" \n</string>
                    <string name="spaces">  keep  spaces  </string>
                    <string name="styled">Bold <b>text</b> and <a href="x">link</a></string>
                    <string name="empty"></string>
                    <plurals name="p"><item quantity="one">one</item></plurals>
                </resources>""".trimIndent(),
            )
        }

        StringsXmlStyledSplitProcessor(mapOf("pkg" to tempDir.resolve("pkg"))).process()

        val plainFile = strings.resolveSibling("string.xml")
        assertTrue(plainFile.isFile)

        val plain = textOf(plainFile, "string")
        assertEquals(setOf("plain", "spaces", "empty"), plain.keys)
        assertEquals("Hello & \"quoted\" \\n", plain["plain"])
        assertEquals("  keep  spaces  ", plain["spaces"])
        assertEquals("", plain["empty"])

        val kept = textOf(strings, "string")
        assertEquals(setOf("styled"), kept.keys)
        assertEquals("Bold text and link", kept["styled"])
        assertEquals(1, textOf(strings, "plurals").size, "non-string elements stay in strings.xml")
        assertTrue(strings.readText().contains("<b>text</b>"), "styled markup is preserved")
    }

    @Test
    fun `a file without plain strings is left untouched`() {
        val strings = valuesDir("values").resolve("strings.xml").apply {
            writeText("""<resources><string name="s">a<b>b</b></string></resources>""")
        }
        val before = strings.readText()

        StringsXmlStyledSplitProcessor(mapOf("pkg" to tempDir.resolve("pkg"))).process()

        assertEquals(before, strings.readText())
        assertFalse(strings.resolveSibling("string.xml").exists())
    }

    @Test
    fun `an existing string xml disables the split for that directory`() {
        val dir = valuesDir("values-fr")
        dir.resolve("string.xml").writeText("""<resources><string name="own">x</string></resources>""")
        val strings = dir.resolve("strings.xml").apply {
            writeText("""<resources><string name="s">plain</string></resources>""")
        }
        val before = strings.readText()

        StringsXmlStyledSplitProcessor(mapOf("pkg" to tempDir.resolve("pkg"))).process()

        assertEquals(before, strings.readText())
        assertEquals(setOf("own"), textOf(dir.resolve("string.xml"), "string").keys)
    }

    @Test
    fun `directories that are not values directories are ignored`() {
        val strings = valuesDir("xml").resolve("strings.xml").apply {
            writeText("""<resources><string name="s">plain</string></resources>""")
        }

        StringsXmlStyledSplitProcessor(mapOf("pkg" to tempDir.resolve("pkg"))).process()

        assertFalse(strings.resolveSibling("string.xml").exists())
    }
}

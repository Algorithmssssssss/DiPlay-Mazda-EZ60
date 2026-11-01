package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The EZ-60 fork ships English and Khmer only (`:mobile` localeFilters). Its own screens (strings_ez60*.xml) are
 * translated into Khmer; everything else shows English in Khmer until it is translated.
 */
class Ez60KhmerTranslationsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test fun everyEz60StringHasAKhmerTranslationWithTheSamePlaceholders() {
        val english = strings(File(res, "values"))
        val khmer = strings(File(res, "values-km"))
        assertEquals("Khmer EZ-60 keys", english.keys, khmer.keys)
        for ((name, text) in english) {
            assertEquals(name, placeholders(text), placeholders(khmer.getValue(name)))
            assertTrue("$name is not translated", khmer.getValue(name).isNotBlank())
        }
    }

    // A label marked experimental in English carries Khmer's own form of the suffix.
    @Test fun experimentalLabelsUseTheKhmerSuffix() {
        val english = strings(File(res, "values"))
        val khmer = strings(File(res, "values-km"))
        for ((name, text) in english.filterValues { it.endsWith(" (experimental)") }) {
            assertTrue(name, khmer.getValue(name).endsWith(" (ពិសោធន៍)"))
        }
    }

    @Test fun theForksOtherLanguagesHaveNoEz60Files() {
        val others = res.listFiles { file -> file.name.startsWith("values-") && file.name != "values-km" }!!
            .flatMap { folder -> folder.listFiles { file -> file.name.startsWith("strings_ez60") }!!.toList() }
        assertEquals(emptyList<File>(), others)
    }

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.toSet()

    private fun strings(folder: File): Map<String, String> =
        folder.listFiles { file -> file.extension == "xml" && file.name.startsWith("strings_ez60") }!!
            .flatMap { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    .getElementsByTagName("string")
                (0 until nodes.length).map { nodes.item(it) }
                    .map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
            }.toMap()
}

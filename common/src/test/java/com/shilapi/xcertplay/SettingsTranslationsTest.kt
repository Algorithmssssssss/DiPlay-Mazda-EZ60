package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// Lint does not check this library module's translations, so Settings copy is guarded here.
class SettingsTranslationsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%\\d+\\$[sd]")

    @Test fun everySettingsStringIsTranslatedWithTheSamePlaceholders() {
        // The EZ-60 fork's own strings are in English and Khmer only (Ez60KhmerTranslationsTest); Khmer has no others.
        val base = strings(File(res, "values"), upstreamOnly = true).filterKeys { it.startsWith("settings_") }
        val locales = res.listFiles { file ->
            file.name.matches(Regex("values-[a-z]{2}(-r[A-Z]{2})?")) && file.name != "values-km"
        }!!
        for (locale in locales) {
            val translated = strings(locale)
            assertEquals(locale.name, emptySet<String>(), base.keys - translated.keys)
            for ((name, text) in base) {
                assertEquals("${locale.name}/$name", placeholders(text), placeholders(translated.getValue(name)))
            }
        }
    }

    private fun placeholders(text: String) = placeholder.findAll(text).map { it.value }.toSet()

    private fun strings(folder: File, upstreamOnly: Boolean = false): Map<String, String> = folder.listFiles { file ->
        file.extension == "xml" && !(upstreamOnly && file.name.startsWith("strings_ez60"))
    }!!
        .flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it) }.map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
        }.toMap()
}

package com.a0soft.gphone.camonitor.i18n

import com.a0soft.gphone.camonitor.ui.common.LocaleHelper
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.regex.Pattern
import javax.xml.parsers.DocumentBuilderFactory

class LocaleResourcesTest {

    private val resDir = File("src/main/res")

    @Test
    fun testLocalesConfigXmlExistsAndValid() {
        val localesConfigFile = File(resDir, "xml/locales_config.xml")
        assertTrue("locales_config.xml must exist", localesConfigFile.exists())

        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = db.parse(localesConfigFile)
        val root = doc.documentElement
        assertEquals("Root element should be locale-config", "locale-config", root.tagName)

        val localeNodes = root.getElementsByTagName("locale")
        assertTrue("Should have at least 2 locales configured", localeNodes.length >= 2)

        val declaredLocales = mutableListOf<String>()
        for (i in 0 until localeNodes.length) {
            val element = localeNodes.item(i) as Element
            declaredLocales.add(element.getAttribute("android:name"))
        }

        assertTrue("Should declare en locale", declaredLocales.contains("en"))
        assertTrue("Should declare zh-TW locale", declaredLocales.contains("zh-TW"))
        assertTrue("Should declare ja locale", declaredLocales.contains("ja"))
    }

    @Test
    fun testStringsKeyParityBetweenEnglishAndTraditionalChinese() {
        val defaultStringsFile = File(resDir, "values/strings.xml")
        val zhHantStringsFile = File(resDir, "values-b+zh+Hant/strings.xml")

        assertTrue("Default strings.xml must exist", defaultStringsFile.exists())
        assertTrue("values-b+zh+Hant/strings.xml must exist", zhHantStringsFile.exists())

        val defaultStrings = parseStringsXml(defaultStringsFile)
        val zhHantStrings = parseStringsXml(zhHantStringsFile)

        // 1. Verify all default keys exist in Traditional Chinese
        val missingInZhHant = defaultStrings.keys - zhHantStrings.keys
        assertTrue("Missing keys in values-b+zh+Hant: $missingInZhHant", missingInZhHant.isEmpty())

        // 2. Verify all Traditional Chinese keys exist in default strings
        val extraInZhHant = zhHantStrings.keys - defaultStrings.keys
        assertTrue("Extra keys in values-b+zh+Hant not in default: $extraInZhHant", extraInZhHant.isEmpty())
    }

    @Test
    fun testStringsKeyParityBetweenEnglishAndJapanese() {
        val defaultStringsFile = File(resDir, "values/strings.xml")
        val jaStringsFile = File(resDir, "values-ja/strings.xml")

        assertTrue("Default strings.xml must exist", defaultStringsFile.exists())
        assertTrue("values-ja/strings.xml must exist", jaStringsFile.exists())

        val defaultStrings = parseStringsXml(defaultStringsFile)
        val jaStrings = parseStringsXml(jaStringsFile)

        val missingInJa = defaultStrings.keys - jaStrings.keys
        assertTrue("Missing keys in values-ja: $missingInJa", missingInJa.isEmpty())

        val extraInJa = jaStrings.keys - defaultStrings.keys
        assertTrue("Extra keys in values-ja not in default: $extraInJa", extraInJa.isEmpty())
    }

    @Test
    fun testPlaceholderConsistency() {
        val defaultStrings = parseStringsXml(File(resDir, "values/strings.xml"))
        val zhHantStrings = parseStringsXml(File(resDir, "values-b+zh+Hant/strings.xml"))
        val jaStrings = parseStringsXml(File(resDir, "values-ja/strings.xml"))

        val placeholderPattern = Pattern.compile("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z%])")

        defaultStrings.forEach { (key, defaultValue) ->
            val zhHantValue = zhHantStrings[key]
            assertNotNull("Translation must exist in values-b+zh+Hant for $key", zhHantValue)
            val defaultPlaceholders = extractPlaceholders(placeholderPattern, defaultValue)
            val zhHantPlaceholders = extractPlaceholders(placeholderPattern, zhHantValue!!)
            assertEquals(
                "Placeholder mismatch in values-b+zh+Hant for key: $key (en: $defaultValue, zh: $zhHantValue)",
                defaultPlaceholders,
                zhHantPlaceholders
            )

            val jaValue = jaStrings[key]
            assertNotNull("Translation must exist in values-ja for $key", jaValue)
            val jaPlaceholders = extractPlaceholders(placeholderPattern, jaValue!!)
            assertEquals(
                "Placeholder mismatch in values-ja for key: $key (en: $defaultValue, ja: $jaValue)",
                defaultPlaceholders,
                jaPlaceholders
            )
        }
    }

    @Test
    fun testLocaleHelperConstants() {
        assertEquals("", LocaleHelper.LANGUAGE_FOLLOW_SYSTEM)
        assertEquals("en", LocaleHelper.LANGUAGE_EN)
        assertEquals("zh-TW", LocaleHelper.LANGUAGE_ZH_TW)
        assertEquals("ja", LocaleHelper.LANGUAGE_JA)
    }

    private fun extractPlaceholders(pattern: Pattern, text: String): List<String> {
        val matcher = pattern.matcher(text)
        val matches = mutableListOf<String>()
        while (matcher.find()) {
            matches.add(matcher.group())
        }
        return matches
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = db.parse(file)
        val stringNodes = doc.getElementsByTagName("string")
        for (i in 0 until stringNodes.length) {
            val element = stringNodes.item(i) as Element
            val name = element.getAttribute("name")
            val content = element.textContent
            map[name] = content
        }
        return map
    }
}

package com.streamvault.app.update

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/**
 * The update download notification text must come from resources and be translated (not English) in every
 * locale the app advertises in locales_config.xml.
 */
class AppUpdateStringsLocaleParityTest {

    private val resDir = File("src/main/res")
    private val localesConfig = File(resDir, "xml/locales_config.xml")
    private val installerSource = File("src/main/java/com/streamvault/app/update/AppUpdateInstaller.kt")

    private val keys = listOf("app_update_download_notification_description")

    @Test
    fun updateStringsAreTranslatedInEveryAdvertisedLocale() {
        val defaults = readStrings(File(resDir, "values/strings.xml"))
        val problems = mutableListOf<String>()
        keys.filter { defaults[it].isNullOrBlank() }.forEach { problems += "values: missing $it" }
        advertisedLocales().filter { it != "en" }.forEach { locale ->
            val folder = "values-" + locale.replace("-", "-r")
            val file = File(resDir, "$folder/strings.xml")
            if (!file.isFile) {
                problems += "$folder: no strings.xml"
                return@forEach
            }
            val strings = readStrings(file)
            keys.forEach { key ->
                val value = strings[key]
                when {
                    value.isNullOrBlank() -> problems += "$folder: missing $key"
                    value == defaults[key] -> problems += "$folder: $key is still English"
                }
            }
        }
        assertWithMessage("Update string locale problems").that(problems).isEmpty()
    }

    @Test
    fun installerNoLongerHardCodesTheNotificationText() {
        assertWithMessage("AppUpdateInstaller must read the notification text from resources")
            .that(installerSource.readText())
            .doesNotContain("\"Downloading the latest StreamVault update\"")
    }

    private fun advertisedLocales(): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(localesConfig)
        val nodes = document.getElementsByTagName("locale")
        return (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("android:name") }
    }

    private fun readStrings(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val element = nodes.item(index) as Element
            element.getAttribute("name") to element.textContent
        }
    }
}

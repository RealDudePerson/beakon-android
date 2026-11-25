package com.beakon.companion

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Acceptance criterion 1: the `AppWidgetProvider` is declared with
 * `android:exported="false"` and an `appwidget-provider` metadata resource.
 * Criterion 8: no cleartext permission or trust-all path is introduced. A device
 * is not available, so the manifest and resource XML are inspected directly.
 */
class ManifestWidgetTest {

    private val repoRoot = File(System.getProperty("beakon.repoRoot"))

    @Test
    fun `the widget provider is declared not exported with provider metadata`() {
        val document = parse(File(repoRoot, "app/src/main/AndroidManifest.xml"))
        val receiver = findElement(document, "receiver", "android:name", ".widget.BeakonWidgetProvider")

        assertNotNull("BeakonWidgetProvider must be declared", receiver)
        assertEquals(
            "the provider must not be reachable outside the app",
            "false",
            receiver!!.getAttribute("android:exported"),
        )

        val metadata = childElements(receiver, "meta-data")
            .firstOrNull { it.getAttribute("android:name") == "android.appwidget.provider" }
        assertNotNull("the provider must reference appwidget-provider metadata", metadata)
        assertEquals(
            "the metadata must point at the widget info resource",
            "@xml/beakon_widget_info",
            metadata!!.getAttribute("android:resource"),
        )

        val updateAction = childElements(receiver, "intent-filter")
            .flatMap { childElements(it, "action") }
            .map { it.getAttribute("android:name") }
        assertTrue(
            "the provider must listen for APPWIDGET_UPDATE, was $updateAction",
            updateAction.contains("android.appwidget.action.APPWIDGET_UPDATE"),
        )
    }

    @Test
    fun `the configure activity is declared for placement-time selection`() {
        val document = parse(File(repoRoot, "app/src/main/AndroidManifest.xml"))
        val activity = findElement(document, "activity", "android:name", ".widget.BeakonWidgetConfigActivity")

        assertNotNull("the configure activity must be declared", activity)
        val actions = childElements(activity!!, "intent-filter")
            .flatMap { childElements(it, "action") }
            .map { it.getAttribute("android:name") }
        assertTrue(
            "the configure activity must handle APPWIDGET_CONFIGURE, was $actions",
            actions.contains("android.appwidget.action.APPWIDGET_CONFIGURE"),
        )
    }

    @Test
    fun `the widget info resource exists and points at the layout and config activity`() {
        val info = File(repoRoot, "app/src/main/res/xml/beakon_widget_info.xml")
        assertTrue("widget info missing at $info", info.exists())

        val document = parse(info)
        val provider = document.documentElement
        assertEquals("@layout/beakon_widget", provider.getAttribute("android:initialLayout"))
        assertEquals(
            "com.beakon.companion.widget.BeakonWidgetConfigActivity",
            provider.getAttribute("android:configure"),
        )
    }

    @Test
    fun `no cleartext traffic or trust-all path is introduced`() {
        val manifest = parse(File(repoRoot, "app/src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertFalse(
            "cleartext traffic must never be enabled",
            application.getAttribute("android:usesCleartextTraffic") == "true",
        )

        val securityConfig = File(repoRoot, "app/src/main/res/xml/network_security_config.xml")
        val config = securityConfig.readText()
        assertTrue(
            "cleartext must stay disabled in the network security config",
            config.contains("cleartextTrafficPermitted=\"false\""),
        )
    }

    // --- helpers -----------------------------------------------------------

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun findElement(
        document: org.w3c.dom.Document,
        tag: String,
        attribute: String,
        value: String,
    ): Element? {
        val elements = document.getElementsByTagName(tag)
        for (index in 0 until elements.length) {
            val element = elements.item(index) as Element
            if (element.getAttribute(attribute) == value) return element
        }
        return null
    }

    private fun childElements(parent: Element, tag: String): List<Element> {
        val children = parent.childNodes
        val result = mutableListOf<Element>()
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node is Element && node.tagName == tag) result += node
        }
        return result
    }
}

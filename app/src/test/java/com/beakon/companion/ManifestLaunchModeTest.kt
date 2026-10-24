package com.beakon.companion

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * P2-6: a notification tap must not stack another `MainActivity`. The launch
 * mode lives in the manifest, which cannot be unit-tested through the Android
 * runtime here, so the manifest itself is asserted.
 */
class ManifestLaunchModeTest {

    @Test
    fun `MainActivity is singleTask so notification taps reuse the instance`() {
        val manifest = File(
            System.getProperty("beakon.repoRoot"),
            "app/src/main/AndroidManifest.xml",
        )
        assertTrue("manifest missing at $manifest", manifest.exists())

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val activities = document.getElementsByTagName("activity")
        var mainActivity: Element? = null
        for (index in 0 until activities.length) {
            val activity = activities.item(index) as Element
            if (activity.getAttribute("android:name") == ".MainActivity") {
                mainActivity = activity
            }
        }

        assertNotNull("MainActivity must be declared", mainActivity)
        assertEquals(
            "a notification tap must reuse the existing activity",
            "singleTask",
            mainActivity!!.getAttribute("android:launchMode"),
        )
    }
}

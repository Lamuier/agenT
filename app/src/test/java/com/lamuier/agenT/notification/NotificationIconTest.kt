package com.lamuier.agenT.notification

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class NotificationIconTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    @Test fun islandUsesCurrentCatLauncherAndAVersionedPictureKey() {
        val icon = File(root, "res/drawable/ic_island.xml").readText()
        assertTrue(icon.contains("<vector"))
        assertTrue(icon.contains("android:strokeLineCap=\"round\""))
        assertFalse(icon.contains("ic_launcher"))
        val adapter = File(root, "java/com/lamuier/agenT/notification/XiaomiHyperIslandAdapter.kt").readText()
        assertTrue(adapter.contains("IslandArtwork.pictureReference"))
        assertFalse(adapter.contains("miui.focus.pic_agentt_app_v4"))
        assertTrue(adapter.contains("IslandArtwork.createIcon(context"))
        val artwork = File(root, "java/com/lamuier/agenT/notification/IslandArtwork.kt").readText()
        assertTrue(artwork.contains("_v7"))
        assertTrue(artwork.contains("Icon.createWithResource(context, resource)"))
        assertTrue(artwork.contains("Icon.createWithBitmap(bitmap)"))
        assertTrue(File(root, "res/drawable/ic_island_cat_running.xml").readText().contains("@mipmap/ic_launcher"))
        val renderer = File(root, "java/com/lamuier/agenT/notification/QuotaNotificationRenderer.kt").readText()
        assertTrue(renderer.contains(".setSmallIcon(R.drawable.ic_launcher_monochrome)"))
    }
    @Test fun oneShotReminderIconRetainsQuotaSilhouetteDistinctFromCatLauncher() {
        fun paths(name: String): List<String> {
            val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            val items = factory.newDocumentBuilder().parse(File(root, "res/drawable/$name.xml")).getElementsByTagName("path")
            return (0 until items.length).map { items.item(it).attributes.getNamedItem("android:pathData").nodeValue }
        }
        assertEquals(paths("ic_island"), paths("ic_notification"))
        assertNotEquals(paths("ic_launcher_monochrome"), paths("ic_notification"))
    }
}

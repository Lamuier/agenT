package com.lamuier.agenT.ui

import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resource-only verification: no emulator or Android process required. */
class LauncherIconAssetsTest {
    private val resources = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { it.isDirectory }

    @Test
    fun catArtworkHasRealTransparencyAndFitsAdaptiveSafeCircle() {
        val icon = ImageIO.read(File(resources, "drawable-nodpi/ic_launcher_cat_art.png"))
        assertEquals(icon.width, icon.height)
        assertTrue(icon.colorModel.hasAlpha())
        assertEquals(0, icon.getRGB(0, 0).ushr(24))
        assertEquals(0, icon.getRGB(icon.width - 1, icon.height - 1).ushr(24))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resources, "drawable/ic_launcher_foreground.xml"))
        val foreground = document.documentElement
        assertEquals("inset", foreground.tagName)
        for (edge in listOf("Left", "Top", "Right", "Bottom")) {
            assertEquals("20%", foreground.getAttribute("android:inset$edge"))
        }
        val bitmap = document.getElementsByTagName("bitmap").item(0)
        assertEquals("@drawable/ic_launcher_cat_art", bitmap.attributes.getNamedItem("android:src").nodeValue)
        var visiblePixels = 0
        var maximumRadius = 0.0
        for (y in 0 until icon.height) for (x in 0 until icon.width) {
            if (icon.getRGB(x, y).ushr(24) > 16) {
                visiblePixels++
                val dx = ((x + 0.5) / icon.width - 0.5) * 108.0 * 0.6
                val dy = ((y + 0.5) / icon.height - 0.5) * 108.0 * 0.6
                maximumRadius = maxOf(maximumRadius, Math.hypot(dx, dy))
            }
        }
        assertTrue("Cat must be present", visiblePixels > icon.width * icon.height / 4)
        assertTrue("Artwork must fit the central 66dp circle; radius=$maximumRadius", maximumRadius <= 33.0)
    }

    @Test
    fun adaptiveAndLegacyLaunchersShareNewForeground() {
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val adaptive = File(resources, "mipmap-anydpi-v26/$name.xml").readText()
            assertTrue(adaptive.contains("@drawable/ic_launcher_foreground"))
            assertTrue(adaptive.contains("@drawable/ic_launcher_monochrome"))
            val legacy = File(resources, "mipmap-anydpi/$name.xml").readText()
            assertTrue(legacy.contains("@drawable/ic_launcher_legacy"))
        }
        assertTrue(File(resources, "drawable/ic_launcher_legacy.xml").readText()
            .contains("@drawable/ic_launcher_foreground"))
    }
}

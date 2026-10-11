package com.lamuier.agenT.notification

import com.lamuier.agenT.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class IslandArtworkTest {
    private val root = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val names = R.drawable::class.java.fields.associate { it.getInt(null) to it.name }

    @Test fun allTwelveStylesAndStatesHaveIndependentIconsAndCacheReferences() {
        val pictures = mutableSetOf<String>()
        val icons = mutableSetOf<Int>()
        for (cat in listOf(false, true)) for (state in IslandMonitorState.entries) {
            val picture = IslandArtwork.pictureReference(state, cat)
            assertTrue(picture.startsWith("miui.focus.pic_agentt_${if (cat) "cat" else "formal"}_"))
            assertTrue(picture.endsWith("_v7"))
            assertTrue(pictures.add(picture))
            assertTrue(icons.add(IslandArtwork.iconResource(state, cat)))
        }
        assertEquals(IslandMonitorState.entries.size * 2, pictures.size)
        assertEquals(IslandMonitorState.entries.size * 2, icons.size)
    }

    @Test fun multipleAttentionGetsASeparateCacheKeyWithoutKeyChurnForEveryCount() {
        for (cat in listOf(false, true)) for (state in IslandMonitorState.entries) {
            val single = IslandArtwork.pictureReference(state, cat)
            val multiple = IslandArtwork.pictureReference(state, cat, 1)
            assertNotEquals(single, multiple)
            assertTrue(multiple.endsWith("_multi_v7"))
            assertEquals(multiple, IslandArtwork.pictureReference(state, cat, 6))
            assertEquals(single, IslandArtwork.pictureReference(state, cat, -1))
        }
    }

    @Test fun iconsRetainAppIdentityAndDifferByShapeNotOnlyColor() {
        val badges = mutableSetOf<String>()
        val badgePaths = mutableSetOf<String>()
        for (cat in listOf(false, true)) for (state in IslandMonitorState.entries) {
            val name = names.getValue(IslandArtwork.iconResource(state, cat))
            val file = File(root, "drawable/$name.xml")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            assertEquals("layer-list", document.documentElement.tagName)
            val items = document.getElementsByTagName("item")
            assertEquals("32dp", items.item(0).attributes.getNamedItem("android:width").nodeValue)
            assertEquals(if (cat) "@mipmap/ic_launcher" else "@drawable/ic_island_formal_mark",
                items.item(1).attributes.getNamedItem("android:drawable").nodeValue)
            val badge = items.item(2).attributes.getNamedItem("android:drawable").nodeValue.removePrefix("@drawable/")
            assertEquals("bottom|right", items.item(2).attributes.getNamedItem("android:gravity").nodeValue)
            badges += badge
            val paths = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root, "drawable/$badge.xml"))
                .getElementsByTagName("path")
            badgePaths += (0 until paths.length).joinToString("|") { paths.item(it).attributes.getNamedItem("android:pathData").nodeValue }
        }
        assertEquals(IslandMonitorState.entries.size, badges.size)
        assertEquals(IslandMonitorState.entries.size, badgePaths.size)
    }
}

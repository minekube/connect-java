package com.minekube.connect.share.fabric.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveShareLayoutTest {
    @Test
    fun `compact screens keep content and footer separated`() {
        val layout = AdaptiveShareLayout.friends(
            screenWidth = 320,
            screenHeight = 240,
        )

        assertEquals(296, layout.contentWidth)
        assertEquals(12, layout.contentX)
        assertTrue(layout.visibleRows >= 3)
        assertTrue(layout.rowsBottom <= layout.messageY)
        assertTrue(layout.messageY < layout.footerTop)
        assertTrue(layout.footerBottom <= 240 - AdaptiveShareLayout.EDGE_MARGIN)
    }

    @Test
    fun `wide screens cap line length and show at most six relationships`() {
        val layout = AdaptiveShareLayout.friends(
            screenWidth = 1_920,
            screenHeight = 1_080,
        )

        assertEquals(360, layout.contentWidth)
        assertEquals(6, layout.visibleRows)
        assertEquals(780, layout.contentX)
    }

    @Test
    fun `form layout remains usable at the minimum supported height`() {
        val layout = AdaptiveShareLayout.form(
            screenWidth = 320,
            screenHeight = 240,
            fieldCount = 4,
        )

        assertEquals(296, layout.contentWidth)
        assertTrue(layout.bodyTop < layout.footerTop)
        assertTrue(layout.availableBodyHeight >= 112)
        assertTrue(layout.footerBottom <= 228)
    }
}

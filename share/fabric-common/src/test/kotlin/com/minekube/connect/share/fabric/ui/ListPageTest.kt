package com.minekube.connect.share.fabric.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ListPageTest {
    @Test
    fun `pages every relationship without losing rows`() {
        val relationships = (1..12).toList()

        val first = relationships.page(offset = 0, size = 5)
        val second = relationships.page(offset = first.nextOffset!!, size = 5)
        val third = relationships.page(offset = second.nextOffset!!, size = 5)

        assertEquals((1..5).toList(), first.items)
        assertEquals((6..10).toList(), second.items)
        assertEquals(listOf(11, 12), third.items)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)
        assertTrue(second.hasPrevious)
        assertTrue(second.hasNext)
        assertTrue(third.hasPrevious)
        assertFalse(third.hasNext)
        assertEquals(3, third.pageNumber)
        assertEquals(3, third.pageCount)
    }

    @Test
    fun `clamps an obsolete offset after relationships disappear`() {
        val page = listOf("remaining").page(offset = 10, size = 5)

        assertEquals(listOf("remaining"), page.items)
        assertEquals(0, page.offset)
        assertEquals(null, page.previousOffset)
        assertEquals(null, page.nextOffset)
        assertEquals(1, page.pageNumber)
        assertEquals(1, page.pageCount)
    }
}

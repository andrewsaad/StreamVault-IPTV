package com.streamvault.data.local.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleRowIdsTest {
    @Test
    fun returnsOnlyRowsMissingFromKeepSet() {
        val existing = listOf(1L to 10L, 2L to 20L, 3L to 30L)
        assertEquals(listOf(2L), staleRowIds(existing, listOf(10L, 30L, 40L)))
    }

    @Test
    fun keepsAllDuplicatesOfKeptKeyAndDropsAllOfMissingKey() {
        val existing = listOf(1L to "a", 2L to "a", 3L to "b", 4L to "b")
        assertEquals(listOf(3L, 4L), staleRowIds(existing, listOf("a")))
    }

    @Test
    fun handlesMoreThanSqliteVariableLimit() {
        val existing = (1L..3000L).map { it to it }
        val stale = staleRowIds(existing, (1L..1000L).toList())
        assertEquals(2000, stale.size)
        val chunks = stale.chunked(DELETE_BY_IDS_CHUNK_SIZE)
        assertEquals(4, chunks.size)
        assertTrue(chunks.all { it.size <= 500 })
    }
}

package com.example

import com.example.network.ApiKeyRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyRotationTest {

    @Test
    fun `starts at the key that worked last`() {
        assertEquals(listOf(0, 1, 2), ApiKeyRotation.order(3, 0))
        assertEquals(listOf(1, 2, 0), ApiKeyRotation.order(3, 1))
        assertEquals(listOf(2, 0, 1), ApiKeyRotation.order(3, 2))
    }

    @Test
    fun `every key is tried exactly once`() {
        val order = ApiKeyRotation.order(5, 3)
        assertEquals(5, order.size)
        assertEquals(5, order.toSet().size)
        assertTrue(order.all { it in 0..4 })
    }

    @Test
    fun `stale active index wraps into the pool`() {
        assertEquals(listOf(1, 0), ApiKeyRotation.order(2, 9))
        assertEquals(listOf(1, 0), ApiKeyRotation.order(2, 3))
        assertEquals(listOf(0, 1, 2), ApiKeyRotation.order(3, 9))
        assertEquals(listOf(2, 0, 1), ApiKeyRotation.order(3, 8))
    }

    @Test
    fun `empty pool produces no candidates`() {
        assertTrue(ApiKeyRotation.order(0, 0).isEmpty())
        assertTrue(ApiKeyRotation.order(-4, 2).isEmpty())
    }
}

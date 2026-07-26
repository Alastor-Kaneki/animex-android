package com.alastor.orbitlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereLayoutTest {
    @Test
    fun emptyInputProducesNoNodes() {
        assertTrue(buildSphereNodes(emptyList<String>(), 0f, 0f, 0f, 0f, 100f).isEmpty())
    }

    @Test
    fun everyItemProducesOneFiniteBoundedNode() {
        val items = (0 until 500).toList()
        val nodes = buildSphereNodes(items, yaw = 2.4f, pitch = -0.9f, centerX = 500f, centerY = 800f, radius = 320f)

        assertEquals(items.size, nodes.size)
        assertEquals(items, nodes.map { it.item })
        nodes.forEach { node ->
            assertTrue(node.x.isFinite())
            assertTrue(node.y.isFinite())
            assertTrue(node.depth in -1.0001f..1.0001f)
            assertTrue(node.scale in 0.62f..1.34f)
            assertTrue(node.alpha in 0.30f..1.0f)
        }
    }

    @Test
    fun rotationChangesProjectionWithoutChangingItems() {
        val items = listOf("A", "B", "C", "D", "E")
        val first = buildSphereNodes(items, 0f, 0f, 500f, 800f, 300f)
        val second = buildSphereNodes(items, 1f, 0.5f, 500f, 800f, 300f)

        assertEquals(first.map { it.item }, second.map { it.item })
        assertTrue(first.zip(second).any { (a, b) -> a.x != b.x || a.y != b.y })
    }
}

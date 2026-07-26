package com.alastor.orbitlauncher.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal data class SphereNode<T>(
    val item: T,
    val x: Float,
    val y: Float,
    val depth: Float,
    val scale: Float,
    val alpha: Float,
)

/**
 * Places items evenly over a virtual unit sphere using a Fibonacci lattice,
 * rotates that sphere, then projects it onto the 2D launcher surface.
 */
internal fun <T> buildSphereNodes(
    items: List<T>,
    yaw: Float,
    pitch: Float,
    centerX: Float,
    centerY: Float,
    radius: Float,
): List<SphereNode<T>> {
    if (items.isEmpty()) return emptyList()

    val goldenAngle = PI * (3.0 - sqrt(5.0))
    val count = items.size
    val cosYaw = cos(yaw)
    val sinYaw = sin(yaw)
    val cosPitch = cos(pitch)
    val sinPitch = sin(pitch)

    return items.mapIndexed { index, item ->
        val normalized = if (count == 1) 0.5 else index.toDouble() / (count - 1).toDouble()
        val baseY = 1.0 - normalized * 2.0
        val ringRadius = sqrt((1.0 - baseY * baseY).coerceAtLeast(0.0))
        val angle = goldenAngle * index
        val baseX = cos(angle) * ringRadius
        val baseZ = sin(angle) * ringRadius

        val rotatedX = baseX * cosYaw + baseZ * sinYaw
        val yawZ = -baseX * sinYaw + baseZ * cosYaw
        val rotatedY = baseY * cosPitch - yawZ * sinPitch
        val depth = baseY * sinPitch + yawZ * cosPitch

        val perspective = (1.0 / (1.85 - depth * 0.55)).toFloat()
        val normalizedDepth = ((depth + 1.0) / 2.0).toFloat().coerceIn(0f, 1f)

        SphereNode(
            item = item,
            x = centerX + (rotatedX * radius * perspective).toFloat(),
            y = centerY + (rotatedY * radius * perspective * 0.93).toFloat(),
            depth = depth.toFloat(),
            scale = 0.62f + normalizedDepth * 0.72f,
            alpha = 0.30f + normalizedDepth * 0.70f,
        )
    }
}

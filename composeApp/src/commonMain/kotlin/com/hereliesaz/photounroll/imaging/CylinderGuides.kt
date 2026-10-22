package com.hereliesaz.photounroll.imaging

/**
 * Where the cylinder sits in one photo. All values are fractions of the image size
 * so guides survive rescaling.
 *
 * ~~~
 *        left             right
 *         |   .-''''''-.    |     <- topY: silhouette tangent row of the top rim
 *         | (            )  |
 *         |   '-......-'    |     <- topY + topBulge: rim point nearest the camera
 *         |                 |
 *         |   .-''''''-.    |     <- bottomY
 *         | (            )  |
 *         |   '-......-'    |     <- bottomY + bottomBulge
 * ~~~
 *
 * A bulge is the ellipse's semi-minor axis; positive means the near side of the rim
 * appears lower in the frame (camera above the rim), negative means higher.
 */
data class CylinderGuides(
    val left: Float = 0.3f,
    val right: Float = 0.7f,
    val topY: Float = 0.2f,
    val topBulge: Float = 0.03f,
    val bottomY: Float = 0.8f,
    val bottomBulge: Float = 0.05f,
) {
    val isValid: Boolean get() = right - left > 0.01f && bottomY - topY > 0.01f

    /** Swaps crossed handles so dragging past each other never yields an inverted cylinder. */
    fun normalized(): CylinderGuides {
        val l = minOf(left, right).coerceIn(0f, 1f)
        val r = maxOf(left, right).coerceIn(0f, 1f)
        return if (topY <= bottomY) copy(left = l, right = r)
        else copy(left = l, right = r, topY = bottomY, topBulge = bottomBulge, bottomY = topY, bottomBulge = topBulge)
    }
}

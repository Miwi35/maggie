package com.maggie.app.ui.activity

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/**
 * The halo the mockups lay on the night background (MAG-617): an ellipse of [width] × [height] (fractions of the
 * window) centred at ([x], [y]), the module's colour at [alpha] in its middle and gone at [fade] of its radius.
 * The fractions are the tablet mockup's px over its 1280 × 800 screen, so a phone gets the same halo in proportion.
 */
internal data class ActivityHalo(
    val width: Float,
    val height: Float,
    val x: Float,
    val y: Float,
    val alpha: Float,
    val fade: Float,
) {
    /** The ellipse in px for a window of [size]: centre and radii. */
    fun ellipse(size: Size): Triple<Offset, Float, Float> =
        Triple(Offset(size.width * x, size.height * y), size.width * width, size.height * height)

    /** The colour stops: [color] at [alpha] to the same colour at zero (never black, which would grey the fade). */
    fun stops(color: Color): List<Pair<Float, Color>> =
        listOf(0f to color.copy(alpha = alpha), fade to color.copy(alpha = 0f))

    companion object {
        /** « Activité Cuisine · Tablette » (900 × 640 at 78 % 30 %, 16 %): the frame's, unless an activity's mockup says otherwise. */
        val Frame = ActivityHalo(width = 900f / 1280f, height = 640f / 800f, x = 0.78f, y = 0.30f, alpha = 0.16f, fade = 0.62f)

        /** « Revue · Activité » (900 × 600 at 85 % 30 %, 13 %): the Comptes mint, further right and softer. */
        val Review = ActivityHalo(width = 900f / 1280f, height = 600f / 800f, x = 0.85f, y = 0.30f, alpha = 0.13f, fade = 0.60f)
    }
}

/** The colour a node's halo is drawn in, for the tests: a drawing cannot be read back, a semantics property can. */
internal val HaloColorKey = SemanticsPropertyKey<Color>("HaloColor")
private var SemanticsPropertyReceiver.haloColor by HaloColorKey

/** Draws [halo] in [color] behind the content, over whatever background it is given. */
internal fun Modifier.activityHalo(color: Color, halo: ActivityHalo = ActivityHalo.Frame): Modifier = semantics { haloColor = color }.drawBehind {
    val (center, radiusX, radiusY) = halo.ellipse(size)
    // A radial brush is a circle: draw one of the ellipse's width, squashed to its height around its centre.
    scale(scaleX = 1f, scaleY = radiusY / radiusX, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(colorStops = halo.stops(color).toTypedArray(), center = center, radius = radiusX),
            radius = radiusX,
            center = center,
        )
    }
}

package dev.wasil.permit.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wasil.permit.ui.theme.LocalHandoffColors

/**
 * Two arcs curving away from each other like a facing "( )", with a dot that
 * sits at whichever arc's centre currently holds the permit. The unlit arc
 * dims, so position, colour and contrast all say the same thing.
 *
 * With [MarkArcs.SOLE] it draws one arc and the dot nested in it — the shape a
 * single car with the permit actually has. The second arc is *removed*, not
 * dimmed: a dimmed arc means "the other car has not got it", which needs there
 * to be another car.
 */
@Composable
fun HandoffMark(state: MarkState, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val colors = LocalHandoffColors.current
    val leftColor = if (state.lit == Side.LEFT) colors.wasilStrong else colors.arcInactive
    val rightColor = if (state.lit == Side.RIGHT) colors.walidStrong else colors.arcInactive
    val sole = state.arcs == MarkArcs.SOLE

    // The pair needs room for two arcs and the travel between them; one arc and
    // its dot is a little over half that, and keeping the full width would sit
    // the whole mark left of centre inside a centred column.
    Canvas(modifier = modifier.size(width = size * (if (sole) 0.86f else 1.6f), height = size)) {
        val stroke = Stroke(width = size.toPx() * 0.13f, cap = StrokeCap.Round)
        val cy = this.size.height / 2f
        val r = this.size.height * 0.42f

        if (sole) {
            // The arc's own circle centre, which is where the dot sits: the
            // stroke is the far (west) side of that circle, so the dot lands
            // just inside the curve exactly as it does in the pair.
            val x = this.size.width * 0.52f
            val color = if (state.lit == Side.RIGHT) colors.walidStrong else colors.wasilStrong
            drawPath(arcPath(x, cy, r, opensRight = true), color, style = stroke)
            drawCircle(colors.dot, radius = this.size.height * 0.115f, center = Offset(x, cy))
            return@Canvas
        }

        val leftX = this.size.width * 0.30f
        val rightX = this.size.width * 0.70f

        drawPath(arcPath(leftX, cy, r, opensRight = true), leftColor, style = stroke)
        drawPath(arcPath(rightX, cy, r, opensRight = false), rightColor, style = stroke)

        // The dot sits at whichever arc's own centre holds it — nested inside
        // that arc's curve — and in the middle when nobody holds it. Centre-
        // to-centre gives a wide, unambiguous left/right travel.
        val dotX = when (state.dot) {
            Side.LEFT -> leftX
            Side.RIGHT -> rightX
            null -> this.size.width / 2f
        }
        drawCircle(colors.dot, radius = this.size.height * 0.115f, center = Offset(dotX, cy))
    }
}

/**
 * [centerX]/[cy] is the arc's own circle centre. Angles follow Compose's
 * `addArc` convention: 0 degrees is due east (3 o'clock) and increases
 * clockwise, so 180 is west and 270 is north.
 *
 * An arc that "opens right" — a "(" — is the far (west) 140 degrees of its
 * circle, spanning 110..250 through 180: from just past the bottom, around
 * the west apex, to just past the top. Its mirror, an arc that opens left —
 * a ")" — is the far (east) 140 degrees, spanning 290..70 through 0/360.
 * Each arc's stroke stays on its own outward side of its circle, so the two
 * curve away from each other with open space in between, regardless of how
 * close their centres sit.
 */
private fun arcPath(centerX: Float, cy: Float, r: Float, opensRight: Boolean): Path {
    val start = if (opensRight) 110f else 290f
    return Path().apply {
        addArc(Rect(centerX - r, cy - r, centerX + r, cy + r), start, 140f)
    }
}

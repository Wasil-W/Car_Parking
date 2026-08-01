package dev.wasil.permit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Two radii, not six.
 *
 * Corners had drifted to 14, 16, 18 and 22dp across the screens — each choice
 * reasonable alone, the set of them reading as unconsidered. A system with two
 * steps is invisible in the right way: nothing draws the eye because nothing
 * disagrees.
 *
 * [Card] is for anything holding content — the hero, the map, the summary
 * panel. [Control] is for things you press or that sit inside a card.
 */
object HandoffShapes {
    val Card = RoundedCornerShape(20.dp)
    val Control = RoundedCornerShape(14.dp)
}

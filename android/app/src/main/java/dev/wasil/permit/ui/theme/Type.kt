package dev.wasil.permit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One family, a deliberate scale, and every style carrying its own line height
 * and tracking.
 *
 * The previous scale set sizes only, leaving Compose to pick line heights — the
 * single thing that most makes an interface read as default rather than
 * designed. Body styles now sit near 1.45 leading, which is comfortable without
 * looking airy; headings are tighter at ~1.2 and pull their letters in slightly,
 * since large type at default tracking looks loose.
 *
 * Sizes step by roughly a fifth rather than doubling: the aim is a screen with
 * one clear focal point and a quiet supporting cast, not a shouted hierarchy.
 */
val HandoffTypography = Typography(
    // The permit holder's name — the one thing on the main screen that should
    // be readable at arm's length. Down from 30sp: it was competing with itself.
    headlineLarge = TextStyle(
        fontSize = 26.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.4).sp,
    ),
    // Screen titles: Settings, Map.
    headlineSmall = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    // Button labels. Medium, never Bold — weight is enough emphasis when the
    // button already has a fill behind it.
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
    ),
    // Section headers. Slight positive tracking is what makes small caps-height
    // text read as a deliberate label rather than shrunken body copy.
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    ),
)

package dev.wasil.permit.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.wasil.permit.parking.MyCar
import dev.wasil.permit.ui.theme.HandoffTheme
import dev.wasil.permit.ui.theme.LocalHandoffColors
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Renders the mark against the real hero-card colours and writes PNGs, so the
 * question "can you actually tell the lit arc from the dimmed one" is answered
 * by pixels rather than by arithmetic over hex values.
 *
 * Contrast maths said the Wasil-on-dark case was marginal (lit 3.371:1 vs
 * dimmed 3.284:1 against the container). This measures what really renders.
 *
 * PNGs land in the app's external files dir; pull them with
 * `adb pull /sdcard/Android/data/dev.wasil.permit/files/`.
 */
class HandoffMarkScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private fun outDir(): File =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null)!!.also { it.mkdirs() }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit): Bitmap {
        rule.setContent { HandoffTheme(darkTheme = dark) { content() } }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(outDir(), "$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }

    /** The mark as it appears inside a hero card belonging to [card]. */
    @Composable
    private fun MarkOnCard(card: MyCar, holder: MyCar?) {
        val colors = LocalHandoffColors.current
        Box(
            modifier = Modifier
                .background(colors.containerFor(card))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            HandoffMark(markStateFor(holder), size = 72.dp)
        }
    }

    @Test
    fun mark_on_wasil_card_dark() {
        val b = capture("mark-wasil-card-dark", dark = true) {
            MarkOnCard(card = MyCar.WASIL, holder = MyCar.WASIL)
        }
        assertTrue("bitmap should have pixels", b.width > 0 && b.height > 0)
    }

    @Test
    fun mark_on_walid_card_dark() {
        capture("mark-walid-card-dark", dark = true) {
            MarkOnCard(card = MyCar.WALID, holder = MyCar.WALID)
        }
    }

    @Test
    fun mark_on_wasil_card_light() {
        capture("mark-wasil-card-light", dark = false) {
            MarkOnCard(card = MyCar.WASIL, holder = MyCar.WASIL)
        }
    }

    @Test
    fun mark_on_walid_card_light() {
        capture("mark-walid-card-light", dark = false) {
            MarkOnCard(card = MyCar.WALID, holder = MyCar.WALID)
        }
    }

    /** Nobody holding: both arcs dim, dot centred. */
    @Test
    fun mark_unheld_dark() {
        capture("mark-unheld-dark", dark = true) {
            MarkOnCard(card = MyCar.WASIL, holder = null)
        }
    }
}

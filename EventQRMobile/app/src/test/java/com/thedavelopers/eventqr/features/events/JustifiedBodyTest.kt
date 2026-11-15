package com.thedavelopers.eventqr.features.events

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.text.Layout
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thedavelopers.eventqr.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * §6.5: the two long request bodies (Reason for request, Description) are justified inter-word
 * because Compose `Text` cannot justify. The body is the platform TextView in
 * `res/layout/ui_justified_body.xml`; this pins the attributes that make the justification real and
 * the ones that keep it from clipping at a large font scale.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class JustifiedBodyTest {

    private val baseContext: Context = ApplicationProvider.getApplicationContext()

    private fun contextAt(fontScale: Float): Context {
        val configuration = Configuration(baseContext.resources.configuration).apply {
            this.fontScale = fontScale
        }
        return ContextThemeWrapper(
            baseContext.createConfigurationContext(configuration),
            R.style.Theme_EventQR,
        )
    }

    private fun body(fontScale: Float = 1f, text: String = LONG_BODY): TextView {
        val context = contextAt(fontScale)
        // A real parent: inflating with a null root leaves the view without LayoutParams.
        val view = LayoutInflater.from(context)
            .inflate(R.layout.ui_justified_body, FrameLayout(context), false) as TextView
        view.text = text
        return view
    }

    @Test
    fun body_isJustifiedInterWordFromApi26() {
        val view = body()

        assertTrue(
            "inter-word justification needs API 26, which is this build's minSdk",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O,
        )
        assertEquals(Layout.JUSTIFICATION_MODE_INTER_WORD, view.justificationMode)
        assertEquals(Layout.BREAK_STRATEGY_HIGH_QUALITY, view.breakStrategy)
    }

    /** Never centred and never end aligned: justification stretches spaces, it does not move the edge. */
    @Test
    fun body_staysStartAligned() {
        val view = body()

        // `start` resolves to the leading edge, so the same layout reads correctly under RTL.
        assertTrue("gravity is a relative start, not a fixed left", view.gravity and Gravity.START != 0)
        assertEquals(Gravity.LEFT, view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertEquals(Gravity.TOP, view.gravity and Gravity.VERTICAL_GRAVITY_MASK)
        assertEquals(View.TEXT_ALIGNMENT_GRAVITY, view.textAlignment)
    }

    /** No truncation and no fixed box: the body wraps and the row grows (§9.3). */
    @Test
    fun body_isNeverTruncatedOrHeightCapped() {
        val view = body()

        assertNull(view.ellipsize)
        assertEquals(Int.MAX_VALUE, view.maxLines)
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, view.layoutParams.height)
    }

    /** The words keep the `body` role's own size and ink, so the justified copy is not a new style. */
    @Test
    fun body_wearsTheBodyRoleAndPrimaryInk() {
        val context = contextAt(1f)
        val view = body()

        assertEquals(context.resources.getDimension(R.dimen.ui_type_body), view.textSize, 0.5f)
        assertEquals(
            ContextCompat.getColor(context, R.color.ui_text_primary),
            view.currentTextColor,
        )
    }

    /** At `fontScale` 2.0 the same body reads larger, because nothing caps its box. */
    @Test
    fun body_growsAtFontScale2() {
        val atOne = body()
        val atTwo = body(fontScale = 2f)

        assertTrue(
            "text size must scale with the font setting (${atOne.textSize} -> ${atTwo.textSize})",
            atTwo.textSize > atOne.textSize,
        )
        assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, atTwo.layoutParams.height)
        assertNull(atTwo.ellipsize)
    }

    private companion object {
        /** Long enough to take several lines at 360dp, the width the detail screen gives it. */
        const val LONG_BODY =
            "We are running a two-day campus technology conference for 600 students and need a " +
                "check-in desk that can scan passes quickly. This request is the event we ran " +
                "last year, now with a second hall and a staff team of twelve."
    }
}

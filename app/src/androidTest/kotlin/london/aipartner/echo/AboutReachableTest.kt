package london.aipartner.echo

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import london.aipartner.echo.core.consent.ConsentPreferences
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * Phase 3 gate (re-homed for Phase 6 nav): the About restatement of the disclosure
 * is actually REACHABLE in the built UI. Drives the REAL [MainActivity] through the
 * Phase-6 navigation graph: Library → Settings → About, asserting the
 * about_privacy_title string shows. (The disclosure is acknowledged first so the app
 * starts on the library; the disclosure is acknowledge-only and never gates
 * recording — covered by other tests.)
 *
 * NOTE: the full end-to-end library → player flow test is the Phase-6 gate item;
 * this stays the focused About-reachability backstop.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AboutReachableTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Must run before the activity launches (order 2), so the app skips disclosure.
    @get:Rule(order = 1)
    val acknowledgeDisclosure = object : ExternalResource() {
        override fun before() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            ConsentPreferences(context).disclosureAcknowledged = true
        }
    }

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun aboutRestatementIsReachable() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        composeRule.onNodeWithContentDescription("Settings").performClick()
        // The Phase-7 Settings rework re-homed the disclosure restatement into a row whose
        // subtitle uniquely identifies it (the title matches the destination header, so we click
        // the subtitle to stay unambiguous). onClick routes to the About/privacy screen.
        composeRule.onNodeWithText(string(context, "settings_about_row_subtitle")).performClick()

        composeRule.onNodeWithText(string(context, "about_privacy_title")).assertIsDisplayed()
    }

    private fun string(context: Context, name: String): String {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        return context.getString(id)
    }
}

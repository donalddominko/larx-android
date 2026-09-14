package london.aipartner.echo

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import london.aipartner.echo.core.billing.DevEntitlements
import london.aipartner.echo.core.billing.Entitlements
import london.aipartner.echo.core.billing.Feature
import london.aipartner.echo.core.capture.CaptureSourceResolver
import london.aipartner.echo.core.capture.RealCaptureSourceResolver
import london.aipartner.echo.core.consent.ConsentGate
import london.aipartner.echo.core.consent.RecordAudioConsentGate
import london.aipartner.echo.core.sync.CloudSink
import london.aipartner.echo.core.sync.RoutingCloudSink
import london.aipartner.echo.core.transcribe.Transcriber
import london.aipartner.echo.core.transcribe.TranscriberRouter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Phase 1 seam gate. Proves the Hilt graph binds each of the five seams to its
 * expected stub. The graph — not assertion — is the proof the seams are wired.
 * Independent of entitlement state: passes with Pro **off**.
 */
@HiltAndroidTest
class SeamGraphTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var captureSourceResolver: CaptureSourceResolver
    @Inject lateinit var consentGate: ConsentGate
    @Inject lateinit var entitlements: Entitlements
    @Inject lateinit var transcriber: Transcriber
    @Inject lateinit var cloudSink: CloudSink

    @Before fun inject() = hiltRule.inject()

    @Test
    fun allFiveSeamsResolveToTheirStubs() {
        assertTrue(captureSourceResolver is RealCaptureSourceResolver)
        assertTrue(consentGate is RecordAudioConsentGate)
        assertTrue(entitlements is DevEntitlements)
        // Phase 4: the seam now binds the router (on-device default, cloud behind
        // the double gate) in place of the Phase 1 NoopTranscriber.
        assertTrue(transcriber is TranscriberRouter)
        // Phase 8: the seam now binds the production RoutingCloudSink (resolves the
        // user's WebDAV destination per call; no destination ⇒ the worker no-ops)
        // in place of the Phase 1 LocalOnlyCloudSink reference no-op.
        assertTrue(cloudSink is RoutingCloudSink)
    }

    @Test
    fun entitlementsDefaultProOff() {
        // Honest gating from day one — every Pro feature is locked by default.
        Feature.entries.forEach { feature ->
            assertFalse("$feature must be locked with Pro off", entitlements.has(feature))
        }
    }
}

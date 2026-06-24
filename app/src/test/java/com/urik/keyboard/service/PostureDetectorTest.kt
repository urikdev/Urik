package com.urik.keyboard.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PostureDetectorTest {
    private val app = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

    private fun portraitContext() = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
    )

    private fun halfOpenedVerticalFold(hingeBounds: Rect) = object : FoldingFeature {
        override val bounds = hingeBounds
        override val isSeparating = true
        override val occlusionType = FoldingFeature.OcclusionType.NONE
        override val orientation = FoldingFeature.Orientation.VERTICAL
        override val state = FoldingFeature.State.HALF_OPENED
    }

    private fun landscapeContext(): Context {
        RuntimeEnvironment.setQualifiers("+land")
        val landscapeConfig = Configuration(app.resources.configuration)
        RuntimeEnvironment.setQualifiers("+port")
        return app.createConfigurationContext(landscapeConfig)
    }

    @Test
    fun `orientation is derived from displayMetrics not stale configuration orientation field`() {
        val staleConfigContext = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
        )

        val detector = PostureDetector(staleConfigContext, scope)

        val metrics = staleConfigContext.resources.displayMetrics
        assertTrue(metrics.heightPixels >= metrics.widthPixels)
        assertEquals(
            Configuration.ORIENTATION_PORTRAIT,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `initial PostureInfo reflects service context orientation`() {
        val detector = PostureDetector(portraitContext(), scope)

        assertEquals(
            Configuration.ORIENTATION_PORTRAIT,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `attachToWindow adopts orientation from window context`() {
        val detector = PostureDetector(portraitContext(), scope)

        detector.attachToWindow(landscapeContext())

        assertEquals(
            Configuration.ORIENTATION_LANDSCAPE,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `onConfigurationChanged prefers window context over service context`() {
        val detector = PostureDetector(portraitContext(), scope)
        detector.attachToWindow(landscapeContext())

        detector.onConfigurationChanged()

        assertEquals(
            Configuration.ORIENTATION_LANDSCAPE,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `onConfigurationChanged falls back to service context when no window attached`() {
        val detector = PostureDetector(portraitContext(), scope)

        detector.onConfigurationChanged()

        assertEquals(
            Configuration.ORIENTATION_PORTRAIT,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `onConfigurationChanged falls back to service context after stop clears window context`() {
        val detector = PostureDetector(portraitContext(), scope)
        detector.attachToWindow(landscapeContext())
        detector.stop()

        detector.onConfigurationChanged()

        assertEquals(
            Configuration.ORIENTATION_PORTRAIT,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `updatePostureFromLayoutInfo uses windowContext orientation not bare service context`() {
        // This is the real squish scenario: service context may lag during rotation window.
        // windowContext is the authoritative source for orientation when set.
        val detector = PostureDetector(portraitContext(), scope)
        detector.attachToWindow(portraitContext())

        detector.updatePostureFromLayoutInfo(WindowLayoutInfo(emptyList()))

        assertEquals(
            Configuration.ORIENTATION_PORTRAIT,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `updatePostureFromLayoutInfo reflects landscape when windowContext is landscape`() {
        val detector = PostureDetector(landscapeContext(), scope)
        detector.attachToWindow(landscapeContext())

        detector.updatePostureFromLayoutInfo(WindowLayoutInfo(emptyList()))

        assertEquals(
            Configuration.ORIENTATION_LANDSCAPE,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `refresh updates postureInfo to current metrics`() {
        val detector = PostureDetector(portraitContext(), scope)
        detector.attachToWindow(landscapeContext())

        detector.refresh()

        assertEquals(
            Configuration.ORIENTATION_LANDSCAPE,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `refresh preserves tracker-derived posture and hingeBounds`() {
        val detector = PostureDetector(portraitContext(), scope)
        detector.attachToWindow(portraitContext())
        val hinge = Rect(0, 500, 1080, 530)
        detector.updatePostureFromLayoutInfo(
            WindowLayoutInfo(listOf(halfOpenedVerticalFold(hinge)))
        )
        assertEquals(DevicePosture.HALF_OPENED, detector.postureInfo.value.posture)

        detector.refresh()

        assertEquals(DevicePosture.HALF_OPENED, detector.postureInfo.value.posture)
        assertEquals(hinge, detector.postureInfo.value.hingeBounds)
    }

    @Test
    fun `refresh on unchanged metrics does not emit a new StateFlow value`() {
        val detector = PostureDetector(portraitContext(), scope)
        detector.attachToWindow(portraitContext())

        val emissions = mutableListOf<PostureInfo>()
        val collectJob = scope.launch { detector.postureInfo.collect { emissions.add(it) } }

        detector.refresh()
        collectJob.cancel()

        assertEquals(1, emissions.size)
    }

    @Test
    fun `updatePostureFromLayoutInfo reflects portrait after onConfigurationChanged`() {
        val detector = PostureDetector(portraitContext(), scope)
        detector.attachToWindow(portraitContext())
        detector.onConfigurationChanged()

        detector.updatePostureFromLayoutInfo(WindowLayoutInfo(emptyList()))

        assertEquals(
            Configuration.ORIENTATION_PORTRAIT,
            detector.postureInfo.value.orientation
        )
    }
}

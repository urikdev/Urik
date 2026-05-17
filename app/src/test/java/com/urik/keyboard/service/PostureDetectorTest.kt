package com.urik.keyboard.service

import android.content.res.Configuration
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
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

    private fun landscapeContext() = app.createConfigurationContext(
        Configuration(app.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
    )

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
    fun `onConfigurationChanged prefers window context over service context (GH-742)`() {
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

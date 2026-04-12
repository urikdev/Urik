package com.urik.keyboard.service

import android.content.res.Configuration
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
    fun `attachToWindow does not override portrait service context with stale landscape window context`() {
        val detector = PostureDetector(portraitContext(), scope)

        detector.attachToWindow(landscapeContext())

        assertEquals(
            "attachToWindow must not replace portrait posture with stale landscape window context",
            Configuration.ORIENTATION_PORTRAIT,
            detector.postureInfo.value.orientation
        )
    }

    @Test
    fun `onConfigurationChanged uses service context not stale window context`() {
        val detector = PostureDetector(portraitContext(), scope)

        detector.attachToWindow(landscapeContext())

        detector.onConfigurationChanged()

        assertEquals(
            "onConfigurationChanged must re-read service context orientation, not stale window context",
            Configuration.ORIENTATION_PORTRAIT,
            detector.postureInfo.value.orientation
        )
    }
}

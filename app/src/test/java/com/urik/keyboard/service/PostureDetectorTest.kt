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
    @Test
    fun `attachToWindow syncs PostureInfo orientation from window context`() {
        val app = RuntimeEnvironment.getApplication()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

        val landscapeConfig = Configuration(app.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        val serviceContext = app.createConfigurationContext(landscapeConfig)

        val detector = PostureDetector(serviceContext, scope)
        detector.start()

        assertEquals(
            "Initial PostureInfo should reflect the (stale) service context orientation",
            Configuration.ORIENTATION_LANDSCAPE,
            detector.postureInfo.value.orientation
        )

        val portraitConfig = Configuration(app.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        val windowContext = app.createConfigurationContext(portraitConfig)

        detector.attachToWindow(windowContext)

        assertEquals(
            "PostureInfo must update to window context orientation after attachToWindow",
            Configuration.ORIENTATION_PORTRAIT,
            detector.postureInfo.value.orientation
        )
    }
}

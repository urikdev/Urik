package com.urik.keyboard.service

import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TestInputMethodServiceTest {
    private lateinit var service: TestInputMethodService

    @Before
    fun setup() {
        service = TestInputMethodService()
    }

    @Test
    fun `inputState field is accessible as internal from test code`() {
        // Verifies compile-time visibility: the property reference is non-null,
        // confirming the field is accessible (internal) from the test package.
        // The backing field is intentionally not dereferenced — onCreate() has not
        // run, so the lateinit var is uninitialised and dereference would throw.
        assertNotNull(service::inputState)
    }

    @Test
    fun `outputBridge field is accessible as internal from test code`() {
        // See inputState test for rationale.
        assertNotNull(service::outputBridge)
    }

    @Test
    fun `suggestionPipeline field is accessible as internal from test code`() {
        // See inputState test for rationale.
        assertNotNull(service::suggestionPipeline)
    }
}

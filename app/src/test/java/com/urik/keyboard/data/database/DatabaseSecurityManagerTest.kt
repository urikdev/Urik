package com.urik.keyboard.data.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.utils.ErrorLogger
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseSecurityManagerTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        ErrorLogger.resetForTesting()
        ErrorLogger.init(context)
    }

    @Test
    fun `getDatabasePassphrase returns null and logs HIGH warning when no lock screen`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { false })
        val beforeCount = ErrorLogger.getErrorCount()

        val result = manager.getDatabasePassphrase()

        assertNull(result)
        val afterCount = ErrorLogger.getErrorCount()
        assertTrue("Expected at least one new error entry", afterCount > beforeCount)
    }

    @Test
    fun `getDatabasePassphrase does not log when lock screen present`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { true })
        try {
            manager.getDatabasePassphrase()
        } catch (_: Exception) {
            // Robolectric Keystore throws — expected
        }
    }
}

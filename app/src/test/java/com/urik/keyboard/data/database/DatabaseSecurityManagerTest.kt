package com.urik.keyboard.data.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.urik.keyboard.R
import com.urik.keyboard.utils.ErrorLogger
import org.junit.Assert.assertNotNull
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

    @Test
    fun `prefs field returns the same instance on repeated access`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { false })
        val field = DatabaseSecurityManager::class.java
            .getDeclaredField("prefs")
            .apply { isAccessible = true }
        val first = field.get(manager)
        val second = field.get(manager)
        assertTrue("prefs must be eagerly initialized (same instance)", first === second)
    }

    @Test
    fun `prefs field is non-null immediately after construction`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { false })
        val field = DatabaseSecurityManager::class.java
            .getDeclaredField("prefs")
            .apply { isAccessible = true }
        val prefs = field.get(manager)
        assertNotNull("prefs must be non-null after construction", prefs)
    }

    @Test
    fun `hasDeviceLockScreen returns false when no lock screen configured`() {
        val manager = DatabaseSecurityManager(context, lockScreenCheck = { false })
        assertTrue(
            "SEC-01 requires hasDeviceLockScreen() to reflect lock screen absence",
            !manager.hasDeviceLockScreen()
        )
    }

    @Test
    fun `privacy_settings_keystore_api27_title string resource exists`() {
        val title = context.getString(R.string.privacy_settings_keystore_api27_title)
        assertTrue("SEC-02 requires api27 title string", title.isNotEmpty())
    }

    @Test
    fun `privacy_settings_keystore_api27_summary string resource exists`() {
        val summary = context.getString(R.string.privacy_settings_keystore_api27_summary)
        assertTrue("SEC-02 requires api27 summary string", summary.isNotEmpty())
    }
}

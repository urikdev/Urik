package com.urik.keyboard.data.database

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.urik.keyboard.utils.ErrorLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Architecture:
 * - Master key (AES-256) stored in hardware security module
 * - Database passphrase (32 bytes) encrypted by master key
 * - Device lock screen required (enforces device-level security)
 */
class DatabaseSecurityManager(
    private val context: Context,
    @get:VisibleForTesting internal val lockScreenCheck: () -> Boolean = { defaultLockScreenCheck(context) }
) {
    private val keyAlias = "urik_database_master_key"
    private val passphraseKey = "encrypted_passphrase"
    private val ivKey = "encryption_iv"

    private val prefs: SharedPreferences = deviceProtectedPrefs(context)

    /** Without a lock screen, Keystore keys are vulnerable to offline attacks. */
    fun hasDeviceLockScreen(): Boolean = lockScreenCheck()

    /** @return 32-byte passphrase for SQLCipher, or null if no device lock screen */
    fun getDatabasePassphrase(): ByteArray? {
        if (!hasDeviceLockScreen()) {
            ErrorLogger.logException(
                component = "DatabaseSecurityManager",
                severity = ErrorLogger.Severity.HIGH,
                exception = IllegalStateException("Device has no lock screen; database encryption unavailable"),
                context = mapOf("condition" to "no_lock_screen")
            )
            return null
        }

        return try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)

            if (!keyStore.containsAlias(keyAlias)) {
                return generateAndStorePassphrase()
            }

            retrieveStoredPassphrase()
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "DatabaseSecurityManager",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("operation" to "getDatabasePassphrase")
            )
            null
        }
    }

    private fun generateAndStorePassphrase(): ByteArray {
        val secretKey = generateMasterKey()

        val passphrase = ByteArray(32)
        secureRandom.nextBytes(passphrase)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)

        val encryptedPassphrase = cipher.doFinal(passphrase)
        val iv = cipher.iv

        prefs.edit().apply {
            putString(passphraseKey, Base64.encodeToString(encryptedPassphrase, Base64.NO_WRAP))
            putString(ivKey, Base64.encodeToString(iv, Base64.NO_WRAP))
            commit()
        }

        return passphrase
    }

    private fun retrieveStoredPassphrase(): ByteArray? {
        return try {
            val encryptedPassphraseString = prefs.getString(passphraseKey, null)
            val ivString = prefs.getString(ivKey, null)

            if (encryptedPassphraseString == null || ivString == null) {
                // Prefs entries missing despite alias existing — DE prefs may be corrupted/partially
                // written. Do NOT regenerate: generateAndStorePassphrase() would overwrite the
                // Keystore key, making any existing encrypted DB permanently unreadable.
                return null
            }

            val encryptedPassphrase = Base64.decode(encryptedPassphraseString, Base64.NO_WRAP)
            val iv = Base64.decode(ivString, Base64.NO_WRAP)

            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val secretKey = keyStore.getKey(keyAlias, null) as SecretKey

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

            cipher.doFinal(encryptedPassphrase)
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "DatabaseSecurityManager",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("operation" to "retrieveStoredPassphrase")
            )
            null
        }
    }

    /**
     * Generates AES-256 master key in Android Keystore.
     *
     * setUserAuthenticationRequired(false): key does not require biometric/PIN to use.
     * This is intentional — requiring auth would block the keyboard from decrypting the
     * database passphrase in the background (no UI surface to prompt). The compensating
     * control is setUnlockedDeviceRequired(true) on API 28+, which ensures the key is
     * inaccessible when the device is locked (screen off / locked state), providing
     * equivalent protection without requiring an interactive auth prompt.
     */
    private fun generateMasterKey(): SecretKey {
        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

        val keyGenParameterSpec =
            KeyGenParameterSpec
                .Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).apply {
                    setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    setKeySize(256)
                    setUserAuthenticationRequired(false)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        setUnlockedDeviceRequired(true)
                    }
                }.build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    fun shouldMigrateToEncrypted(context: Context): Boolean {
        val dbFile = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)
        return dbFile.exists() && !prefs.contains(passphraseKey) && hasDeviceLockScreen()
    }

    /**
     * One-way operation. On success, original unencrypted database permanently deleted.
     * On failure, temp deleted and original preserved.
     */
    fun migrateToEncryptedDatabase(context: Context): Boolean {
        if (prefs.contains(passphraseKey)) {
            return false
        }

        val tempDbPath = context.getDatabasePath("${KeyboardDatabase.DATABASE_NAME}_temp")

        return try {
            val unencryptedDbPath = context.getDatabasePath(KeyboardDatabase.DATABASE_NAME)

            if (!unencryptedDbPath.exists()) {
                return false
            }

            val passphrase = generateAndStorePassphrase()
            try {
                val passphraseString = passphrase.joinToString("") { "%02x".format(it) }

                val safePath = tempDbPath.absolutePath
                check(!safePath.contains('\'')) {
                    "Unexpected single-quote in database path: $safePath"
                }

                val unencryptedDb =
                    net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                        unencryptedDbPath.absolutePath,
                        "",
                        null,
                        net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
                        null,
                        null
                    )

                unencryptedDb.use { unencryptedDb ->
                    unencryptedDb.execSQL(
                        "ATTACH DATABASE '$safePath' AS encrypted KEY \"x'$passphraseString'\""
                    )
                    unencryptedDb.rawQuery("SELECT sqlcipher_export('encrypted')", null)?.use { }
                    unencryptedDb.execSQL("DETACH DATABASE encrypted")
                }

                unencryptedDbPath.delete()
                tempDbPath.renameTo(unencryptedDbPath)

                true
            } finally {
                passphrase.fill(0)
            }
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "DatabaseSecurityManager",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("operation" to "migrateToEncryptedDatabase")
            )
            tempDbPath.delete()
            // Clear prefs so next launch re-attempts migration rather than misclassifying
            // the still-present unencrypted DB as a corrupt encrypted one.
            prefs.edit().remove(passphraseKey).remove(ivKey).commit()
            false
        }
    }

    companion object {
        private val secureRandom = java.security.SecureRandom()

        private fun deviceProtectedPrefs(context: Context): SharedPreferences {
            val deContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
            } else {
                context
            }
            val dePrefs = deContext.getSharedPreferences("urik_db_prefs", Context.MODE_PRIVATE)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                !dePrefs.contains("encrypted_passphrase")
            ) {
                val cePrefs = context.getSharedPreferences("urik_db_prefs", Context.MODE_PRIVATE)
                val encPassphrase = cePrefs.getString("encrypted_passphrase", null)
                val iv = cePrefs.getString("encryption_iv", null)
                if (encPassphrase != null && iv != null) {
                    dePrefs.edit()
                        .putString("encrypted_passphrase", encPassphrase)
                        .putString("encryption_iv", iv)
                        .commit()
                    cePrefs.edit()
                        .remove("encrypted_passphrase")
                        .remove("encryption_iv")
                        .commit()
                }
            }

            return dePrefs
        }

        private fun defaultLockScreenCheck(context: Context): Boolean {
            val keyguardManager =
                context.getSystemService(Context.KEYGUARD_SERVICE)
                    as? android.app.KeyguardManager
            return keyguardManager?.isDeviceSecure ?: false
        }
    }
}

package com.urik.keyboard.data.database

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.urik.keyboard.utils.ErrorLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages hardware-backed database encryption using Android Keystore.
 *
 * Architecture:
 * - Master key (AES-256) stored in hardware security module
 * - Database passphrase (32 bytes) encrypted by master key
 * - Device lock screen required (enforces device-level security)
 *
 */
class DatabaseSecurityManager(
    private val context: Context,
) {
    companion object {
        private val secureRandom = java.security.SecureRandom()
    }

    private val keyAlias = "urik_database_master_key"
    private val prefsFile = "urik_db_prefs"
    private val passphraseKey = "encrypted_passphrase"
    private val ivKey = "encryption_iv"

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE)

    /**
     * Checks if device has lock screen configured.
     *
     * Device lock screen required for Android Keystore security guarantees.
     * Without it, Keystore keys vulnerable to offline attacks.
     *
     * @return true if lock screen configured, false otherwise
     */
    fun hasDeviceLockScreen(): Boolean {
        val keyguardManager =
            context.getSystemService(Context.KEYGUARD_SERVICE)
                as? android.app.KeyguardManager
        return keyguardManager?.isDeviceSecure ?: false
    }

    /**
     * Retrieves or generates database encryption passphrase.
     *
     * @return 32-byte passphrase for SQLCipher, or null if no device lock screen
     */
    fun getDatabasePassphrase(): ByteArray? {
        if (!hasDeviceLockScreen()) {
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
                context = mapOf("operation" to "getDatabasePassphrase"),
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
                return generateAndStorePassphrase()
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
                context = mapOf("operation" to "retrieveStoredPassphrase"),
            )
            null
        }
    }

    private fun generateMasterKey(): SecretKey {
        val keyGenerator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore",
            )

        val keyGenParameterSpec =
            KeyGenParameterSpec
                .Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).apply {
                    setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    setKeySize(256)
                    setUserAuthenticationRequired(false)
                }.build()

        keyGenerator.init(keyGenParameterSpec)
        return keyGenerator.generateKey()
    }

    /**
     * Checks if database needs migration from unencrypted to encrypted.
     *
     * @return true if migration needed, false otherwise
     */
    fun shouldMigrateToEncrypted(context: Context): Boolean {
        val dbFile = context.getDatabasePath("keyboard_database")
        return dbFile.exists() && !prefs.contains(passphraseKey) && hasDeviceLockScreen()
    }

    /**
     * Migrates existing unencrypted database to encrypted format.
     *
     * One-way operation. On success, original unencrypted database permanently deleted.
     * On failure, temp deleted and original preserved.
     *
     * @return true if migration successful, false if failed
     */
    fun migrateToEncryptedDatabase(context: Context): Boolean {
        val tempDbPath = context.getDatabasePath("keyboard_database_temp")

        return try {
            val unencryptedDbPath = context.getDatabasePath("keyboard_database")

            if (!unencryptedDbPath.exists()) {
                return false
            }

            val passphrase = generateAndStorePassphrase()
            val passphraseString = passphrase.joinToString("") { "%02x".format(it) }

            val unencryptedDb =
                net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                    unencryptedDbPath.absolutePath,
                    "",
                    null,
                    net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
                    null,
                    null,
                )

            try {
                unencryptedDb.execSQL("ATTACH DATABASE '${tempDbPath.absolutePath}' AS encrypted KEY \"x'$passphraseString'\"")
                unencryptedDb.rawQuery("SELECT sqlcipher_export('encrypted')", null)?.use { }
                unencryptedDb.execSQL("DETACH DATABASE encrypted")
            } finally {
                unencryptedDb.close()
            }

            unencryptedDbPath.delete()
            tempDbPath.renameTo(unencryptedDbPath)

            true
        } catch (e: Exception) {
            ErrorLogger.logException(
                component = "DatabaseSecurityManager",
                severity = ErrorLogger.Severity.CRITICAL,
                exception = e,
                context = mapOf("operation" to "migrateToEncryptedDatabase"),
            )
            tempDbPath.delete()
            false
        }
    }
}

package com.aistudio.alphapulse.data.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.cert.CertificateException
import androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme
import androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme
import android.util.Log

/**
 * Secure credential storage using Android Keystore.
 * 
 * Per SPEC §42-43:
 * - API keys must never be embedded in source code
 * - Private/premium keys (Binance private, Coinglass, Smart Money API, Apify)
 *   shall be stored in Android Keystore with encrypted SharedPreferences
 *   and biometric unlock
 * - Public market data stays keyless (Binance public → no key)
 * - Credential rotation support
 * - The system shall always expose which fallback is active
 */
object KeystoreCredentialStore {

    private const val TAG = "KeystoreCredentialStore"

    /** Attempts to retrieve stored credentials from Keystore-backed EncryptedSharedPreferences.
     *  Returns null if no credentials are stored or Keystore is unavailable.
     */
    fun getStoredCredentials(context: Context): Map<String, String>? {
        try {
            val masterKey = MasterKey.Builder(context).build()

            val preferences = EncryptedSharedPreferences.create(
                context,
                "alpha_pulse_credentials",
                masterKey,
                PrefKeyEncryptionScheme.AES256_SIV,
                PrefValueEncryptionScheme.AES256_GCM
            )

            val credentials = preferences.getAll()
            if (credentials.isEmpty()) return null

            return credentials as Map<String, String>
        } catch (e: IOException) {
            Log.w(TAG, "Keystore credentials unavailable (IO error): ${e.message}")
            return null
        } catch (e: NoSuchAlgorithmException) {
            Log.w(TAG, "Keystore credentials unavailable (no such algorithm): ${e.message}")
            return null
        } catch (e: CertificateException) {
            Log.w(TAG, "Keystore credentials unavailable (certificate error): ${e.message}")
            return null
        }
    }

    /** Stores credentials securely in Android Keystore-backed EncryptedSharedPreferences.
     *  Credentials are encrypted with a MasterKey derived from Keystore.
     */
    fun storeCredentials(context: Context, credentials: Map<String, String>) {
        try {
            val masterKey = MasterKey.Builder(context).build()

            val sharedPrefs = EncryptedSharedPreferences.create(
                context,
                "alpha_pulse_credentials",
                masterKey,
                PrefKeyEncryptionScheme.AES256_SIV,
                PrefValueEncryptionScheme.AES256_GCM
            )

            // Use editor to clear and store credentials
            sharedPrefs.edit().apply {
                clear()
                for ((key, value) in credentials) {
                    putString(key, value)
                }
                apply()
            }

            Log.d(TAG, "Credentials stored securely in Keystore")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to store credentials (IO error): ${e.message}")
        } catch (e: NoSuchAlgorithmException) {
            Log.e(TAG, "Failed to store credentials (no such algorithm): ${e.message}")
        } catch (e: CertificateException) {
            Log.e(TAG, "Failed to store credentials (certificate error): ${e.message}")
        }
    }

    /** Removes all stored credentials from Keystore.
     *  Called when rotating credentials or on user revocation.
     */
    fun clearCredentials(context: Context) {
        try {
            val masterKey = MasterKey.Builder(context).build()

            val sharedPrefs = EncryptedSharedPreferences.create(
                context,
                "alpha_pulse_credentials",
                masterKey,
                PrefKeyEncryptionScheme.AES256_SIV,
                PrefValueEncryptionScheme.AES256_GCM
            )

            // Use editor to clear
            sharedPrefs.edit().apply {
                clear()
                apply()
            }

            Log.d(TAG, "Credentials cleared from Keystore")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to clear credentials (IO error): ${e.message}")
        } catch (e: NoSuchAlgorithmException) {
            Log.e(TAG, "Failed to clear credentials (no such algorithm): ${e.message}")
        } catch (e: CertificateException) {
            Log.e(TAG, "Failed to clear credentials (certificate error): ${e.message}")
        }
    }
}
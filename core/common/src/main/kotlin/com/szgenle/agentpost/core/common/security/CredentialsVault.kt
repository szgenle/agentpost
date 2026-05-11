package com.szgenle.agentpost.core.common.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 邮箱凭据保险箱。
 *
 * 基于 [EncryptedSharedPreferences]：
 * - Key：业务层提供的 credentialKey（通常是 Account.id）
 * - Value：明文密码 / App Password / OAuth Token
 *
 * Account 表只存 credentialKey 这个引用，不存明文；本类是唯一明文出入口。
 */
class CredentialsVault internal constructor(
    private val prefs: SharedPreferences,
) {

    fun put(key: String, secret: String) {
        prefs.edit().putString(key, secret).apply()
    }

    fun get(key: String): String? = prefs.getString(key, null)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    companion object {
        private const val PREF_FILE = "agentpost_credentials"

        /**
         * 加密 zip 附件的主解密密码统一存储 key。
         * 与 Account 凭据共用同一个保险箱（EncryptedSharedPreferences），不新开存储。
         */
        const val ZIP_MASTER_KEY: String = "__zip_decrypt_master__"

        /**
         * 由 ServiceLocator / Application 调用构造，失败直接抛异常
         * （MasterKey 初始化走 Android Keystore，极少失败）。
         */
        fun create(context: Context): CredentialsVault {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context.applicationContext,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return CredentialsVault(prefs)
        }
    }
}

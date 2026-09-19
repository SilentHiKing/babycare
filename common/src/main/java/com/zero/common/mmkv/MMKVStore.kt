package com.zero.common.mmkv

import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

/**
 * 商用级 MMKV 封装
 * 支持：多空间 / 自动序列化 / AES 加密 / 泛型读取
 */
object MMKVStore {

    init {
        MMKV.initialize(Utils.getApp())
    }

    private val gson = Gson()

    /**
     * 默认主空间（global）
     */
    private val default by lazy {
        MMKV.defaultMMKV()
    }

    /**
     * 获取指定命名空间（多用户、多模块支持）
     */
    fun withSpace(space: String): MMKV {
        return MMKV.mmkvWithID(space)
    }

    /**
     * 通用保存（自动类型识别）
     */
    fun put(key: String, value: Any?, space: String? = null, encrypt: Boolean = false) {
        val kv = space?.let { withSpace(it) } ?: default

        when (value) {
            null -> kv.removeValueForKey(key)
            is String -> {
                val processedValue = if (encrypt) encryptAES(value) else value
                kv.encode(key, processedValue)
            }

            is Boolean -> kv.encode(key, value)
            is Int -> kv.encode(key, value)
            is Long -> kv.encode(key, value)
            is Float -> kv.encode(key, value)
            is Double -> kv.encode(key, value)
            else -> {
                val json = gson.toJson(value)
                val processedValue = if (encrypt) encryptAES(json) else json
                kv.encode(key, processedValue)
            }
        }
    }

    /**
     * 通用读取（自动类型推断）
     * 移除了 inline 和 reified，改为显式类型参数
     */
    fun <T> get(
        key: String,
        type: Class<T>,
        defValue: T? = null,
        space: String? = null,
        decrypt: Boolean = false
    ): T? {
        val kv = space?.let { withSpace(it) } ?: default

        return when (type) {
            String::class.java -> {
                val v = kv.decodeString(key, defValue as? String ?: "")
                val raw = if (decrypt && !v.isNullOrEmpty()) {
                    decryptAES(v) ?: return defValue
                } else {
                    v
                }
                @Suppress("UNCHECKED_CAST")
                raw as? T ?: defValue
            }

            Boolean::class.java -> {
                val defaultValue = defValue as? Boolean ?: false
                @Suppress("UNCHECKED_CAST")
                kv.decodeBool(key, defaultValue) as T
            }

            Int::class.java -> {
                val defaultValue = defValue as? Int ?: 0
                @Suppress("UNCHECKED_CAST")
                kv.decodeInt(key, defaultValue) as T
            }

            Long::class.java -> {
                val defaultValue = defValue as? Long ?: 0L
                @Suppress("UNCHECKED_CAST")
                kv.decodeLong(key, defaultValue) as T
            }

            Float::class.java -> {
                val defaultValue = defValue as? Float ?: 0f
                @Suppress("UNCHECKED_CAST")
                kv.decodeFloat(key, defaultValue) as T
            }

            Double::class.java -> {
                val defaultValue = defValue as? Double ?: 0.0
                @Suppress("UNCHECKED_CAST")
                kv.decodeDouble(key, defaultValue) as T
            }

            else -> {
                val json = kv.decodeString(key, null)
                if (json.isNullOrEmpty()) {
                    defValue
                } else {
                    val content = if (decrypt) decryptAES(json) ?: return defValue else json
                    runCatching { gson.fromJson(content, type) }.getOrNull() ?: defValue
                }
            }
        }
    }

    /**
     * 为常用类型提供便捷方法，避免显式传递 Class 参数
     */
    fun getString(
        key: String,
        defValue: String? = null,
        space: String? = null,
        decrypt: Boolean = false
    ): String? {
        return get(key, String::class.java, defValue, space, decrypt)
    }

    fun getBoolean(key: String, defValue: Boolean = false, space: String? = null): Boolean {
        return get(key, Boolean::class.java, defValue, space) ?: defValue
    }

    fun getInt(key: String, defValue: Int = 0, space: String? = null): Int {
        return get(key, Int::class.java, defValue, space) ?: defValue
    }

    fun getLong(key: String, defValue: Long = 0L, space: String? = null): Long {
        return get(key, Long::class.java, defValue, space) ?: defValue
    }

    fun getFloat(key: String, defValue: Float = 0f, space: String? = null): Float {
        return get(key, Float::class.java, defValue, space) ?: defValue
    }

    fun getDouble(key: String, defValue: Double = 0.0, space: String? = null): Double {
        return get(key, Double::class.java, defValue, space) ?: defValue
    }

    /**
     * 移除 key
     */
    fun remove(key: String, space: String? = null) {
        val kv = space?.let { withSpace(it) } ?: default
        kv.removeValueForKey(key)
    }

    /** 跨存储提交前同步保存对象；调用方必须在 IO 线程执行，写入失败不能继续数据库提交。 */
    fun putDurable(key: String, value: Any, space: String? = null, encrypt: Boolean = false) {
        val kv = space?.let { withSpace(it) } ?: default
        val json = gson.toJson(value)
        val encoded = if (encrypt) encryptAES(json) else json
        check(kv.encode(key, encoded)) { "持久状态写入失败" }
        // encode 仅更新映射内存；同步刷盘为后续 Room 事务建立明确的先后顺序。
        kv.sync()
    }

    /** 按前缀清理派生状态，避免宝宝删除或数据域替换后遗留动态 key。 */
    fun removeByPrefix(prefix: String, space: String? = null) {
        val kv = space?.let { withSpace(it) } ?: default
        kv.allKeys()
            ?.filter { it.startsWith(prefix) }
            ?.forEach(kv::removeValueForKey)
    }

    /**
     * 清空指定空间
     */
    fun clear(space: String? = null) {
        val kv = space?.let { withSpace(it) } ?: default
        kv.clearAll()
    }

    // 敏感值使用 Android Keystore 中不可导出的密钥；每次写入由 Cipher 生成独立随机 IV。
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "babycare_mmkv_sensitive_v1"
    private const val ENCRYPTION_VERSION = "v1"
    private const val GCM_TAG_BITS = 128

    private fun encryptAES(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val ivText = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val dataText = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$ENCRYPTION_VERSION.$ivText.$dataText"
    }

    private fun decryptAES(encrypted: String): String? {
        return runCatching {
            val parts = encrypted.split('.', limit = 3)
            require(parts.size == 3 && parts[0] == ENCRYPTION_VERSION)
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipherText = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}

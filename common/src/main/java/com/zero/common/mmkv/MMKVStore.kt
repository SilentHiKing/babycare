package com.zero.common.mmkv

import android.util.Base64
import com.blankj.utilcode.util.Utils
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
                val raw = if (decrypt && v != null && v.isNotEmpty()) decryptAES(v) else v
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
                    val content = if (decrypt) decryptAES(json) else json
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

    /**
     * 清空指定空间
     */
    fun clear(space: String? = null) {
        val kv = space?.let { withSpace(it) } ?: default
        kv.clearAll()
    }

    // -------------------
    // AES 加解密部分（可换为自定义算法）
    // -------------------

    private const val AES_KEY = "A1B2C3D4E5F6G7H8"  // ⚠️ 请务必替换为你自己的16位密钥
    private const val AES_IV = "1122334455667788"   // 16位 IV

    private fun encryptAES(plain: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec: SecretKey = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv)
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            plain // 加密失败时返回原文
        }
    }

    private fun decryptAES(encrypted: String): String {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec: SecretKey = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val iv = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
            val decoded = Base64.decode(encrypted, Base64.NO_WRAP)
            String(cipher.doFinal(decoded), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            encrypted // 解密失败时返回原文
        }
    }
}
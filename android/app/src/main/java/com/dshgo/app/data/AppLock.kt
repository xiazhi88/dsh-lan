package com.dshgo.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本机支持哪种解锁方式，以及把访问密码安全地存下来。
 *
 * ## 生物识别和加密是两件事，别混
 *
 * `BiometricPrompt` 只回答"是不是本人"，它**不保护任何数据**。而我们的访问密码
 * 必须留在本机（每次解锁都要发给宿主），所以得自己加密 —— 否则一个读过
 * SharedPreferences 的人就拿到了密码，"生物锁"就成了摆设。
 *
 * 这里用 Android Keystore 里的 AES 密钥加密：密钥生成后**不可导出**，
 * 只存在于设备的 TEE/StrongBox 里。APK 被反编译、备份被拷走，都拿不到它。
 *
 * ## 为什么不引 androidx.security:security-crypto
 *
 * 它长期停在 alpha，且在部分国产 ROM 上会抛 keystore 异常。直接调 Keystore
 * 的代码量并不大，行为完全可控。
 */
object AppLock {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "dshgo-lock-key"
    private const val PREFS = "dshgo_lock"
    private const val KEY_PASSWORD = "password_ct"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** 设备支持的解锁方式。 */
    enum class Method {
        /** 指纹或面部 —— 优先 */
        Biometric,

        /** 只有设备密码（PIN / 图案 / 密码），没有生物识别 */
        DeviceCredential,

        /** 什么都不能用 —— 只能每次手输访问密码 */
        None,
    }

    /**
     * 判断支持什么。
     *
     * `BIOMETRIC_STRONG` 而不是 WEAK：WEAK 只保证"识别了个人"，不保证抗伪造 ——
     * 一张照片可能就过了，而这里挡的是网络访问。宁可用设备密码兜底，也不降级。
     */
    fun method(ctx: Context): Method {
        val bm = BiometricManager.from(ctx)
        val strong = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val cred = BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return when {
            bm.canAuthenticate(strong) == BiometricManager.BIOMETRIC_SUCCESS -> Method.Biometric
            bm.canAuthenticate(cred) == BiometricManager.BIOMETRIC_SUCCESS -> Method.DeviceCredential
            else -> Method.None
        }
    }

    /** 给 BiometricPrompt 用的认证器组合。 */
    fun authenticators(ctx: Context): Int = when (method(ctx)) {
        Method.Biometric -> BiometricManager.Authenticators.BIOMETRIC_STRONG
        Method.DeviceCredential -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
        Method.None -> BiometricManager.Authenticators.BIOMETRIC_STRONG
    }

    /** 界面上该怎么称呼这次验证。 */
    fun promptTitle(ctx: Context): String = when (method(ctx)) {
        Method.Biometric -> "验证身份"
        Method.DeviceCredential -> "输入设备密码"
        Method.None -> "验证身份"
    }

    fun promptSubtitle(): String = "解锁 DSH Go 的局域网访问"

    // ------------------------------------------------------------------
    // 密码的存取
    // ------------------------------------------------------------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 有意**不**设 setUserAuthenticationRequired(true)：
                // 那会让密钥在锁屏后不可用，而我们的通知监听、更新检查是后台跑的，
                // 每次都要等用户解锁才能用密钥就太脆了。挡住"拷走文件"这个威胁
                // 靠密钥不可导出就够了 —— 那是这里真正要防的。
                .build(),
        )
        return gen.generateKey()
    }

    fun savePassword(ctx: Context, password: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        // IV 必须和密文一起存 —— GCM 每次加密的 IV 都不同，丢了就解不开
        val packed = cipher.iv + ct
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PASSWORD, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    /** 读回密码；没存过或解不开时返回 null。 */
    fun loadPassword(ctx: Context): String? = runCatching {
        val packedB64 = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PASSWORD, null) ?: return null
        val packed = Base64.decode(packedB64, Base64.NO_WRAP)
        if (packed.size <= IV_BYTES) return null
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ct = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    fun hasPassword(ctx: Context): Boolean = loadPassword(ctx) != null

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_PASSWORD).apply()
    }
}

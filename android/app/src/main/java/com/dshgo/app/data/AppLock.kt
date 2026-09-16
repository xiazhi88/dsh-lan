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

    /**
     * 用户选的解锁方式。
     *
     * 不做成"自动"一条路：设备支持什么和**用户想用什么**是两件事。
     * 有人就是要指纹不要人脸、有人不想用生物识别、有人宁可每次手输密码。
     */
    enum class Mode(val id: String, val label: String) {
        /** 指纹/人脸优先，没有就用设备密码。默认。 */
        Auto("auto", "自动"),

        /** 只认指纹/人脸；识别不成也不给设备密码回退。 */
        Biometric("biometric", "仅指纹/人脸"),

        /** 只用设备密码（不想用生物识别的人）。 */
        Credential("credential", "仅设备密码"),

        /**
         * 不锁本机 —— **每次都要手输访问密码**。
         *
         * 这是最严的一档：本机不存密码，也就没有"被拿起手机就能解开"的问题。
         * 代价是每次打开都要打一遍。
         */
        None("none", "每次输密码");

        companion object {
            fun from(id: String?): Mode =
                entries.firstOrNull { it.id == id } ?: Auto
        }
    }

    private const val KEY_MODE = "unlock_mode"

    fun mode(ctx: Context): Mode = Mode.from(
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null),
    )

    fun setMode(ctx: Context, mode: Mode) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.id).apply()
        // 选了「每次输密码」就把本机存的清掉 —— 留着它和这个选择自相矛盾
        if (mode == Mode.None) clear(ctx)
    }

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
     * ★ 同时接受 STRONG 和 WEAK，**这一点是修出来的**。
     *
     * 原来只认 `BIOMETRIC_STRONG`，理由是"弱生物识别不抗伪造，一张照片可能就过了"。
     * 但实测发现：**国产手机的面部识别绝大多数是 Class 2（弱）**，只有指纹和
     * 极少数 3D 结构光人脸算 STRONG —— 结果是用户配了人脸解锁，App 却判定
     * "没有生物识别"，永远只弹密码框。
     *
     * 重新想清楚威胁模型之后，接受 WEAK 是对的：
     *
     * - 这道门要挡的是「有人拿起你已解锁的手机」；
     * - **真正的安全边界在宿主那边**（访问密码 + 转发端口的闸门），
     *   生物识别只是决定"要不要把本机存的那份密码交出去"；
     * - 不抗伪造的人脸虽然弱，但明显强于"完全不用验证"。
     *
     * 所以顺序是：指纹/强人脸 → 弱人脸 → 设备密码 →（都没有）手输密码。
     */
    fun method(ctx: Context): Method {
        val bm = BiometricManager.from(ctx)
        fun ok(a: Int) = bm.canAuthenticate(a) == BiometricManager.BIOMETRIC_SUCCESS
        val bioOk = ok(BiometricManager.Authenticators.BIOMETRIC_STRONG) ||
            ok(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        val credOk = ok(BiometricManager.Authenticators.DEVICE_CREDENTIAL)

        return when (mode(ctx)) {
            // 只认生物识别：设备没有就退到手输密码，
            // **不给设备密码回退** —— 用户明确说了不要那一档
            Mode.Biometric -> if (bioOk) Method.Biometric else Method.None

            Mode.Credential -> if (credOk) Method.DeviceCredential else Method.None

            Mode.None -> Method.None

            // 自动：指纹/强人脸 → 弱人脸 → 设备密码 → 手输
            Mode.Auto -> when {
                bioOk -> Method.Biometric
                credOk -> Method.DeviceCredential
                else -> Method.None
            }
        }
    }

    /**
     * 给 BiometricPrompt 用的认证器组合。
     *
     * 这里要看**哪一档真的可用**：设备只有弱生物识别时，传 STRONG 进去会立刻
     * 报 `BIOMETRIC_ERROR_NO_BIOMETRICS`，用户点了按钮什么也不发生。
     */
    fun authenticators(ctx: Context): Int {
        val bm = BiometricManager.from(ctx)
        val strong = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        val weak = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS

        return when (mode(ctx)) {
            Mode.Credential -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
            Mode.None -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
            // 自动 / 仅生物识别：优先强，退化到弱；都不可用就给设备密码，
            // 让系统自己弹一个能用的界面，而不是直接失败
            else -> when {
                strong -> BiometricManager.Authenticators.BIOMETRIC_STRONG
                weak -> BiometricManager.Authenticators.BIOMETRIC_WEAK
                else -> BiometricManager.Authenticators.DEVICE_CREDENTIAL
            }
        }
    }

    /**
     * 这台设备上到底有什么 —— 给界面显示用，让用户知道为什么弹的是指纹还是人脸。
     */
    fun describe(ctx: Context): String {
        if (mode(ctx) == Mode.None) return "无"
        val bm = BiometricManager.from(ctx)
        val strong = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
        val weak = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
        val cred = bm.canAuthenticate(BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
            BiometricManager.BIOMETRIC_SUCCESS
        return when (mode(ctx)) {
            Mode.Credential -> if (cred) "设备密码" else "无"
            Mode.Biometric -> if (strong) "指纹或人脸（强）" else if (weak) "指纹或人脸" else "无"
            else -> when {
                strong -> "指纹或人脸（强）"
                weak -> "指纹或人脸"
                cred -> "设备密码"
                else -> "无"
            }
        }
    }

    /** 界面上该怎么称呼这次验证。 */
    fun promptTitle(ctx: Context): String = when (method(ctx)) {
        Method.Biometric -> "用指纹或人脸解锁"
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

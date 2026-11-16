package io.github.ya77papa.shepit.security

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import io.github.ya77papa.shepit.data.Db
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Блокування застосунку PIN-кодом і/або відбитком пальця.
 *
 * PIN не зберігається — лише PBKDF2-SHA256 (210 000 ітерацій) з сіллю. Після 5 помилок —
 * пауза, що подвоюється (30 с … 1 год). Відбиток — системний BiometricPrompt (API 28+),
 * без сторонніх бібліотек.
 *
 * Важливо: це захист від людини, яка тримає розблокований телефон. Ключі акаунта окремо
 * зашифровані Android Keystore і без цього.
 */
class AppLock(private val ctx: Context, private val db: Db) {
    /** Застосунок зараз заблокований (показати екран розблокування). */
    val locked = MutableStateFlow(false)

    private var backgroundAt = 0L
    private var expectReturn = false

    init {
        locked.value = enabled
    }

    val pinEnabled get() = db.get("lock_pin") != null
    val pinLength get() = db.get("lock_pin_len")?.toIntOrNull() ?: 0

    var bioEnabled: Boolean
        get() = db.get("lock_bio") == "1"
        set(v) = db.set("lock_bio", if (v) "1" else "0")

    /** Через скільки після виходу з застосунку блокувати (0 — одразу). */
    var delayMs: Long
        get() = db.get("lock_delay")?.toLongOrNull() ?: 0
        set(v) = db.set("lock_delay", v.toString())

    /** Не показувати вміст у списку застосунків і на скріншотах (FLAG_SECURE). */
    var secureScreen: Boolean
        get() = db.get("lock_secure") == "1"
        set(v) = db.set("lock_secure", if (v) "1" else "0")

    val enabled get() = pinEnabled || bioEnabled

    // ---------- життєвий цикл ----------

    /** Перед тим як відкрити свій системний діалог (вибір фото, файлу) — не блокувати по поверненню. */
    fun expectReturn() { expectReturn = true }

    fun onBackground() {
        backgroundAt = System.currentTimeMillis()
    }

    fun onForeground() {
        if (!enabled) { locked.value = false; return }
        if (expectReturn) { expectReturn = false; return }
        if (backgroundAt != 0L && System.currentTimeMillis() - backgroundAt >= delayMs) locked.value = true
    }

    fun unlock() {
        db.set("lock_failures", "0")
        db.set("lock_until", "0")
        locked.value = false
    }

    // ---------- PIN ----------

    private fun hash(pin: String, salt: ByteArray, iter: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, iter, 256)).encoded

    fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() })
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iter = 210_000
        val b64 = Base64.getEncoder()
        db.set("lock_pin", "$iter$${b64.encodeToString(salt)}$${b64.encodeToString(hash(pin, salt, iter))}")
        db.set("lock_pin_len", pin.length.toString())
    }

    fun clearPin() {
        db.set("lock_pin_len", "0")
        db.wipeKey("lock_pin")
    }

    /** Секунд до наступної спроби (0 — можна). */
    fun waitSeconds(): Long = ((db.get("lock_until")?.toLongOrNull() ?: 0) - System.currentTimeMillis()).coerceAtLeast(0) / 1000

    /** Перевірити PIN (повільно — викликати не з головного потоку). */
    fun checkPin(pin: String): Boolean {
        if (waitSeconds() > 0) return false
        val parts = db.get("lock_pin")?.split('$') ?: return false
        val b64 = Base64.getDecoder()
        val ok = MessageDigest.isEqual(hash(pin, b64.decode(parts[1]), parts[0].toInt()), b64.decode(parts[2]))
        if (ok) return true
        val failures = (db.get("lock_failures")?.toIntOrNull() ?: 0) + 1
        db.set("lock_failures", failures.toString())
        if (failures >= 5) {
            val wait = (30_000L shl (failures - 5).coerceAtMost(7)).coerceAtMost(3_600_000L)
            db.set("lock_until", (System.currentTimeMillis() + wait).toString())
        }
        return false
    }

    // ---------- відбиток ----------

    fun biometricAvailable(): Boolean = when {
        Build.VERSION.SDK_INT >= 30 ->
            ctx.getSystemService(BiometricManager::class.java).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        Build.VERSION.SDK_INT == 29 ->
            @Suppress("DEPRECATION") (ctx.getSystemService(BiometricManager::class.java).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
        Build.VERSION.SDK_INT == 28 -> ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
        else -> false
    }

    /**
     * Показати системне вікно відбитка. Якщо PIN увімкнено — кнопка «Ввести PIN» ([onFallback]);
     * якщо ні — запасний варіант: блокування самого телефона (Android 10+).
     */
    fun promptBiometric(activity: Activity, title: String, onSuccess: () -> Unit, onFallback: () -> Unit) {
        if (Build.VERSION.SDK_INT < 28) return onFallback()
        val exec = activity.mainExecutor
        val b = BiometricPrompt.Builder(activity).setTitle(title)
        if (pinEnabled) {
            b.setNegativeButton("Ввести PIN", exec) { _, _ -> onFallback() }
        } else if (Build.VERSION.SDK_INT >= 30) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else if (Build.VERSION.SDK_INT == 29) {
            @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        } else {
            b.setNegativeButton("Скасувати", exec) { _, _ -> onFallback() }
        }
        runCatching {
            b.build().authenticate(CancellationSignal(), exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(code: Int, msg: CharSequence) = onFallback()
            })
        }.onFailure { onFallback() }
    }
}

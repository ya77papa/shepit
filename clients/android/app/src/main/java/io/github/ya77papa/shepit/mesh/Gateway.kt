package io.github.ya77papa.shepit.mesh

import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Пакет для шлюзу (docs/mesh-concept.md, етап 4): скринька адресата й сам mesh-конверт,
 * зашифровані публічним ключем сервера (ECDH P-256 + AES-256-GCM). Телефон, що несе пакет,
 * не дізнається ні адресата, ні вмісту; сервер — лише адресата (як і для звичайних повідомлень).
 * Формат: ефемерний ключ (65 байт) ‖ IV (12) ‖ шифротекст із тегом.
 */
object Gateway {
    /** Скринька людини на сервері: SHA-256("shepit-mailbox-v0" ‖ ідентичність). */
    fun mailbox(identityHex: String): String {
        val d = MessageDigest.getInstance("SHA-256")
        d.update("shepit-mailbox-v0".toByteArray())
        d.update(hex(identityHex))
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    fun seal(serverKeyHex: String, toIdentity: String, env: MeshEnvelope): ByteArray {
        val plain = JSONObject()
            .put("to", mailbox(toIdentity))
            .put("tag", Base64.getEncoder().encodeToString(env.tag))
            .put("exp", env.expires)
            .put("body", Base64.getEncoder().encodeToString(env.body))
            .toString().toByteArray()
        return encrypt(serverKeyHex, plain)
    }

    fun encrypt(serverKeyHex: String, plain: ByteArray): ByteArray {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val eph = gen.generateKeyPair()
        val params = (eph.public as ECPublicKey).params
        val raw = hex(serverKeyHex)
        require(raw.size == 65 && raw[0] == 4.toByte()) { "ключ шлюзу" }
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        val server = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        val shared = KeyAgreement.getInstance("ECDH").run { init(eph.private); doPhase(server, true); generateSecret() }
        val w = (eph.public as ECPublicKey).w
        val ephRaw = byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
        val key = MessageDigest.getInstance("SHA-256").run { update("shepit-gateway-v1".toByteArray()); update(shared); update(ephRaw); digest() }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            doFinal(plain)
        }
        return ephRaw + iv + ct
    }

    /** Те, що сервер поклав у скриньку (payload конверта з wake = "mesh"), → mesh-конверт. */
    fun unwrap(payload: ByteArray): MeshEnvelope? = runCatching {
        val j = JSONObject(String(payload))
        MeshEnvelope(Base64.getDecoder().decode(j.getString("tag")), j.getLong("exp"), Base64.getDecoder().decode(j.getString("body")))
    }.getOrNull()

    private fun fixed(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

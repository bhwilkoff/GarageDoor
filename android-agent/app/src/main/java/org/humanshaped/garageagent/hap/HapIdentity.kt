package org.humanshaped.garageagent.hap

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Pairing(val id: String, val publicKey: ByteArray, val admin: Boolean)

/**
 * The accessory's long-term identity and its paired controllers. Holds the
 * Ed25519 secret, so it lives only in an AES-GCM file whose key never
 * leaves the Android Keystore.
 */
class HapIdentity private constructor(
    private val file: File,
    val deviceId: String,
    val secretSeed: ByteArray,
    val setupCode: String,
    var configNumber: Int,
    var dbHash: String,
    pairings: List<Pairing>,
) {
    private val pairingList = pairings.toMutableList()
    val publicKey: ByteArray = Ed25519.publicKey(secretSeed)

    @get:Synchronized
    val pairings: List<Pairing> get() = pairingList.toList()

    @get:Synchronized
    val isPaired: Boolean get() = pairingList.isNotEmpty()

    @Synchronized
    fun find(id: String): Pairing? = pairingList.firstOrNull { it.id == id }

    @Synchronized
    fun upsert(p: Pairing) {
        pairingList.removeAll { it.id == p.id }
        pairingList.add(p)
        save()
    }

    @Synchronized
    fun remove(id: String) {
        pairingList.removeAll { it.id == id }
        if (pairingList.none { it.admin }) pairingList.clear()
        save()
    }

    @Synchronized
    fun clearPairings() {
        pairingList.clear()
        save()
    }

    @Synchronized
    fun save() {
        val json = JSONObject()
            .put("deviceId", deviceId)
            .put("seed", secretSeed.hex())
            .put("setupCode", setupCode)
            .put("configNumber", configNumber)
            .put("dbHash", dbHash)
            .put("pairings", JSONArray(pairingList.map {
                JSONObject().put("id", it.id).put("pk", it.publicKey.hex()).put("admin", it.admin)
            }))
        Vault.write(file, json.toString().toByteArray())
    }

    companion object {
        private val INVALID_CODES = setOf(
            "00000000", "11111111", "22222222", "33333333", "44444444", "55555555",
            "66666666", "77777777", "88888888", "99999999", "12345678", "87654321",
        )

        fun load(context: Context, fileName: String = "hap-identity.bin"): HapIdentity {
            val file = File(context.filesDir, fileName)
            Vault.read(file)?.let { bytes ->
                val j = JSONObject(String(bytes))
                val arr = j.getJSONArray("pairings")
                val list = (0 until arr.length()).map {
                    val p = arr.getJSONObject(it)
                    Pairing(p.getString("id"), p.getString("pk").unhex(), p.getBoolean("admin"))
                }
                return HapIdentity(
                    file, j.getString("deviceId"), j.getString("seed").unhex(),
                    j.getString("setupCode"), j.getInt("configNumber"), j.optString("dbHash"), list,
                )
            }
            val id = randomBytes(6).joinToString(":") { "%02X".format(it) }
            var digits: String
            do {
                digits = (1..8).map { secureRandom.nextInt(10) }.joinToString("")
            } while (digits in INVALID_CODES)
            val code = "${digits.substring(0, 3)}-${digits.substring(3, 5)}-${digits.substring(5)}"
            return HapIdentity(file, id, randomBytes(32), code, 1, "", emptyList()).also { it.save() }
        }
    }
}

object Vault {
    private const val ALIAS = "garage_agent_vault"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun write(file: File, plain: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(byteArrayOf(iv.size.toByte()) + iv + ct)
        tmp.renameTo(file)
    }

    fun read(file: File): ByteArray? {
        if (!file.exists()) return null
        val all = file.readBytes()
        val ivLen = all[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 1, ivLen))
        return c.doFinal(all, 1 + ivLen, all.size - 1 - ivLen)
    }
}

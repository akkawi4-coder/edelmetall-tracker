package de.akkaoui.edelmetall.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Geräteverschlüsselung mit AES-256-GCM. Der Schlüssel liegt im Android Keystore –
 * wenn vorhanden im separaten Sicherheitschip (StrongBox), sonst in der hardwaregeschützten TEE.
 * Er verlässt das Gerät nie und kann auch mit Root-Zugriff nicht ausgelesen werden.
 * GCM schützt zusätzlich vor Manipulation: veränderte Dateien werden erkannt und nicht geladen.
 */
object DeviceCrypto {
    private const val ALIAS = "edelmetall_vault_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile private var cached: SecretKey? = null

    fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val k = (ks.getKey(ALIAS, null) as? SecretKey) ?: generate()
            cached = k
            return k
        }
    }

    private fun generate(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            gen.init(spec(strongBox = true))
            gen.generateKey()
        } catch (e: Exception) {
            // Kein StrongBox-Chip im Gerät: Schlüssel in der TEE erzeugen
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(data: ByteArray, offset: Int = 0): ByteArray {
        val iv = data.copyOfRange(offset, offset + 12)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data, offset + 12, data.size - offset - 12)
    }

    fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    /** Atomar schreiben: erst Temp-Datei, dann umbenennen – kein halbes File bei Absturz. */
    fun writeAtomic(file: File, bytes: ByteArray) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/**
 * Verschlüsselte Textdatei (Portfolio, Einstellungen, Kurse).
 * Format v2: "EM2" + IV + AES-GCM(gzip(Text)) – komprimiert spart Speicher.
 * Ältere Dateien (v1: IV + AES-GCM(Text)) werden weiter gelesen und beim nächsten Speichern umgestellt.
 */
class CryptoStore(private val file: File) {

    private companion object {
        val MAGIC = "EM2".toByteArray(Charsets.US_ASCII)
    }

    /** Liefert den Klartext oder null, wenn noch nichts gespeichert ist. */
    fun read(): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val v2 = runCatching {
            if (bytes.size > 15 && bytes.copyOfRange(0, 3).contentEquals(MAGIC)) {
                String(DeviceCrypto.gunzip(DeviceCrypto.decrypt(bytes, 3)), Charsets.UTF_8)
            } else null
        }.getOrNull()
        if (v2 != null) return v2
        return try {
            String(DeviceCrypto.decrypt(bytes, 0), Charsets.UTF_8)
        } catch (e: Exception) {
            // Nicht lesbar (Schlüssel verloren oder Datei manipuliert): beiseitelegen statt still zu überschreiben.
            file.renameTo(File(file.path + ".unreadable-" + System.currentTimeMillis()))
            null
        }
    }

    fun write(text: String) {
        DeviceCrypto.writeAtomic(file, MAGIC + DeviceCrypto.encrypt(DeviceCrypto.gzip(text.toByteArray(Charsets.UTF_8))))
    }

    fun exists(): Boolean = file.exists()
}

/**
 * Verschlüsselte Fotos (eigene Produktbilder). Format: "EMP" + IV + AES-GCM(JPEG).
 * Alte unverschlüsselte JPEGs werden erkannt und beim Start automatisch verschlüsselt.
 */
object SecureFiles {
    private val MAGIC = "EMP".toByteArray(Charsets.US_ASCII)

    fun isEncrypted(f: File): Boolean = runCatching {
        f.inputStream().use { val h = ByteArray(3); it.read(h) == 3 && h.contentEquals(MAGIC) }
    }.getOrDefault(false)

    fun write(f: File, plain: ByteArray) = DeviceCrypto.writeAtomic(f, MAGIC + DeviceCrypto.encrypt(plain))

    /** Klartext-Bytes; null wenn nicht lesbar. */
    fun read(f: File): ByteArray? = runCatching {
        val b = f.readBytes()
        if (b.size > 3 && b.copyOfRange(0, 3).contentEquals(MAGIC)) DeviceCrypto.decrypt(b, 3) else b
    }.getOrNull()

    /** Verschlüsselt alle noch offenen Dateien eines Ordners. */
    fun encryptLegacy(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && !f.name.endsWith(".tmp") && !isEncrypted(f)) runCatching { write(f, f.readBytes()) }
        }
    }
}

/**
 * Verschlüsseltes Backup mit eigenem Passwort (PBKDF2-SHA256 + AES-256-GCM).
 * EMT2 = 310.000 Iterationen (OWASP-Empfehlung), EMT1 (120.000) wird weiterhin gelesen.
 */
object BackupCrypto {
    private val MAGIC_V1 = "EMT1".toByteArray(Charsets.US_ASCII)
    private val MAGIC_V2 = "EMT2".toByteArray(Charsets.US_ASCII)

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt, 310_000), GCMParameterSpec(128, iv))
        return MAGIC_V2 + salt + iv + cipher.doFinal(DeviceCrypto.gzip(plain))
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        require(data.size > 4 + 16 + 12) { "Keine gültige Backup-Datei" }
        val magic = data.copyOfRange(0, 4)
        val v2 = magic.contentEquals(MAGIC_V2)
        require(v2 || magic.contentEquals(MAGIC_V1)) { "Keine gültige Backup-Datei" }
        val salt = data.copyOfRange(4, 20)
        val iv = data.copyOfRange(20, 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, derive(password, salt, if (v2) 310_000 else 120_000), GCMParameterSpec(128, iv))
        val plain = cipher.doFinal(data, 32, data.size - 32)
        return if (v2) DeviceCrypto.gunzip(plain) else plain
    }
}

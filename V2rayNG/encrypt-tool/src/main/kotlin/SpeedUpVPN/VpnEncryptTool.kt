package SpeedUpVPN

import java.io.File
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object VpnEncryptTool {
    // Must match app VpnEncrypt.theKey. Replace before encrypting subscriptions.
    private const val theKey = "YOUR_32_CHAR_AES_SECRET_KEY_HERE"

    fun aesEncrypt(v: String, secretKey: String = theKey) = AES256.encrypt(v, secretKey)
    fun aesDecrypt(v: String, secretKey: String = theKey) = AES256.decrypt(v, secretKey)
    fun readFileAsTextUsingInputStream(fileName: String) =
        File(fileName).inputStream().readBytes().toString(Charsets.UTF_8)

    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.getOrNull(0) ?: "D:\\fanqiang\\android\\release\\py\\"
        val filenames = if (args.size > 1) args.drop(1) else listOf("vsp-en.py", "vsp-cn.py")

        for (name in filenames) {
            val filename = path + name
            val strToEncrypt = readFileAsTextUsingInputStream("$filename.src")
            println(strToEncrypt)
            val encryptedValue = aesEncrypt(strToEncrypt)
            println(encryptedValue)
            File(filename).writeText(encryptedValue)
            val decryptedValue: String = aesDecrypt(encryptedValue)
            println(decryptedValue)
        }
    }
}

private object AES256 {
    private fun cipher(opmode: Int, secretKey: String): Cipher {
        require(secretKey.length == 32) { "SecretKey length is not 32 chars" }
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val sk = SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "AES")
        val iv = IvParameterSpec(secretKey.substring(0, 16).toByteArray(Charsets.UTF_8))
        c.init(opmode, sk, iv)
        return c
    }

    fun encrypt(str: String, secretKey: String): String {
        val encrypted = cipher(Cipher.ENCRYPT_MODE, secretKey).doFinal(str.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    fun decrypt(str: String, secretKey: String): String {
        val byteStr = Base64.getDecoder().decode(str.toByteArray(Charsets.UTF_8))
        return String(cipher(Cipher.DECRYPT_MODE, secretKey).doFinal(byteStr))
    }
}


package com.tstc.zstangshi.shared.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// JVM 实现：本地跑断言用，逻辑与 Android 上的 actual 完全一致。
actual fun md5(input: ByteArray): ByteArray =
    MessageDigest.getInstance("MD5").digest(input)

actual fun aesEcbPkcs5Decrypt(cipherBytes: ByteArray, key: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
    return cipher.doFinal(cipherBytes)
}

package com.tstc.zstangshi;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * SecurePrefs —— AndroidKeyStore AES-256/GCM 加密后存 SharedPreferences（框架 API，零依赖）。
 *
 * §8 共享知识第 10 条：密码只在内存里，落盘的凭据必须加密；换机 / 清 key / 数据损坏时
 * decrypt 失败返回 null，由 JS 侧重新登录，绝不抛异常把 WebView 弄崩。
 * 存储格式：Base64(iv) ":" Base64(ciphertext)，IV 由 Cipher.getIV() 生成（GCM 12 字节）。
 */
public final class SecurePrefs {

    private static final String PREFS_NAME = "zsts.secure";
    private static final String KEY_CREDENTIAL = "credential";
    private static final String KEY_ALIAS = "zsts_credential_aes";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private final SharedPreferences prefs;

    public SecurePrefs(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 加密保存明文 JSON，成功返回 true。 */
    public boolean save(String plain) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(String.valueOf(plain).getBytes(StandardCharsets.UTF_8));
            String stored = Base64.encodeToString(iv, Base64.NO_WRAP)
                    + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            prefs.edit().putString(KEY_CREDENTIAL, stored).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解密读取；失败（换机/清 key/损坏）清掉脏数据并返回 null。 */
    public String load() {
        try {
            String stored = prefs.getString(KEY_CREDENTIAL, null);
            if (stored == null) return null;
            String[] parts = stored.split(":", 2);
            if (parts.length != 2) throw new IllegalArgumentException("凭据格式损坏");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            clear();
            return null;
        }
    }

    /** 清除凭据（不删 KeyStore 里的 key，下次 save 可继续用）。 */
    public boolean clear() {
        prefs.edit().remove(KEY_CREDENTIAL).apply();
        return true;
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            SecretKey existing = (SecretKey) store.getKey(KEY_ALIAS, null);
            if (existing != null) return existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}

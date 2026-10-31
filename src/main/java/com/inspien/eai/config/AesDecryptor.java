package com.inspien.eai.config;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

// 과제 API 응답의 접속 정보 복호화 (AES-128 ECB, 키 = 전화번호 SHA-1 앞 16바이트)
public class AesDecryptor {

    public String decrypt(String base64Cipher, String phoneNumber) {
        try {
            // 전화번호 SHA-1 해시의 앞 16바이트 → AES-128 키
            byte[] hash = MessageDigest.getInstance("SHA-1")
                    .digest(phoneNumber.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec key = new SecretKeySpec(Arrays.copyOf(hash, 128 / 8), "AES");

            // 응답 값은 Base64 → 디코딩 후 복호화
            byte[] cipherBytes = Base64.getDecoder().decode(base64Cipher);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, key);
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 키가 틀리면 패딩 검사에서 실패한다 (BadPaddingException)
            throw new IllegalStateException("접속 정보 복호화 실패", e);
        }
    }
}

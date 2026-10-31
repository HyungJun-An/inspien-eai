package com.inspien.eai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesDecryptorTest {

    // 실제 전화번호 대신 가짜 번호 사용
    private static final String PHONE = "010-0000-0000";

    private final AesDecryptor decryptor = new AesDecryptor();

    @Test
    @DisplayName("같은 전화번호로 암호화한 값을 복호화한다")
    void decryptsTextEncryptedWithSamePhone() throws Exception {
        String original = "jdbc:oracle:thin:@localhost:1521:TEST";

        String result = decryptor.decrypt(encrypt(original, PHONE), PHONE);

        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("한글이 들어간 값도 복호화한다")
    void decryptsKoreanText() throws Exception {
        String original = "Recruit/2026/안형준";

        String result = decryptor.decrypt(encrypt(original, PHONE), PHONE);

        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("전화번호가 다르면 복호화에 실패한다")
    void failsWithDifferentPhone() throws Exception {
        String encrypted = encrypt("jdbc:oracle:thin:@localhost:1521:TEST", PHONE);

        assertThatThrownBy(() -> decryptor.decrypt(encrypted, "010-9999-9999"))
                .isInstanceOf(IllegalStateException.class);
    }

    // 과제 서버와 같은 방식으로 암호화 (테스트 데이터 준비용)
    private String encrypt(String text, String phone) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-1").digest(phone.getBytes(StandardCharsets.UTF_8));
        SecretKeySpec key = new SecretKeySpec(Arrays.copyOf(hash, 16), "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
}

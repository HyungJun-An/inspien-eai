package com.inspien.eai.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdGeneratorTest {

    @Test
    @DisplayName("첫 ID는 A001이다")
    void startsFromA001() {
        assertThat(IdGenerator.next(null)).isEqualTo("A001");
    }

    @Test
    @DisplayName("숫자를 1씩 올리고 3자리를 유지한다")
    void increasesNumber() {
        assertThat(IdGenerator.next("A001")).isEqualTo("A002");
        assertThat(IdGenerator.next("A009")).isEqualTo("A010");
        assertThat(IdGenerator.next("A099")).isEqualTo("A100");
    }

    @Test
    @DisplayName("A999 다음은 B001이다")
    void movesToNextLetterAfter999() {
        assertThat(IdGenerator.next("A999")).isEqualTo("B001");
    }

    @Test
    @DisplayName("Z999 다음은 만들 수 없어 예외가 난다")
    void failsAfterZ999EvenIfStringIsNewObject() {
        String fromDb = new String("Z999");

        assertThatThrownBy(() -> IdGenerator.next(fromDb))
                .isInstanceOf(IllegalStateException.class);
    }
}

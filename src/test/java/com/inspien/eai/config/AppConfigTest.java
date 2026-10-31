package com.inspien.eai.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppConfigTest {

    @Test
    @DisplayName("필수 설정이 없으면 어떤 키가 빠졌는지 알려 주고 멈춘다")
    void failsWithKeyNameWhenRequiredValueIsMissing() {
        AppConfig config = new AppConfig(new Properties());

        assertThatThrownBy(() -> config.get("inspien.api.phone"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inspien.api.phone");
    }

    @Test
    @DisplayName("스위치·숫자 설정은 값이 없으면 기본값을 쓴다")
    void usesDefaultWhenOptionalValueIsMissing() {
        Properties properties = new Properties();
        properties.setProperty("inspien.shipment.enabled", "true");
        AppConfig config = new AppConfig(properties);

        assertThat(config.getBoolean("inspien.shipment.enabled", false)).isTrue();
        assertThat(config.getBoolean("inspien.ftp.upload-enabled", false)).isFalse();
        assertThat(config.getInt("inspien.shipment.delay-minutes", 5)).isEqualTo(5);
    }

    @Test
    @DisplayName("실제 설정 파일에서 되돌릴 수 없는 작업 스위치는 꺼져 있다")
    void switchesAreOffInRealConfig() {
        AppConfig config = AppConfig.load();

        assertThat(config.getBoolean("inspien.ftp.upload-enabled", true)).isFalse();
        assertThat(config.getBoolean("inspien.shipment.enabled", true)).isFalse();
    }
}

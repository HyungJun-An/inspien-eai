package com.inspien.eai.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

// 설정값. application.properties 를 읽고, application-local.properties(비밀값, git 제외)가 있으면 덮어쓴다
public class AppConfig {

    private final Properties properties;

    public AppConfig(Properties properties) {
        this.properties = properties;
    }

    // 클래스패스의 application.properties + application-local.properties
    public static AppConfig load() {
        Properties properties = new Properties();
        if (!readInto(properties, "application.properties")) {
            throw new IllegalStateException("application.properties 가 없습니다");
        }
        readInto(properties, "application-local.properties"); // 없어도 된다 (테스트·CI 환경)
        return new AppConfig(properties);
    }

    // 없으면 시작할 수 없는 값. 없으면 어떤 키가 빠졌는지 알려 주고 멈춘다
    public String get(String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("설정값이 없습니다: " + key
                    + " (비밀값은 application-local.properties 에 넣어야 합니다)");
        }
        return value.trim();
    }

    public String get(String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    public int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        return value == null ? defaultValue : Integer.parseInt(value.trim());
    }

    // 파일이 없으면 false. 이름(안형준) 같은 한글이 깨지지 않도록 UTF-8 로 읽는다
    // (Properties.load(InputStream) 은 ISO-8859-1 로 읽어서 한글이 깨진다)
    private static boolean readInto(Properties properties, String fileName) {
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(fileName)) {
            if (in == null) {
                return false;
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("설정 파일 읽기 실패: " + fileName, e);
        }
    }
}

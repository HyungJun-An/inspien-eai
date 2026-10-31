package com.inspien.eai.config;

import java.util.Properties;

// 설정값. application.properties 를 읽고, application-local.properties(비밀값, git 제외)가 있으면 덮어쓴다
public class AppConfig {

    public AppConfig(Properties properties) {
    }

    // 클래스패스의 application.properties + application-local.properties
    public static AppConfig load() {
        throw new UnsupportedOperationException("TODO");
    }

    // 없으면 시작할 수 없는 값. 없으면 어떤 키가 빠졌는지 알려 주고 멈춘다
    public String get(String key) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        throw new UnsupportedOperationException("TODO");
    }

    public int getInt(String key, int defaultValue) {
        throw new UnsupportedOperationException("TODO");
    }
}

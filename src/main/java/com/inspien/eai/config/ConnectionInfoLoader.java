package com.inspien.eai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

// 시작할 때 과제 API 를 호출해 접속 정보를 받고, 전화번호로 복호화한다.
// DB·FTP 비밀번호는 디스크·git 에 남지 않는다.
public class ConnectionInfoLoader {

    private static final Logger log = LoggerFactory.getLogger(ConnectionInfoLoader.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final AesDecryptor decryptor = new AesDecryptor();
    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    // 설정값으로 API 를 호출하고 복호화까지. 접속 정보가 없으면 아무것도 할 수 없으므로 실패하면 시작을 멈춘다
    public ConnectionInfo load(AppConfig config) {
        String phone = config.get("inspien.api.phone");
        String response = fetch(config.get("inspien.api.url"),
                config.get("inspien.api.username"), config.get("inspien.api.password"),
                config.get("inspien.applicant.name"), phone, config.get("inspien.api.email"));
        ConnectionInfo info = parse(response, phone);
        log.info("접속 정보 수신 완료: {}", info); // toString 에 비밀번호 없음
        return info;
    }

    // POST {NAME, PHONE_NUMBER, E_MAIL} + Basic 인증 → 응답 JSON. 200 이 아니면 IllegalStateException
    String fetch(String url, String username, String password, String name, String phone, String email) {
        String body = jsonMapper.writeValueAsString(Map.of("NAME", name, "PHONE_NUMBER", phone, "E_MAIL", email));
        String basicAuth = Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT) // 서버가 응답하지 않아도 시작이 끝없이 멈춰 있지 않게
                .header("Authorization", "Basic " + basicAuth)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("과제 API 호출 실패: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("과제 API 호출 중단: " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("과제 API 응답 오류: HTTP " + response.statusCode() + " (" + url + ")");
        }
        return response.body();
    }

    // 응답 JSON → 접속 정보. *_CONN 안의 값은 필드마다 따로 암호화(Base64)되어 있다.
    // ORDER_TB 와 SHIPMENT_TB 는 같은 DB·계정이라 ORDER_TB_CONN 만 읽는다.
    // 복호화 실패면 IllegalStateException (AesDecryptor)
    ConnectionInfo parse(String responseJson, String phone) {
        JsonNode root = jsonMapper.readTree(responseJson);
        JsonNode db = root.required("ORDER_TB_CONN");
        JsonNode ftp = root.required("FTP_CONN");

        return new ConnectionInfo(
                decrypt(db, "URL", phone),
                decrypt(db, "ID", phone),
                decrypt(db, "PASSWORD", phone),
                decrypt(ftp, "URL", phone),
                Integer.parseInt(decrypt(ftp, "PORT", phone)),
                decrypt(ftp, "ID", phone),
                decrypt(ftp, "PASSWORD", phone),
                decrypt(ftp, "PATH", phone));
    }

    private String decrypt(JsonNode conn, String field, String phone) {
        return decryptor.decrypt(conn.required(field).asString(), phone);
    }
}

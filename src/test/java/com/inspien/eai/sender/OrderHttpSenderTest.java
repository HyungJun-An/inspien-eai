package com.inspien.eai.sender;

import com.inspien.eai.common.InvalidOrderException;
import com.inspien.eai.flow.OrderReceiveFlow;
import com.inspien.eai.flow.ReceiveResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 흐름(OrderReceiveFlow)은 mock 으로 두고, 실제로 서버를 띄워 HTTP 요청/응답을 확인한다
class OrderHttpSenderTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private OrderReceiveFlow flow;
    private OrderHttpServer server;

    @BeforeEach
    void startServer() {
        flow = mock(OrderReceiveFlow.class);
        server = new OrderHttpServer(flow);
        server.start(0); // 비어 있는 아무 포트
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @DisplayName("처리 결과를 200 JSON으로 응답한다")
    void returnsReceiveResultAsJson() throws Exception {
        when(flow.receive(any())).thenReturn(new ReceiveResult(List.of("A004", "A005"), List.of("USER99/ITEM99"),
                List.of("안형준_A004.txt", "안형준_A005.txt")));

        HttpResponse<String> response = post("/orders", "<HEADER/>".getBytes(EUC_KR));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).contains("application/json"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("savedOrderIds").get(0).asString()).isEqualTo("A004");
        assertThat(body.get("savedOrderIds").get(1).asString()).isEqualTo("A005");
        assertThat(body.get("skipped").get(0).asString()).isEqualTo("USER99/ITEM99");
        assertThat(body.get("uploadedReceipts").get(0).asString()).isEqualTo("안형준_A004.txt");
    }

    @Test
    @DisplayName("요청 바이트(EUC-KR)를 바꾸지 않고 흐름에 넘긴다")
    void passesBodyBytesAsIs() throws Exception {
        // EUC-KR 바이트를 문자열로 바꾸지 않고 그대로 넘겨야 한글이 안 깨진다
        byte[] body = "<NAME>김철수</NAME>".getBytes(EUC_KR);
        when(flow.receive(any())).thenReturn(new ReceiveResult(List.of(), List.of(), List.of()));

        post("/orders", body);

        verify(flow).receive(body);
    }

    @Test
    @DisplayName("잘못된 주문이면 400과 오류 메시지를 응답한다")
    void returns400WithMessageOnInvalidOrder() throws Exception {
        when(flow.receive(any())).thenThrow(new InvalidOrderException("XML 형식 오류"));

        HttpResponse<String> response = post("/orders", "<HEADER>".getBytes(EUC_KR));

        // 쇼핑몰이 실패 이유를 알 수 있게 메시지를 같이 돌려준다
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(message(response)).isEqualTo("XML 형식 오류");
    }

    @Test
    @DisplayName("요청 본문이 없으면 400을 응답한다")
    void returns400OnEmptyBody() throws Exception {
        HttpResponse<String> response = post("/orders", new byte[0]);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(message(response)).isEqualTo("요청 본문이 없습니다");
        verify(flow, never()).receive(any());
    }

    @Test
    @DisplayName("요청 본문이 10MB를 넘으면 413을 응답하고 처리하지 않는다")
    void returns413OnTooLargeBody() throws Exception {
        // 수백 MB XML 을 DOM 으로 올리면 메모리가 터진다 → 입구에서 막는다
        HttpResponse<String> response = post("/orders", new byte[10 * 1024 * 1024 + 1]);

        assertThat(response.statusCode()).isEqualTo(413);
        verify(flow, never()).receive(any());
    }

    @Test
    @DisplayName("예상 못 한 오류는 500으로 응답하고 내부 메시지는 숨긴다")
    void returns500WithoutInternalMessageOnUnexpectedError() throws Exception {
        // DB 오류 등. 내부 메시지(접속 주소 등)는 응답에 넣지 않고 로그에만 남긴다
        when(flow.receive(any())).thenThrow(new IllegalStateException("ORA-12541: TNS:no listener"));

        HttpResponse<String> response = post("/orders", "<HEADER/>".getBytes(EUC_KR));

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(message(response)).isEqualTo("서버 오류");
    }

    @Test
    @DisplayName("POST가 아니면 405를 응답한다")
    void returns405ForWrongMethod() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri("/orders")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(message(response)).isEqualTo("지원하지 않는 요청입니다");
        verify(flow, never()).receive(any());
    }

    @Test
    @DisplayName("없는 주소는 404를 응답한다")
    void returns404ForUnknownPath() throws Exception {
        HttpResponse<String> response = post("/order", "<HEADER/>".getBytes(EUC_KR));

        assertThat(response.statusCode()).isEqualTo(404);
        verify(flow, never()).receive(any());
    }

    private HttpResponse<String> post(String path, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/xml; charset=EUC-KR")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.port() + path);
    }

    private String message(HttpResponse<String> response) {
        return json.readTree(response.body()).get("message").asString();
    }
}

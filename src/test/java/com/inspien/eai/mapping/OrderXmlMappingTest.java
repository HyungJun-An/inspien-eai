package com.inspien.eai.mapping;

import com.inspien.eai.common.InvalidOrderException;
import com.inspien.eai.common.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderXmlMappingTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    private final OrderXmlMapping mapping = new OrderXmlMapping();

    @Test
    @DisplayName("루트 없는 XML을 파싱한다")
    void parsesXmlWithoutRoot() {
        String xml = """
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>서울특별시 강남구</ADDRESS></HEADER>
                <ITEM><USER_ID>USER01</USER_ID><ITEM_ID>ITEM45</ITEM_ID><ITEM_NAME>선글라스</ITEM_NAME><PRICE>95000</PRICE></ITEM>
                """;

        List<Order> orders = mapping.parse(xml.getBytes(EUC_KR)).orders();

        assertThat(orders).hasSize(1);
        Order order = orders.get(0);
        assertThat(order.getUserId()).isEqualTo("USER01");
        assertThat(order.getItemId()).isEqualTo("ITEM45");
        assertThat(order.getName()).isEqualTo("김철수");
        assertThat(order.getAddress()).isEqualTo("서울특별시 강남구");
        assertThat(order.getItemName()).isEqualTo("선글라스");
        assertThat(order.getPrice()).isEqualTo("95000");
    }

    @Test
    @DisplayName("순서가 아니라 USER_ID로 HEADER와 ITEM을 합친다")
    void joinsByUserIdNotByOrder() {
        // USER02 의 ITEM 이 USER01 HEADER 바로 아래에 있음
        String xml = """
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>강남구</ADDRESS></HEADER>
                <ITEM><USER_ID>USER02</USER_ID><ITEM_ID>ITEM02</ITEM_ID><ITEM_NAME>모자</ITEM_NAME><PRICE>2000</PRICE></ITEM>
                <HEADER><USER_ID>USER02</USER_ID><NAME>이영희</NAME><ADDRESS>성동구</ADDRESS></HEADER>
                """;

        List<Order> orders = mapping.parse(xml.getBytes(EUC_KR)).orders();

        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getName()).isEqualTo("이영희");
    }

    @Test
    @DisplayName("짝이 되는 HEADER가 없는 ITEM은 제외한다")
    void skipsItemWithoutHeader() {
        String xml = """
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>강남구</ADDRESS></HEADER>
                <ITEM><USER_ID>USER99</USER_ID><ITEM_ID>ITEM99</ITEM_ID><ITEM_NAME>컵</ITEM_NAME><PRICE>500</PRICE></ITEM>
                """;

        ParsedOrders result = mapping.parse(xml.getBytes(EUC_KR));

        assertThat(result.orders()).isEmpty();
        assertThat(result.skipped()).containsExactly("USER99/ITEM99");
    }

    @Test
    @DisplayName("ITEM이 하나도 없는 HEADER는 저장할 주문이 없으므로 따로 알려 준다")
    void reportsHeaderWithoutItem() {
        String xml = """
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>강남구</ADDRESS></HEADER>
                <ITEM><USER_ID>USER01</USER_ID><ITEM_ID>ITEM01</ITEM_ID><ITEM_NAME>컵</ITEM_NAME><PRICE>500</PRICE></ITEM>
                <HEADER><USER_ID>USER02</USER_ID><NAME>이영희</NAME><ADDRESS>성동구</ADDRESS></HEADER>
                """;

        ParsedOrders result = mapping.parse(xml.getBytes(EUC_KR));

        assertThat(result.orders()).hasSize(1);
        assertThat(result.headersWithoutItem()).containsExactly("USER02");
    }

    @Test
    @DisplayName("XML 형식이 깨지면 InvalidOrderException")
    void failsOnBrokenXml() {
        String xml = "<HEADER><USER_ID>USER01</USER_ID>";

        assertThatThrownBy(() -> mapping.parse(xml.getBytes(EUC_KR)))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    @DisplayName("필수 태그가 없으면 InvalidOrderException")
    void failsOnMissingRequiredTag() {
        // ITEM 에 PRICE 가 없음
        String xml = """
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>강남구</ADDRESS></HEADER>
                <ITEM><USER_ID>USER01</USER_ID><ITEM_ID>ITEM45</ITEM_ID><ITEM_NAME>선글라스</ITEM_NAME></ITEM>
                """;

        assertThatThrownBy(() -> mapping.parse(xml.getBytes(EUC_KR)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("PRICE");
    }

    @Test
    @DisplayName("PRICE가 숫자가 아니면 InvalidOrderException")
    void failsOnNonNumericPrice() {
        String xml = """
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>강남구</ADDRESS></HEADER>
                <ITEM><USER_ID>USER01</USER_ID><ITEM_ID>ITEM45</ITEM_ID><ITEM_NAME>선글라스</ITEM_NAME><PRICE>95,000</PRICE></ITEM>
                """;

        assertThatThrownBy(() -> mapping.parse(xml.getBytes(EUC_KR)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("PRICE");
    }

    @Test
    @DisplayName("같은 USER_ID의 HEADER가 두 번 오면 InvalidOrderException")
    void failsOnDuplicateHeader() {
        // 어느 이름·주소가 맞는지 알 수 없으므로 추측해서 저장하지 않고 요청 전체를 돌려보낸다
        String xml = """
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>강남구</ADDRESS></HEADER>
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>성동구</ADDRESS></HEADER>
                <ITEM><USER_ID>USER01</USER_ID><ITEM_ID>ITEM45</ITEM_ID><ITEM_NAME>선글라스</ITEM_NAME><PRICE>95000</PRICE></ITEM>
                """;

        assertThatThrownBy(() -> mapping.parse(xml.getBytes(EUC_KR)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("USER01");
    }

    @Test
    @DisplayName("값에 구분자 '|'가 있으면 InvalidOrderException (영수증 칸이 밀림)")
    void failsOnDelimiterInValue() {
        String xml = """
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>강남구</ADDRESS></HEADER>
                <ITEM><USER_ID>USER01</USER_ID><ITEM_ID>ITEM45</ITEM_ID><ITEM_NAME>선글라스|검정</ITEM_NAME><PRICE>95000</PRICE></ITEM>
                """;

        assertThatThrownBy(() -> mapping.parse(xml.getBytes(EUC_KR)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("ITEM_NAME");
    }

    @Test
    @DisplayName("값에 줄바꿈이 있으면 InvalidOrderException (영수증 한 줄이 둘로 나뉨)")
    void failsOnLineBreakInValue() {
        String xml = """
                <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>서울특별시
                강남구</ADDRESS></HEADER>
                <ITEM><USER_ID>USER01</USER_ID><ITEM_ID>ITEM45</ITEM_ID><ITEM_NAME>선글라스</ITEM_NAME><PRICE>95000</PRICE></ITEM>
                """;

        assertThatThrownBy(() -> mapping.parse(xml.getBytes(EUC_KR)))
                .isInstanceOf(InvalidOrderException.class)
                .hasMessageContaining("ADDRESS");
    }

    @Test
    @DisplayName("DOCTYPE이 있으면 InvalidOrderException (외부 엔티티로 서버 파일을 읽는 XXE 차단)")
    void rejectsDoctype() {
        String xml = """
                <!DOCTYPE HEADER [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
                <HEADER><USER_ID>USER01</USER_ID><NAME>&secret;</NAME><ADDRESS>강남구</ADDRESS></HEADER>
                """;

        assertThatThrownBy(() -> mapping.parse(xml.getBytes(EUC_KR)))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    @DisplayName("과제 샘플 데이터는 63건 저장, 7건 제외, ITEM 없는 HEADER 4개")
    void parsesSampleData() throws Exception {
        byte[] sample;
        try (InputStream in = getClass().getResourceAsStream("/sample-order.xml")) {
            sample = in.readAllBytes();
        }

        ParsedOrders result = mapping.parse(sample);

        // HEADER 15개, ITEM 70개 중 HEADER 가 없는 ITEM 7개(USER16~22) 제외
        assertThat(result.orders()).hasSize(63);
        assertThat(result.skipped()).hasSize(7);
        // ITEM 이 하나도 없는 HEADER 4개
        assertThat(result.headersWithoutItem()).containsExactly("USER12", "USER13", "USER14", "USER15");
    }
}

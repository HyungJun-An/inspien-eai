package com.inspien.eai.mapping;

import com.inspien.eai.common.InvalidOrderException;
import com.inspien.eai.common.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 주문 XML(EUC-KR, 루트 없음) → 주문 목록
public class OrderXmlMapping {

    private static final Logger log = LoggerFactory.getLogger(OrderXmlMapping.class);
    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    // HEADER 와 ITEM 을 USER_ID 로 합친다. HEADER 가 없는 ITEM 은 로그만 남기고 건너뛴다.
    // 데이터가 잘못됐으면 InvalidOrderException (요청 전체를 거부, 일부만 저장하지 않는다)
    public ParsedOrders parse(byte[] xml) {
        Document document = toDocument(xml);

        // HEADER 를 USER_ID 로 찾을 수 있게 Map 에 담는다 (ITEM 이 HEADER 보다 먼저 나올 수 있다)
        Map<String, Element> headerByUserId = new HashMap<>();
        NodeList headers = document.getElementsByTagName("HEADER");
        for (int i = 0; i < headers.getLength(); i++) {
            Element header = (Element) headers.item(i);
            String userId = textOf(header, "USER_ID");
            // 같은 사람이 두 번 오면 어느 이름·주소가 맞는지 알 수 없으므로 추측하지 않는다
            if (headerByUserId.put(userId, header) != null) {
                throw new InvalidOrderException("같은 USER_ID 의 HEADER 가 두 번 있습니다: " + userId);
            }
        }

        List<Order> orders = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        NodeList items = document.getElementsByTagName("ITEM");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String userId = textOf(item, "USER_ID");
            String itemId = textOf(item, "ITEM_ID");
            Element header = headerByUserId.get(userId);
            if (header == null) {
                // 주문번호가 아직 없으므로 USER_ID/ITEM_ID 로 남긴다 (응답의 skipped 와 같은 형식)
                log.warn("[{}/{}] 주문 제외 - 짝이 되는 HEADER 없음(이름·주소 없음)", userId, itemId);
                skipped.add(userId + "/" + itemId);
                continue;
            }
            String price = textOf(item, "PRICE");
            if (!price.matches("\\d+")) {
                throw new InvalidOrderException("PRICE 가 숫자가 아닙니다: " + price);
            }
            orders.add(new Order(null, userId, itemId,
                    textOf(header, "NAME"), textOf(header, "ADDRESS"),
                    textOf(item, "ITEM_NAME"), price, null)); // ORDER_ID·STATUS 는 저장할 때 정한다
        }
        return new ParsedOrders(orders, skipped, List.of());
    }

    // 루트가 없어서 그대로는 XML 문서가 아니다 → <ROOT> 로 감싸서 파싱한다
    private Document toDocument(byte[] xml) {
        String wrapped = "<ROOT>" + new String(xml, EUC_KR) + "</ROOT>";
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE 차단: 외부에서 받은 XML 이므로 DOCTYPE(외부 엔티티 정의)을 아예 허용하지 않는다
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(wrapped)));
        } catch (Exception e) {
            throw new InvalidOrderException("XML 형식 오류: " + e.getMessage(), e);
        }
    }

    // 태그가 없으면 거부. 값은 '|' 구분 영수증의 한 칸이 되므로 '|' 와 줄바꿈이 있으면 거부한다
    private String textOf(Element parent, String tagName) {
        Node node = parent.getElementsByTagName(tagName).item(0);
        if (node == null) {
            throw new InvalidOrderException(parent.getTagName() + " 에 " + tagName + " 태그가 없습니다");
        }
        String value = node.getTextContent();
        if (value.contains("|") || value.contains("\n") || value.contains("\r")) {
            throw new InvalidOrderException(tagName + " 에 사용할 수 없는 문자('|' 또는 줄바꿈)가 있습니다: " + value);
        }
        return value;
    }
}

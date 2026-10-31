package com.inspien.eai.flow;

import com.inspien.eai.common.Database;
import com.inspien.eai.common.InvalidOrderException;
import com.inspien.eai.common.Order;
import com.inspien.eai.mapping.OrderXmlMapping;
import com.inspien.eai.mapping.ReceiptMapping;
import com.inspien.eai.receiver.FtpReceiver;
import com.inspien.eai.receiver.OrderDbReceiver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// DB 연결·ORDER_TB·FTP 는 mock 으로 두고 흐름과 트랜잭션 순서만 확인한다
class OrderReceiveFlowTest {

    private static final Charset EUC_KR = Charset.forName("EUC-KR");

    // 저장 2건 + HEADER 없는 ITEM 1건
    private static final String XML = """
            <HEADER><USER_ID>USER01</USER_ID><NAME>김철수</NAME><ADDRESS>강남구</ADDRESS></HEADER>
            <ITEM><USER_ID>USER01</USER_ID><ITEM_ID>ITEM45</ITEM_ID><ITEM_NAME>선글라스</ITEM_NAME><PRICE>95000</PRICE></ITEM>
            <ITEM><USER_ID>USER01</USER_ID><ITEM_ID>ITEM46</ITEM_ID><ITEM_NAME>안경테</ITEM_NAME><PRICE>45000</PRICE></ITEM>
            <ITEM><USER_ID>USER99</USER_ID><ITEM_ID>ITEM99</ITEM_ID><ITEM_NAME>컵</ITEM_NAME><PRICE>500</PRICE></ITEM>
            """;

    private Connection connection;
    private OrderDbReceiver orderDb;
    private FtpReceiver ftp;
    private OrderReceiveFlow flow;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        orderDb = mock(OrderDbReceiver.class);
        ftp = mock(FtpReceiver.class);
        flow = flowWith(orderDb);
    }

    @Test
    @DisplayName("다음 ORDER_ID와 STATUS N으로 저장한다")
    void savesOrdersWithNextIdAndStatusN() throws Exception {
        when(orderDb.findMaxOrderId(connection)).thenReturn("A003");

        ReceiveResult result = flow.receive(XML.getBytes(EUC_KR));

        assertThat(result.savedOrderIds()).containsExactly("A004", "A005");
        List<Order> saved = savedOrders();
        assertThat(saved).extracting(Order::getOrderId).containsExactly("A004", "A005");
        assertThat(saved).extracting(Order::getStatus).containsOnly(Order.RECEIVED);
    }

    @Test
    @DisplayName("제외한 ITEM 목록을 결과에 담는다")
    void returnsSkippedItems() {
        ReceiveResult result = flow.receive(XML.getBytes(EUC_KR));

        assertThat(result.skipped()).containsExactly("USER99/ITEM99");
    }

    @Test
    @DisplayName("DB 커밋이 끝난 뒤에 영수증을 업로드한다")
    void uploadsReceiptsAfterCommit() throws Exception {
        when(ftp.uploadAll(any())).thenReturn(List.of("안형준_A001.txt", "안형준_A002.txt"));

        ReceiveResult result = flow.receive(XML.getBytes(EUC_KR));

        // 올린 파일은 되돌릴 수 없으므로 커밋이 확실히 끝난 뒤에 올린다
        InOrder order = inOrder(orderDb, connection, ftp);
        order.verify(orderDb).saveAll(any(), anyList());
        order.verify(connection).commit();
        order.verify(ftp).uploadAll(any());
        assertThat(result.uploadedReceipts()).containsExactly("안형준_A001.txt", "안형준_A002.txt");
    }

    @Test
    @DisplayName("주문마다 영수증 1개를 '이름_ORDER_ID.txt'로 올린다")
    void uploadsOneReceiptPerOrder() {
        flow.receive(XML.getBytes(EUC_KR));

        assertThat(uploadedReceipts().keySet()).containsExactly("안형준_A001.txt", "안형준_A002.txt");
    }

    @Test
    @DisplayName("FTP가 실패해도 저장한 주문은 유지한다")
    void keepsSavedOrdersWhenFtpFails() throws Exception {
        when(ftp.uploadAll(any())).thenThrow(new IllegalStateException("FTP 접속 실패"));

        ReceiveResult result = flow.receive(XML.getBytes(EUC_KR));

        assertThat(result.savedOrderIds()).hasSize(2);
        assertThat(result.uploadedReceipts()).isEmpty();
        verify(connection, never()).rollback(); // 이미 커밋된 주문은 되돌리지 않는다
    }

    @Test
    @DisplayName("저장할 주문이 없으면 DB와 FTP를 부르지 않는다")
    void touchesNothingWhenNoOrderToSave() {
        // HEADER 없는 ITEM 뿐이라 저장할 주문이 없음
        String onlySkipped = "<ITEM><USER_ID>USER99</USER_ID><ITEM_ID>ITEM99</ITEM_ID><ITEM_NAME>컵</ITEM_NAME><PRICE>500</PRICE></ITEM>";

        ReceiveResult result = flow.receive(onlySkipped.getBytes(EUC_KR));

        assertThat(result.skipped()).containsExactly("USER99/ITEM99");
        verifyNoInteractions(orderDb, ftp, connection);
    }

    @Test
    @DisplayName("XML이 잘못됐으면 DB와 FTP를 부르지 않는다")
    void touchesNothingOnInvalidXml() {
        assertThatThrownBy(() -> flow.receive("<HEADER>".getBytes(EUC_KR)))
                .isInstanceOf(InvalidOrderException.class);

        verifyNoInteractions(orderDb, ftp, connection);
    }

    @Test
    @DisplayName("DB 저장이 실패하면 롤백하고 영수증을 올리지 않는다")
    void rollsBackAndSkipsFtpWhenSaveFails() throws Exception {
        doThrow(new SQLException("ORA-00942: table or view does not exist", "42000", 942))
                .when(orderDb).saveAll(any(), anyList());

        assertThatThrownBy(() -> flow.receive(XML.getBytes(EUC_KR)))
                .isInstanceOf(IllegalStateException.class);

        verify(connection).rollback();
        verifyNoInteractions(ftp);
    }

    @Test
    @DisplayName("값이 컬럼보다 길면(ORA-12899) 주문 데이터 오류로 본다 (400)")
    void valueTooLargeIsInvalidOrder() throws Exception {
        // 쇼핑몰이 다시 보내도 같은 결과이므로 서버 오류(500)가 아니라 요청 오류(400)
        doThrow(new SQLException("ORA-12899: value too large for column \"ITEM_NAME\"", "72000", 12899))
                .when(orderDb).saveAll(any(), anyList());

        assertThatThrownBy(() -> flow.receive(XML.getBytes(EUC_KR)))
                .isInstanceOf(InvalidOrderException.class);

        verify(connection).rollback();
        verifyNoInteractions(ftp);
    }

    @Test
    @DisplayName("ORDER_ID 중복(ORA-00001)은 재시도하지 않고 서버 오류로 드러낸다 (500)")
    void duplicateOrderIdIsServerError() throws Exception {
        // 서버 1대 + 잠금이면 생기지 않는다. 생겼다면 다른 프로세스가 같은 키로 쓰고 있다는 뜻
        doThrow(new SQLIntegrityConstraintViolationException("ORA-00001: unique constraint violated", "23000", 1))
                .when(orderDb).saveAll(any(), anyList());

        assertThatThrownBy(() -> flow.receive(XML.getBytes(EUC_KR)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(InvalidOrderException.class);

        verify(orderDb).saveAll(any(), anyList()); // 한 번만 시도
        verifyNoInteractions(ftp);
    }

    @Test
    @DisplayName("주문이 동시에 들어와도 ORDER_ID가 겹치지 않는다")
    void concurrentRequestsGetDistinctOrderIds() throws Exception {
        // 조회(MAX) → 저장 사이에 다른 요청이 끼어들면 같은 ID 가 나온다. 일부러 그 사이를 늘린 가짜 DB
        SlowInMemoryOrderDb slowDb = new SlowInMemoryOrderDb();
        OrderReceiveFlow concurrentFlow = flowWith(slowDb);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<ReceiveResult>> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                requests.add(() -> concurrentFlow.receive(XML.getBytes(EUC_KR)));
            }
            List<String> ids = new ArrayList<>();
            for (Future<ReceiveResult> future : pool.invokeAll(requests)) {
                ids.addAll(future.get().savedOrderIds());
            }

            assertThat(ids).hasSize(16).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    private OrderReceiveFlow flowWith(OrderDbReceiver db) {
        return new OrderReceiveFlow(new OrderXmlMapping(), new Database(() -> connection), db,
                new ReceiptMapping("안형준"), ftp);
    }

    @SuppressWarnings("unchecked")
    private Map<String, byte[]> uploadedReceipts() {
        ArgumentCaptor<Map<String, byte[]>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ftp).uploadAll(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<Order> savedOrders() throws SQLException {
        ArgumentCaptor<List<Order>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderDb).saveAll(any(), captor.capture());
        return captor.getValue();
    }

    // 메모리에 저장하는 가짜 ORDER_TB. MAX 조회 후 잠깐 멈춰서 요청이 겹칠 틈을 만든다
    static class SlowInMemoryOrderDb extends OrderDbReceiver {
        private final Set<String> ids = new HashSet<>();

        SlowInMemoryOrderDb() {
            super("TEST");
        }

        @Override
        public String findMaxOrderId(Connection connection) {
            synchronized (ids) {
                return ids.stream().max(String::compareTo).orElse(null);
            }
        }

        @Override
        public void saveAll(Connection connection, List<Order> orders) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (ids) {
                for (Order order : orders) {
                    if (!ids.add(order.getOrderId())) {
                        throw new IllegalStateException("ORDER_ID 중복: " + order.getOrderId());
                    }
                }
            }
        }
    }
}

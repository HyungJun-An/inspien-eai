package com.inspien.eai.flow;

import com.inspien.eai.common.Database;
import com.inspien.eai.common.Order;
import com.inspien.eai.mapping.Shipment;
import com.inspien.eai.mapping.ShipmentMapping;
import com.inspien.eai.receiver.ShipmentDbReceiver;
import com.inspien.eai.sender.OrderDbPollingSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// DB 연결·ORDER_TB·SHIPMENT_TB 는 mock 으로 두고, 주문마다의 트랜잭션과 실패 분기만 확인한다
class ShipmentFlowTest {

    private final Order first = order("A001", "ITEM45");
    private final Order second = order("A002", "ITEM46");

    private Connection connection;
    private OrderDbPollingSender polling;
    private ShipmentDbReceiver shipmentDb;
    private ShipmentFlow flow;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        polling = mock(OrderDbPollingSender.class);
        shipmentDb = mock(ShipmentDbReceiver.class);
        flow = new ShipmentFlow(new Database(() -> connection), polling, new ShipmentMapping(), shipmentDb);
        when(polling.markShipped(any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("접수(N) 주문마다 상태를 Y로 바꾸고 운송 데이터를 저장해 주문별로 커밋한다")
    void shipsEachOrderInItsOwnTransaction() throws Exception {
        when(polling.findReceived(connection)).thenReturn(List.of(first, second));

        ShipmentResult result = flow.run();

        // 상태를 먼저 바꿔서(N 일 때만) 이 주문을 "내가 처리한다"고 확정한 뒤 운송 데이터를 넣는다
        InOrder order = inOrder(polling, shipmentDb, connection);
        order.verify(polling).markShipped(connection, "A001");
        order.verify(shipmentDb).save(eq(connection), any());
        order.verify(connection).commit();
        order.verify(polling).markShipped(connection, "A002");
        order.verify(shipmentDb).save(eq(connection), any());
        order.verify(connection).commit();
        assertThat(result).isEqualTo(new ShipmentResult(2, 2, 0, 0, false));
    }

    @Test
    @DisplayName("다음 SHIPMENT_ID로 주문의 ORDER_ID·ITEM_ID·주소를 저장한다")
    void savesWithNextShipmentId() throws Exception {
        when(polling.findReceived(connection)).thenReturn(List.of(first, second));
        when(shipmentDb.findMaxShipmentId(connection)).thenReturn("A005", "A006");

        flow.run();

        verify(shipmentDb).save(connection, new Shipment("A006", "A001", "ITEM45", "서울특별시 강남구"));
        verify(shipmentDb).save(connection, new Shipment("A007", "A002", "ITEM46", "서울특별시 강남구"));
    }

    @Test
    @DisplayName("이미 처리된 주문(바뀐 행 0)은 운송 데이터를 넣지 않고 건너뛴다")
    void skipsOrderAlreadyShipped() throws Exception {
        // 이미 Y 라서 바뀐 행이 0 → 다른 쪽이 처리한 주문. 운송 데이터를 또 넣으면 중복 배송
        when(polling.findReceived(connection)).thenReturn(List.of(first));
        when(polling.markShipped(connection, "A001")).thenReturn(0);

        ShipmentResult result = flow.run();

        verify(shipmentDb, never()).save(any(), any());
        assertThat(result).isEqualTo(new ShipmentResult(1, 0, 1, 0, false));
    }

    @Test
    @DisplayName("한 건이 실패하면 그 건만 롤백하고 나머지는 계속 처리한다")
    void rollsBackOnlyFailedOrderAndContinues() throws Exception {
        // 실패한 건은 N 으로 남아 다음 주기에 다시 처리된다
        when(polling.findReceived(connection)).thenReturn(List.of(first, second));
        doThrow(new SQLException("ORA-12899: value too large for column \"ADDRESS\"", "72000", 12899))
                .doNothing()
                .when(shipmentDb).save(eq(connection), any());

        ShipmentResult result = flow.run();

        verify(connection).rollback();
        verify(connection, times(1)).commit();
        verify(polling).markShipped(connection, "A002");
        assertThat(result).isEqualTo(new ShipmentResult(2, 1, 0, 1, false));
    }

    @Test
    @DisplayName("DB 연결이 끊기면 이번 주기를 멈추고 남은 주문은 다음 주기로 넘긴다")
    void stopsRunWhenConnectionIsLost() throws Exception {
        // 연결이 죽었는데 남은 주문을 계속 시도하면 같은 오류만 반복해서 찍힌다
        when(polling.findReceived(connection)).thenReturn(List.of(first, second));
        when(polling.markShipped(connection, "A001"))
                .thenThrow(new SQLRecoverableException("ORA-03113: end-of-file on communication channel"));

        ShipmentResult result = flow.run();

        verify(polling, never()).markShipped(connection, "A002");
        assertThat(result).isEqualTo(new ShipmentResult(2, 0, 0, 1, true));
    }

    @Test
    @DisplayName("접수된 주문이 없으면 아무것도 하지 않는다")
    void doesNothingWhenNoOrder() throws Exception {
        when(polling.findReceived(connection)).thenReturn(List.of());

        ShipmentResult result = flow.run();

        verify(polling, never()).markShipped(any(), any());
        assertThat(result).isEqualTo(new ShipmentResult(0, 0, 0, 0, false));
    }

    @Test
    @DisplayName("접수 주문 조회가 실패해도 예외를 밖으로 던지지 않는다 (다음 주기에 다시)")
    void doesNotThrowWhenPollingFails() throws Exception {
        // 예외가 스케줄러 밖으로 나가면 다음 실행이 멈춘다
        when(polling.findReceived(connection))
                .thenThrow(new SQLException("ORA-00942: table or view does not exist", "42000", 942));

        ShipmentResult result = flow.run();

        verify(shipmentDb, never()).save(any(), any());
        assertThat(result.stopped()).isTrue();
    }

    private static Order order(String orderId, String itemId) {
        return new Order(orderId, "USER01", itemId, "김철수", "서울특별시 강남구", "선글라스", "95000", Order.RECEIVED);
    }
}

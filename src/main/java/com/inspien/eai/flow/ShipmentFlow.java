package com.inspien.eai.flow;

import com.inspien.eai.common.Database;
import com.inspien.eai.common.IdGenerator;
import com.inspien.eai.common.Order;
import com.inspien.eai.mapping.ShipmentMapping;
import com.inspien.eai.receiver.ShipmentDbReceiver;
import com.inspien.eai.sender.OrderDbPollingSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

// IF-02 운송 연계 (배치): ORDER_TB 접수(N) 주문 → SHIPMENT_TB, 주문 STATUS N → Y
// 주문 1건 = 트랜잭션 1개 (상태 UPDATE + 운송 INSERT). 한 건이 실패해도 그 건만 롤백되고 N 으로 남아 다음 주기에 다시 처리된다
// SHIPMENT_ID 는 이 배치 하나만 만들고 실행이 겹치지 않으므로(fixed delay) 잠금이 필요 없다
public class ShipmentFlow {

    private static final Logger log = LoggerFactory.getLogger(ShipmentFlow.class);

    private final Database database;
    private final OrderDbPollingSender orderPolling;
    private final ShipmentMapping shipmentMapping;
    private final ShipmentDbReceiver shipmentDb;

    public ShipmentFlow(Database database, OrderDbPollingSender orderPolling,
                        ShipmentMapping shipmentMapping, ShipmentDbReceiver shipmentDb) {
        this.database = database;
        this.orderPolling = orderPolling;
        this.shipmentMapping = shipmentMapping;
        this.shipmentDb = shipmentDb;
    }

    // 예외를 밖으로 던지지 않는다 (실패는 결과와 로그로). 접속 실패·조회 실패면 이번 주기를 건너뛴다
    public ShipmentResult run() {
        try {
            // 연결 하나로 전부 처리한다 (주문마다 접속하면 63번 접속)
            return database.withConnection(this::shipAll);
        } catch (RuntimeException e) {
            log.error("운송 연계 배치 실패 - 다음 주기에 다시 시도: {}", e.getMessage(), e);
            return new ShipmentResult(0, 0, 0, 0, true);
        }
    }

    private ShipmentResult shipAll(Connection connection) throws SQLException {
        List<Order> orders = orderPolling.findReceived(connection);
        if (orders.isEmpty()) {
            return new ShipmentResult(0, 0, 0, 0, false);
        }
        int shipped = 0;
        int skipped = 0;
        int failed = 0;
        boolean stopped = false;
        for (Order order : orders) {
            try {
                if (Database.transaction(connection, tx -> ship(tx, order))) {
                    shipped++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failed++;
                if (connectionLost(e)) {
                    // 연결이 죽었는데 남은 주문을 계속하면 같은 오류만 반복된다 → 멈추고 다음 주기에
                    log.error("[{}] 운송 연계 실패 - DB 연결 끊김, 이번 주기 중단 (남은 주문은 다음 주기에): {}",
                            order.getOrderId(), e.getMessage());
                    stopped = true;
                    break;
                }
                log.error("[{}] 운송 연계 실패 - 이 건만 롤백(N 유지), 다음 주기에 재시도", order.getOrderId(), e);
            }
        }
        log.info("운송 연계 배치: 대상 {}건, 완료 {}건, 건너뜀 {}건, 실패 {}건{}",
                orders.size(), shipped, skipped, failed, stopped ? " (중단)" : "");
        return new ShipmentResult(orders.size(), shipped, skipped, failed, stopped);
    }

    // 반환: 이번에 운송 데이터를 넣었으면 true, 이미 처리된 주문이라 건너뛰었으면 false
    private boolean ship(Connection tx, Order order) throws SQLException {
        // 상태를 먼저 바꾼다: N 일 때만 바뀌므로 바뀐 행이 1 이면 이 주문은 지금 여기서만 처리한다
        if (orderPolling.markShipped(tx, order.getOrderId()) == 0) {
            log.warn("[{}] 이미 운송 연계된 주문 - 건너뜀", order.getOrderId());
            return false;
        }
        String shipmentId = IdGenerator.next(shipmentDb.findMaxShipmentId(tx));
        shipmentDb.save(tx, shipmentMapping.toShipment(shipmentId, order));
        log.info("[{}] 운송 연계 완료: SHIPMENT_ID={}", order.getOrderId(), shipmentId);
        return true;
    }

    // 커밋 결과 불명(커밋 중 끊김)도 여기 해당한다. 운송은 조건부 UPDATE 라 다음 주기에 다시 해도 안전하다
    // (커밋됐으면 Y 라 안 잡히고, 안 됐으면 N 이라 다시 잡힌다)
    private static boolean connectionLost(RuntimeException e) {
        return e.getCause() instanceof SQLException sql && Database.isConnectionProblem(sql);
    }
}

package com.inspien.eai.flow;

import com.inspien.eai.common.CommitOutcomeUnknownException;
import com.inspien.eai.common.Database;
import com.inspien.eai.common.IdGenerator;
import com.inspien.eai.common.InvalidOrderException;
import com.inspien.eai.common.Order;
import com.inspien.eai.mapping.OrderXmlMapping;
import com.inspien.eai.mapping.ParsedOrders;
import com.inspien.eai.mapping.ReceiptMapping;
import com.inspien.eai.receiver.FtpReceiver;
import com.inspien.eai.receiver.OrderDbReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// IF-01 주문 수신: 쇼핑몰 XML → ORDER_TB(STATUS=N, 커밋) → 주문별 영수증 → 회계 FTP
// 트랜잭션은 ORDER_TB 저장까지만. FTP 는 커밋이 끝난 뒤에 올리고, 실패해도 저장한 주문은 그대로 둔다
public class OrderReceiveFlow {

    private static final Logger log = LoggerFactory.getLogger(OrderReceiveFlow.class);

    private final OrderXmlMapping xmlMapping;
    private final Database database;
    private final OrderDbReceiver orderDb;
    private final ReceiptMapping receiptMapping;
    private final FtpReceiver ftp;
    // ID 생성 + 저장을 한 번에 하나씩 (서버 1대 전제)
    private final Object saveLock = new Object();

    public OrderReceiveFlow(OrderXmlMapping xmlMapping, Database database, OrderDbReceiver orderDbReceiver,
                            ReceiptMapping receiptMapping, FtpReceiver ftpReceiver) {
        this.xmlMapping = xmlMapping;
        this.database = database;
        this.orderDb = orderDbReceiver;
        this.receiptMapping = receiptMapping;
        this.ftp = ftpReceiver;
    }

    public ReceiveResult receive(byte[] xml) {
        // XML 이 잘못됐으면 여기서 InvalidOrderException 이 나고 DB·FTP 는 건드리지 않는다
        ParsedOrders parsed = xmlMapping.parse(xml);
        List<Order> orders = parsed.orders();
        if (orders.isEmpty()) {
            log.info("주문 수신 요청 처리: 저장할 주문 없음, 제외 {}건, ITEM 없는 HEADER {}건",
                    parsed.skipped().size(), parsed.headersWithoutItem().size());
            return new ReceiveResult(List.of(), parsed.skipped(), List.of());
        }

        List<String> savedIds = save(orders);
        // 처리 추적: 단계별 로그는 모두 "[ORDER_ID] 단계 결과" 형식 → 로그에서 주문번호로 검색하면 흐름이 보인다
        for (Order order : orders) {
            log.info("[{}] 주문 수신 - DB 저장 완료 (USER_ID={}, ITEM_ID={})",
                    order.getOrderId(), order.getUserId(), order.getItemId());
        }

        List<String> uploaded = upload(orders);
        log.info("주문 수신 요청 처리: 저장 {}건, 제외 {}건, ITEM 없는 HEADER {}건, 영수증 업로드 {}건",
                savedIds.size(), parsed.skipped().size(), parsed.headersWithoutItem().size(), uploaded.size());
        return new ReceiveResult(savedIds, parsed.skipped(), uploaded);
    }

    // MAX 조회 → ID 부여 → batch 저장 → 커밋을 잠금 안에서 한 번에 한다.
    // 잠금 밖에서 MAX 를 읽으면 동시에 들어온 두 요청이 같은 ID 를 만든다. 커밋까지 잠금 안에 있어야
    // 다음 요청이 MAX 를 읽을 때 앞 요청의 저장 결과가 보인다
    private List<String> save(List<Order> orders) {
        synchronized (saveLock) {
            try {
                return database.inTransaction(connection -> {
                    String lastId = orderDb.findMaxOrderId(connection);
                    List<String> ids = new ArrayList<>();
                    for (Order order : orders) {
                        lastId = IdGenerator.next(lastId);
                        order.setOrderId(lastId);
                        order.setStatus(Order.RECEIVED);
                        ids.add(lastId);
                    }
                    orderDb.saveAll(connection, orders);
                    return ids;
                });
            } catch (CommitOutcomeUnknownException e) {
                // 다시 저장하면 같은 주문이 새 번호로 또 들어갈 수 있어 자동으로 재시도하지 않는다 → 500, 사람이 확인
                log.error("주문 저장 커밋 결과 불명 - DB 확인 필요. 저장하려던 ORDER_ID: {}~{} ({}건)",
                        orders.get(0).getOrderId(), orders.get(orders.size() - 1).getOrderId(), orders.size(), e);
                throw e;
            } catch (IllegalStateException e) {
                // 값이 컬럼보다 긴 경우 등: 다시 보내도 같으므로 요청 오류(400)로 돌려준다. 나머지 DB 오류는 500
                if (e.getCause() instanceof SQLException sql && Database.isDataProblem(sql)) {
                    throw new InvalidOrderException("주문 데이터가 DB 형식에 맞지 않습니다: " + sql.getMessage(), e);
                }
                throw e;
            }
        }
    }

    // 영수증: 주문마다 파일 1개. 실패해도 저장한 주문은 유지하고(receipts/pending 에서 재처리), 올라간 파일만 결과에 담는다
    private List<String> upload(List<Order> orders) {
        List<String> uploaded = List.of();
        try {
            uploaded = ftp.uploadAll(receiptMapping.toFiles(orders));
        } catch (Exception e) {
            log.error("영수증 업로드 실패 - 저장된 주문은 유지, receipts/pending 에서 재처리", e);
        }
        for (Order order : orders) {
            String fileName = receiptMapping.fileName(order);
            if (uploaded.contains(fileName)) {
                log.info("[{}] 영수증 FTP 업로드 완료 ({})", order.getOrderId(), fileName);
            } else {
                log.warn("[{}] 영수증 FTP 미업로드 - receipts/pending 에 보관 ({})", order.getOrderId(), fileName);
            }
        }
        return uploaded;
    }
}

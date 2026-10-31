package com.inspien.eai.flow;

import com.inspien.eai.common.Database;
import com.inspien.eai.mapping.ShipmentMapping;
import com.inspien.eai.receiver.ShipmentDbReceiver;
import com.inspien.eai.sender.OrderDbPollingSender;

// IF-02 운송 연계 (배치): ORDER_TB 접수(N) 주문 → SHIPMENT_TB, 주문 STATUS N → Y
public class ShipmentFlow {

    public ShipmentFlow(Database database, OrderDbPollingSender orderPolling,
                        ShipmentMapping shipmentMapping, ShipmentDbReceiver shipmentDb) {
    }

    // 예외를 밖으로 던지지 않는다 (실패는 결과와 로그로)
    public ShipmentResult run() {
        throw new UnsupportedOperationException("TODO");
    }
}

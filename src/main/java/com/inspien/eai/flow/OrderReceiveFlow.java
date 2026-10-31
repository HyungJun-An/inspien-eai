package com.inspien.eai.flow;

import com.inspien.eai.common.Database;
import com.inspien.eai.mapping.OrderXmlMapping;
import com.inspien.eai.mapping.ReceiptMapping;
import com.inspien.eai.receiver.FtpReceiver;
import com.inspien.eai.receiver.OrderDbReceiver;

// IF-01 주문 수신: 쇼핑몰 XML → ORDER_TB(STATUS=N, 커밋) → 주문별 영수증 → 회계 FTP
public class OrderReceiveFlow {

    public OrderReceiveFlow(OrderXmlMapping xmlMapping, Database database, OrderDbReceiver orderDbReceiver,
                            ReceiptMapping receiptMapping, FtpReceiver ftpReceiver) {
    }

    public ReceiveResult receive(byte[] xml) {
        throw new UnsupportedOperationException("TODO");
    }
}

package com.inspien.eai.mapping;

import com.inspien.eai.common.Order;

// 주문(ORDER_TB) → 운송(SHIPMENT_TB). 운송회사에 필요한 것만 넘긴다 (이름·가격 등은 넘기지 않음)
public class ShipmentMapping {

    public Shipment toShipment(String shipmentId, Order order) {
        return new Shipment(shipmentId, order.getOrderId(), order.getItemId(), order.getAddress());
    }
}

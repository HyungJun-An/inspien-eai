package com.inspien.eai.mapping;

import com.inspien.eai.common.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShipmentMappingTest {

    @Test
    @DisplayName("주문의 ORDER_ID, ITEM_ID, 주소를 운송 데이터로 옮긴다")
    void copiesOrderIdItemIdAndAddress() {
        Order order = new Order("A001", "USER01", "ITEM45", "김철수", "서울특별시 강남구", "선글라스", "95000", Order.RECEIVED);

        Shipment shipment = new ShipmentMapping().toShipment("A007", order);

        assertThat(shipment).isEqualTo(new Shipment("A007", "A001", "ITEM45", "서울특별시 강남구"));
    }
}

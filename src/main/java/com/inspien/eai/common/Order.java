package com.inspien.eai.common;

// ORDER_TB 1행 = 주문자(HEADER) + 상품(ITEM) 1개
public class Order {

    // STATUS 값. 수신 시 N, 운송 연계가 끝나면 Y
    public static final String RECEIVED = "N";
    public static final String SHIPPED = "Y";

    private String orderId;
    private final String userId;
    private final String itemId;
    private final String name;
    private final String address;
    private final String itemName;
    private final String price;
    private String status;

    public Order(String orderId, String userId, String itemId, String name,
                 String address, String itemName, String price, String status) {
        this.orderId = orderId;
        this.userId = userId;
        this.itemId = itemId;
        this.name = name;
        this.address = address;
        this.itemName = itemName;
        this.price = price;
        this.status = status;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getUserId() {
        return userId;
    }

    public String getItemId() {
        return itemId;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }

    public String getItemName() {
        return itemName;
    }

    public String getPrice() {
        return price;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

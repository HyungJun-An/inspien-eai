package com.inspien.eai.flow;

// 운송 연계 1회 실행 결과
// target: 접수(N) 주문 수, shipped: 이번에 넘긴 수, skipped: 이미 처리돼 건너뛴 수, failed: 실패(N 으로 남음) 수
// stopped: DB 연결 문제로 이번 주기를 중간에 멈췄는지 (남은 주문은 다음 주기에)
public record ShipmentResult(int target, int shipped, int skipped, int failed, boolean stopped) {
}

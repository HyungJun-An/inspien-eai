package com.inspien.eai.flow;

import java.util.List;

// 주문 수신 결과. API 응답으로 그대로 돌려준다.
// uploadedReceipts: FTP 에 올라간 영수증 파일명. 저장된 주문 중 여기 없는 것은 업로드 실패(또는 업로드 꺼짐)
public record ReceiveResult(List<String> savedOrderIds, List<String> skipped, List<String> uploadedReceipts) {
}

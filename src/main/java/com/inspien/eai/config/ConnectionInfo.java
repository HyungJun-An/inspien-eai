package com.inspien.eai.config;

// 과제 API 에서 받아 복호화한 접속 정보. 파일에 저장하지 않고 메모리에만 둔다.
// ORDER_TB 와 SHIPMENT_TB 는 같은 DB·같은 계정이라 DB 접속 정보는 하나다.
public record ConnectionInfo(
        String dbUrl, String dbUsername, String dbPassword,
        String ftpHost, int ftpPort, String ftpUsername, String ftpPassword, String ftpPath) {

    // 로그에 찍혀도 비밀번호가 나가지 않게 한다
    @Override
    public String toString() {
        return "ConnectionInfo[dbUrl=" + dbUrl + ", dbUsername=" + dbUsername
                + ", ftpHost=" + ftpHost + ", ftpPort=" + ftpPort + ", ftpPath=" + ftpPath + "]";
    }
}

package com.inspien.eai;

import com.inspien.eai.common.Database;
import com.inspien.eai.config.AppConfig;
import com.inspien.eai.config.ConnectionInfo;
import com.inspien.eai.config.ConnectionInfoLoader;

import java.sql.Connection;
import java.sql.SQLException;

// 과제 Oracle 에 직접 붙는 테스트용. 접속 정보는 과제 API 에서 받아온다 (테스트 실행 중 한 번만)
// 계정에 DELETE 권한이 없으므로 이 연결은 자동 커밋을 끄고 주며, 테스트가 끝나면 반드시 rollback 해야 한다.
// 커밋하는 테스트를 만들지 말 것.
public final class OracleTestSupport {

    private static ConnectionInfo info;

    private OracleTestSupport() {
    }

    // API 계정·전화번호(application-local.properties)가 없으면 DB 테스트를 건너뛴다 (@EnabledIf 에서 사용)
    public static boolean hasLocalConfig() {
        return OracleTestSupport.class.getResource("/application-local.properties") != null;
    }

    public static String applicantKey() {
        return AppConfig.load().get("inspien.applicant.key");
    }

    public static synchronized ConnectionInfo connectionInfo() {
        if (info == null) {
            info = new ConnectionInfoLoader().load(AppConfig.load());
        }
        return info;
    }

    // 자동 커밋이 꺼진 연결 (앱과 같은 방법·타임아웃으로 연다). 테스트 끝에 rollback 후 close 할 것
    public static Connection openRollbackOnly() throws SQLException {
        Connection connection = Database.oracle(connectionInfo()).open();
        connection.setAutoCommit(false);
        return connection;
    }
}

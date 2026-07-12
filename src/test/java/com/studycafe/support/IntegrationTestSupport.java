package com.studycafe.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class IntegrationTestSupport {

    // 싱글턴 컨테이너: JVM 전체에서 한 번만 시작하고 종료하지 않는다.
    // @Testcontainers/@Container를 쓰면 테스트 클래스마다 컨테이너가 stop/restart되어
    // Spring 컨텍스트 캐싱과 충돌하므로(연결 실패/스키마 누락), 직접 start()한다.
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }
}

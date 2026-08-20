package com.k_place.support;

import com.k_place.support.cleanup.CleanupConfiguration;
import com.k_place.support.cleanup.InfrastructureCleaner;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 실제 MySQL / Redis 가 필요한 테스트의 공통 기반.
 *
 * <pre>{@code
 * class ReviewIntegrationTest extends IntegrationTest {
 *     @Test
 *     void ...
 * }
 * }</pre>
 *
 * <p><b>전제: {@code docker compose up -d} 로 인프라가 떠 있어야 한다.</b>
 * 테스트가 컨테이너를 직접 띄우지 않으므로, 대신 실행마다 인프라 부팅을 기다리지 않는다.
 *
 * <p><b>격리는 데이터 정리로 만든다.</b> 매 테스트가 끝날 때 테스트 스키마의 테이블을 비우고
 * Redis 테스트 DB 를 지운다. 컨테이너를 새로 띄우는 방식보다 훨씬 빠르지만,
 * <b>정리 대상 밖의 상태는 남는다</b>는 점은 알고 써야 한다 — 스키마 변경, 시퀀스가 아닌
 * 전역 설정, 다른 스키마의 데이터 등.
 *
 * <p>정리를 {@code @BeforeEach} 가 아니라 {@code @AfterEach} 에 두는 이유는, 실패한 테스트의
 * 데이터를 남겨 두면 다음 테스트가 그 영향을 받기 때문이다. 자기가 어지른 것은 자기가 치운다.
 *
 * <p>DB 가 필요 없는 테스트는 <b>이 클래스를 상속하지 않는다.</b> 도메인 단위 테스트,
 * Mockito 기반 application 테스트, {@code @WebMvcTest} 는 Docker 없이 훨씬 빠르게 돈다.
 */
@SpringBootTest
@Import(CleanupConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    private InfrastructureCleaner infrastructureCleaner;

    @AfterEach
    void cleanInfrastructure() {
        infrastructureCleaner.clean();
    }
}

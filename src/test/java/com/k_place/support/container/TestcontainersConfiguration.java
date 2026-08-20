package com.k_place.support.container;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Spring 컨텍스트가 필요한 테스트가 쓸 MySQL / Redis 컨테이너.
 *
 * <pre>{@code
 * @SpringBootTest
 * @Import(TestcontainersConfiguration.class)
 * class SomethingIntegrationTest { ... }
 * }</pre>
 *
 * <p><b>H2 를 쓰지 않는 이유</b> — H2 의 MySQL 호환 모드는 호환일 뿐 동일하지 않다.
 * 갭 락, {@code SELECT ... FOR UPDATE} 의 대기 동작, 격리 수준, unique 제약 위반 시점,
 * collation 정렬이 모두 다르다. 특히 <b>동시성 테스트가 H2 에서 통과하고 운영에서 깨지는</b>
 * 상황은 테스트가 없느니만 못하다. 그래서 테스트도 운영과 같은 엔진 위에서 돌린다.
 *
 * <p><b>컨테이너는 빈으로 선언한다.</b> {@code @Container} 정적 필드는 테스트 클래스마다
 * 컨테이너를 새로 띄우지만, 빈으로 두면 Spring 의 컨텍스트 캐시를 타서 같은 설정을 쓰는
 * 테스트 클래스들이 하나를 공유한다. 부팅 비용(수 초)을 클래스 수만큼 곱하지 않는다.
 *
 * <p>접속 정보는 {@link ServiceConnection} 이 컨테이너에서 읽어 주입하므로
 * {@code application.yaml} 에 datasource 를 적지 않는다.
 *
 * <p><b>전제: Docker 데몬이 떠 있어야 한다.</b> 순수 단위 테스트(도메인, Mockito 기반
 * application 테스트)와 {@code @WebMvcTest} 는 이 설정이 필요 없으므로 Docker 없이 돈다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /** docker-compose.yml 과 같은 이미지를 쓴다. 버전이 갈리면 테스트의 의미가 없다. */
    private static final DockerImageName MYSQL_IMAGE = DockerImageName.parse("mysql:8.4");

    private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

    private static final int REDIS_PORT = 6379;

    @Bean
    @ServiceConnection
    MySQLContainer<?> mysqlContainer() {
        return new MySQLContainer<>(MYSQL_IMAGE)
                .withDatabaseName("k_place")
                .withCommand(
                        "--character-set-server=utf8mb4",
                        "--collation-server=utf8mb4_unicode_ci",
                        // 동시성 테스트는 동시 요청 수만큼 세션을 연다. 기본값(151)이면
                        // 락이 아니라 커넥션 한도로 실패해 원인을 오해하게 된다.
                        "--max-connections=500");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(REDIS_PORT);
    }
}

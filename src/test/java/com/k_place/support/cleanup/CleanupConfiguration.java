package com.k_place.support.cleanup;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * {@link InfrastructureCleaner} 등록.
 *
 * <p>{@code @Component} 로 두지 않는다. {@code com.k_place} 아래에 있어 컴포넌트 스캔에 걸리면
 * 이 빈이 필요 없는 슬라이스 테스트에도 딸려 들어가고, 그러면 DataSource 가 없는
 * {@code @WebMvcTest} 가 이유 없이 깨진다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CleanupConfiguration {

    @Bean
    InfrastructureCleaner infrastructureCleaner(DataSource dataSource,
                                                RedisConnectionFactory redisConnectionFactory) {
        return new InfrastructureCleaner(dataSource, redisConnectionFactory);
    }
}

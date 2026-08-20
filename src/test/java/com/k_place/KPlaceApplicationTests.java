package com.k_place;

import com.k_place.support.container.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 전체 컨텍스트가 뜨는지 확인한다.
 *
 * <p>Docker 데몬이 필요하다 — MySQL / Redis 컨테이너 위에서 실제 연결까지 검증한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KPlaceApplicationTests {

	@Test
	void contextLoads() {
	}

}

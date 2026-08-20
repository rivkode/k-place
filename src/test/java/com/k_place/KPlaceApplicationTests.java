package com.k_place;

import com.k_place.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * 전체 컨텍스트가 뜨는지 확인한다.
 *
 * <p>{@code docker compose up -d} 로 MySQL / Redis 가 떠 있어야 한다.
 */
class KPlaceApplicationTests extends IntegrationTest {

	@Test
	void contextLoads() {
	}

}

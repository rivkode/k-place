-- 테스트 전용 스키마.
--
-- 개발용(k_place)과 분리한다. 테스트는 매 케이스 뒤에 테이블을 비우므로,
-- 같은 스키마를 쓰면 `./gradlew test` 한 번에 개발 데이터가 날아간다.
CREATE DATABASE IF NOT EXISTS k_place_test
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

GRANT ALL PRIVILEGES ON k_place_test.* TO 'k_place'@'%';
FLUSH PRIVILEGES;

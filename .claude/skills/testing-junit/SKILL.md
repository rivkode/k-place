---
name: testing-junit
description: k-place 에서 JUnit 5 기반 테스트를 작성할 때 사용한다. 계층별 테스트 전략(domain/application/infrastructure/presentation), Mock 사용 기준과 실제 객체 사용 기준, Given-When-Then 구조, 테스트 네이밍, Spring 슬라이스 테스트 선택, Testcontainers 기반 테스트 환경(H2 를 쓰지 않는 이유), ConcurrentRunner 를 쓰는 동시성 테스트, Fixture 관리, 커버리지 기준을 포함한다. "테스트 작성", "단위 테스트", "통합 테스트", "동시성 테스트", "Mockito", "JUnit", "@SpringBootTest" 언급이 있거나 구현 직후 검증 단계에서 반드시 사용한다.
---

# Testing with JUnit 5

DDD 계층 구조에서 **독립적이고 빠른** 테스트를 작성하기 위한 지침.
구현이 끝난 직후, 커밋 전에 읽는다.

---

## 1. 핵심 원칙

1. **계층별 독립 테스트** — 각 계층은 자기 책임만 검증한다. 다른 계층이 망가져도 내 테스트는 돈다.
2. **Mock 은 경계에만** — 순수 객체는 실제로 쓴다.
3. **테스트도 문서** — 이름만 봐도 사양이 보여야 한다.
4. **빠른 피드백** — domain 테스트 전체가 1초 이내.
5. **결정론적** — 시간·순서·외부 상태에 의존하지 않는다.

---

## 2. 계층별 전략

| 계층 | 애너테이션 | Spring | DB/Redis | Mock 대상 |
|---|---|---|---|---|
| domain | 없음 (순수 JUnit) | ❌ | ❌ | 없음 |
| application | `@ExtendWith(MockitoExtension.class)` | ❌ | ❌ | Repository, 외부 연동 인터페이스, EventPublisher, Clock |
| infrastructure (JPA) | `@DataJpaTest` | 슬라이스 | ✅ | 없음 |
| infrastructure (Redis) | `@DataRedisTest` 또는 직접 구성 | 슬라이스 | ✅ | 없음 |
| infrastructure (외부 HTTP) | `@RestClientTest` / MockWebServer | 슬라이스 | ❌ | HTTP 서버 |
| presentation | `@WebMvcTest(XxxController.class)` | 슬라이스 | ❌ | Application Service |
| 통합 (E2E) | `@SpringBootTest` | 전체 | ✅ | 최소화 |

**반드시**: 분류와 애너테이션이 일치해야 한다. domain 테스트에 `@SpringBootTest` 가 보이면 즉시 수정.

DB/Redis 가 ✅ 인 테스트는 **Testcontainers 로 실제 컨테이너에 붙으므로 Docker 데몬이 필요하다**
(7절). 그 외에는 Docker 없이 돈다.

**테스트 패키지는 main 패키지 구조를 그대로 미러링한다.**
`com.k_place.review.domain.ReviewTest` 처럼 대상과 같은 패키지에 둔다.

---

## 3. Mock vs 실제 객체 ⭐

### 3.1 Mock 을 **써야** 하는 것
- 외부 시스템 경계: **MySQL, Redis, 외부 HTTP API, 파일 시스템, 메일**
- 비결정적 요소: **Clock, Random, UUID 생성기**
- 느리거나 비싼 작업
- **Repository 인터페이스, 외부 연동용 domain 인터페이스**

### 3.2 Mock 을 **쓰면 안** 되는 것
- **Value Object** (`Rating`, `PlaceId`, `Money`) → 실제 객체
- **Entity / Aggregate Root** (`Review`, `Place`) → 실제 객체
- 순수 Domain Service (외부 의존 없음) → 실제 객체
- DTO, Command, Query → 실제 객체
- 테스트 대상(SUT) → 당연히 실제

### 3.3 판단 질문 5가지

```
Q1. 외부 리소스(DB, Redis, 네트워크, 시간)에 접근하는가?  → Yes: Mock
Q2. Repository / 외부 연동 인터페이스인가?                 → Yes: Mock
Q3. 실제로 쓰면 테스트가 1초 이상 걸리는가?                → Yes: Mock
Q4. 순수 계산 로직만 있는가?                               → Yes: 실제 객체
Q5. Value Object 또는 DTO 인가?                            → Yes: 실제 객체
```

### 3.4 흔한 오용

```java
// ❌ VO 를 Mock — 검증 로직이 사라져 테스트가 거짓 통과한다
Rating rating = mock(Rating.class);
when(rating.value()).thenReturn(5);

// ✅
Rating rating = new Rating(5);
```

```java
// ❌ Aggregate 를 Mock — "호출되었는가" 만 보고 실제 규칙은 검증하지 않는다
Review review = mock(Review.class);
service.delete(command);
verify(review).delete(any(), any());

// ✅ 실제 객체로 상태 변화를 검증한다
Review review = Review.write(...);
given(reviewRepository.findById(reviewId)).willReturn(Optional.of(review));
service.delete(command);
assertThat(review.status()).isEqualTo(ReviewStatus.DELETED);
```

---

## 4. Given-When-Then

모든 테스트를 세 블록으로 나누고 빈 줄로 구분한다.

```java
@Test
@DisplayName("작성자는 자신의 리뷰를 삭제할 수 있다")
void delete_whenRequesterIsAuthor_shouldMarkAsDeleted() {
    // given
    MemberId author = MemberId.of("MEM-1");
    Review review = Review.write(ReviewId.of("REV-1"), PlaceId.of("PLC-1"), author,
            new Rating(5), "좋아요", Instant.parse("2026-08-19T10:00:00Z"));
    Instant now = Instant.parse("2026-08-19T11:00:00Z");

    // when
    review.delete(author, now);

    // then
    assertThat(review.status()).isEqualTo(ReviewStatus.DELETED);
    assertThat(review.updatedAt()).isEqualTo(now);
}
```

---

## 5. 테스트 이름 규칙

**메서드명**: `<대상메서드>_<조건>_<기대결과>`
**`@DisplayName`**: 한국어 한 문장으로 사양을 서술한다.

```java
@Test
@DisplayName("작성자가 아닌 사용자는 리뷰를 삭제할 수 없다")
void delete_whenRequesterIsNotAuthor_shouldThrow() { ... }
```

`test1`, `삭제테스트`, `shouldWork` 같은 이름은 금지.

---

## 6. 계층별 상세 규칙

### 6.1 domain 테스트
- **Spring 컨텍스트 금지** (`@SpringBootTest`, `@ExtendWith(SpringExtension.class)` 금지)
- Mockito 를 거의 쓰지 않는다
- `@Nested` 로 케이스를 그룹화한다
- **상태 전이의 모든 분기**와 불변식 위반 케이스를 검증한다

```java
class ReviewTest {

    @Nested
    @DisplayName("리뷰 작성")
    class Write {
        @Test
        @DisplayName("내용이 1000자를 넘으면 작성할 수 없다")
        void write_whenContentTooLong_shouldThrow() {
            assertThatThrownBy(() -> Review.write(..., "a".repeat(1001), now))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
```

### 6.2 application 테스트
- `@ExtendWith(MockitoExtension.class)`
- **Repository, 외부 연동 인터페이스, EventPublisher 만 Mock.** 도메인 객체는 실제로 생성한다
- `Clock.fixed(Instant.parse("..."), ZoneOffset.UTC)` 로 시간을 고정한다
- 성공 경로 + **모든 예외 경로**를 검증한다
- Mock 검증은 호출 여부뿐 아니라 **인자와 횟수**까지 확인한다

```java
@ExtendWith(MockitoExtension.class)
class WriteReviewServiceTest {

    @Mock ReviewRepository reviewRepository;
    @Mock PlaceReader placeReader;
    @Mock ApplicationEventPublisher eventPublisher;

    Clock clock = Clock.fixed(Instant.parse("2026-08-19T10:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("같은 장소에 이미 리뷰를 쓴 회원은 다시 쓸 수 없다")
    void write_whenDuplicated_shouldThrow() {
        // given
        given(placeReader.getById(any())).willReturn(openPlace());
        given(reviewRepository.existsByPlaceIdAndAuthorId(any(), any())).willReturn(true);

        // when & then
        assertThatThrownBy(() -> service.write(command))
                .isInstanceOf(DuplicateReviewException.class);
        then(reviewRepository).should(never()).save(any());
    }
}
```

### 6.3 infrastructure (JPA) 테스트
- `@DataJpaTest` 사용
- **`TestEntityManager.flush()` + `clear()` 후 조회한다.** 생략하면 1차 캐시가 응답해
  실제 매핑 오류(컬럼 누락, 타입 불일치)를 못 잡는다
- unique/not-null 같은 **실제 DB 제약**을 검증한다
- Mapper 는 `도메인 → Entity → 도메인` 왕복이 동등한지 확인한다

### 6.4 infrastructure (Redis) 테스트
- 캐시 히트/미스뿐 아니라 **Redis 장애 시 폴백 경로**를 반드시 테스트한다
  (연결 예외를 던지도록 스텁하고, 예외가 밖으로 새지 않는지 확인)
- TTL 만료는 `Thread.sleep()` 대신 TTL 값 자체를 검증하거나 짧은 TTL 로 확인한다

### 6.5 presentation 테스트
- `@WebMvcTest(XxxController.class)` 로 **대상 Controller 만** 올린다
- Application Service 는 `@MockitoBean` 으로 대체한다
- **HTTP 상태, JSON 구조, 검증 실패(400), 예외 매핑(404/409)** 를 모두 검증한다
- 응답 JSON 의 속성명이 camelCase 인지 여기서 확인한다 (`api-conventions` 규칙)

### 6.6 통합 테스트
- `@SpringBootTest` 는 **10개 이하**로 제한한다
- 크리티컬 플로우만 (예: 리뷰 작성 → 장소 평점 갱신)
- 외부 시스템은 Testcontainers 또는 MockWebServer 로 대체한다

---

## 7. 테스트 환경 — Testcontainers (실제 MySQL / Redis)

**H2 를 쓰지 않는다.** 테스트도 운영과 같은 엔진 위에서 돈다.
Spring 컨텍스트가 필요한 테스트는 `TestcontainersConfiguration` 을 import 해 실제 컨테이너에 붙는다.

```java
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewIntegrationTest { ... }
```

`support/container/TestcontainersConfiguration` 이 MySQL 8.4 / Redis 7-alpine 컨테이너를 **빈으로**
선언하고, `@ServiceConnection` 이 접속 정보를 주입한다. 그래서 테스트 `application.yaml` 에
datasource 를 적지 않는다.

### 7.1 왜 H2 를 버렸나 ⭐

H2 의 MySQL 호환 모드는 **호환일 뿐 동일하지 않다.** 아래는 H2 에서 통과해도 MySQL 에서 깨진다.

- **락 동작** — `SELECT ... FOR UPDATE` 의 대기·갭 락, 격리 수준별 동시성
- unique 제약 위반 시점, `ON DUPLICATE KEY UPDATE`
- MySQL 고유 함수 (`JSON_*`, `GROUP_CONCAT` 옵션, `MATCH ... AGAINST`), 인덱스 힌트
- 컬럼 타입·길이 경계 (utf8mb4 문자열 길이, `DATETIME` 정밀도)
- 정렬 순서 (collation 차이로 `ORDER BY` 결과가 달라진다)

특히 **동시성 테스트가 H2 에서 통과하고 운영에서 깨지는 상황은 테스트가 없느니만 못하다.**
"락을 검증했다"는 잘못된 확신을 주기 때문이다.

실제 MySQL 에서 측정한 결과 — 재고 100 개에 200 명이 동시에 신청:

| | 성공 | 잔여 재고 |
|---|---|---|
| `SELECT ... FOR UPDATE` | 100 | 0 |
| 락 없음 | **108** | **-8** ← 초과 발급 |

이 차이를 잡아내지 못하는 테스트 환경은 의미가 없다.

### 7.2 Docker 가 필요한 테스트, 필요 없는 테스트

| 테스트 | Docker | 이유 |
|---|---|---|
| domain 단위 테스트 | ❌ | Spring 자체가 없다 |
| application 단위 테스트 (Mockito) | ❌ | Repository 를 Mock |
| `@WebMvcTest` | ❌ | datasource 를 올리지 않는 슬라이스 |
| `@DataJpaTest`, `@SpringBootTest` | ✅ | 실제 DB 연결 |

빠른 피드백이 필요하면 Docker 없이 도는 것만 돌린다.

```bash
./gradlew test --tests '*domain*' --tests '*ServiceTest'   # Docker 불필요
./gradlew test                                              # 전체 (Docker 필요)
```

### 7.3 컨테이너를 빈으로 선언하는 이유

`@Container` 정적 필드는 **테스트 클래스마다** 컨테이너를 새로 띄운다.
빈으로 두면 Spring 의 컨텍스트 캐시를 타서 같은 설정을 쓰는 클래스들이 하나를 공유한다.
부팅 비용(수 초)을 클래스 수만큼 곱하지 않으려면 빈으로 선언한다.

### 7.4 Docker 런타임 주의

Testcontainers 는 정리용 Ryuk 컨테이너에 도커 소켓을 마운트한다.
Colima / Rancher Desktop 처럼 소켓이 VM 안에 있으면 호스트 경로를 그대로 마운트하려다 실패하므로,
`build.gradle.kts` 의 test 태스크가 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` 을
설정한다. 직접 설정한 값이 있으면 그것을 존중한다.

컨테이너 기동 실패로 테스트가 깨지면 **먼저 `docker info` 로 데몬부터 확인한다.**

### 7.5 Redis 가 필요한 테스트

Redis 도 컨테이너로 붙는다. 다만 **폴백 경로** 테스트는 컨테이너 없이 한다 —
연결 예외를 던지도록 스텁하고 예외가 밖으로 새지 않는지만 확인하면 된다(6.4절).

---

## 8. 동시성 테스트

재고 차감, 중복 방지, 조회수 증가처럼 **경합이 정답을 바꾸는 로직**은 단일 스레드 테스트로 검증되지 않는다.
`src/test/java/com/k_place/support/concurrent/` 의 `ConcurrentRunner` 를 쓴다. **직접 executor 를 짜지 않는다.**

```java
import static com.k_place.support.concurrent.ConcurrentRunner.run;

@Test
@DisplayName("재고 100개에 200명이 동시에 신청하면 정확히 100명만 성공한다")
void write_underContention_shouldNotOverIssue() {
    run(200, i -> issueService.issue(new IssueCommand(memberId(i), couponId)))
            .assertSuccessCount(100)
            .assertFailureCount(100);

    assertThat(inventoryRepository.findById(couponId).orElseThrow().remaining()).isZero();
}
```

### 8.1 왜 유틸을 쓰나

스레드를 그냥 띄우면 먼저 시작한 스레드가 끝난 뒤 다음이 시작되어 **경합이 재현되지 않는다.**
`ConcurrentRunner` 는 두 단계 래치로 모든 작업을 출발선에 세운 뒤 동시에 출발시킨다.
고정 크기 풀 대신 **가상 스레드**를 써서, 풀 크기보다 많은 작업을 넣었을 때 앞선 작업이 출발 신호를
기다리며 스레드를 점유해 나머지가 시작되지 못하는 함정을 없앴다.

| 메서드 | 용도 |
|---|---|
| `run(n, task)` | n개 동시 실행 (타임아웃 10초) |
| `run(n, timeout, task)` | 타임아웃 지정 |
| `result.assertAllSucceeded()` | 전부 성공. 실패 시 첫 예외를 cause 로 첨부 |
| `result.assertSuccessCount(n)` / `assertFailureCount(n)` | 성공·실패 건수 |
| `result.exceptionsOf(Type.class)` | 예외 타입별 추출 (하위 타입 포함) |

작업이 던진 예외는 다른 작업을 중단시키지 않고 수집된다. 그래서 람다 안에서 try/catch 를 쓸 필요가 없고,
"200명 중 100명은 예외" 같은 검증을 예외 개수로 표현할 수 있다.

### 8.2 결정적으로 단언한다 ⭐

**동시성 테스트가 한 번 통과했다고 락이 올바르다는 뜻이 아니다.** 경합 버그는 확률적으로 나타난다.
따라서 단언은 타이밍이 아니라 **결정적인 사후 상태**로 써야 한다.

```java
// ❌ 실행할 때마다 값이 달라 flaky
assertThat(result.successCount()).isGreaterThan(50);

// ✅ 락이 올바르면 항상 같은 값
result.assertSuccessCount(100);
assertThat(inventory.remaining()).isZero();     // 음수면 초과 발급
assertThat(couponRepository.count()).isEqualTo(100);
```

무엇이 깨지는지도 함께 생각한다 — 락이 없으면 `successCount > 100`, 재고가 음수, unique 제약 위반 등.
그 실패 모드가 단언에 걸리지 않으면 그 테스트는 락을 검증하지 못한다.

### 8.3 어느 계층에서 쓰나

| 대상 | 위치 | 비고 |
|---|---|---|
| DB 락 (비관적/낙관적 락, unique 제약) | `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` | 실제 MySQL 필요 (7.1절) |
| Redis 원자 연산 (`INCR`, Lua, 분산 락) | 위와 동일 | 실제 Redis 필요 |
| 도메인 객체의 스레드 안전성 | domain 테스트 | Spring·Docker 없이 가능 |

DB 락 정합성은 **반드시 실제 MySQL 위에서** 검증한다. H2 는 갭 락·격리 수준을 재현하지 못해
락이 없어도 통과할 수 있다 (7.1절의 측정 결과 참조).

동시성 테스트에서는 **커넥션 풀이 동시 요청 수보다 커야 한다.**
작으면 락이 아니라 풀 고갈로 실패해 원인을 오해하게 된다.

```java
@DynamicPropertySource
static void poolSize(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "32");
}
```

MySQL 쪽 `max_connections` 는 `TestcontainersConfiguration` 이 500 으로 올려 둔다.

### 8.4 주의

- 동시성 테스트는 느리고 CI 를 불안정하게 만든다. **경합이 정답을 바꾸는 지점에만** 쓴다.
- 타임아웃 초과로 실패하면 데드락이나 DB lock-wait 을 의심한다. 타임아웃을 늘려 덮지 않는다.
- `Thread.sleep()` 으로 순서를 맞추지 않는다. 래치는 `ConcurrentRunner` 가 이미 처리한다.

---

## 9. Fixture 관리

도메인 객체 생성이 복잡하면 **Object Mother** 패턴으로 분리한다.

```java
// src/test/java/com/k_place/review/fixture/ReviewFixtures.java
public final class ReviewFixtures {

    public static Review.Builder aReview() {
        return new Review.Builder()
                .id(ReviewId.of("REV-1"))
                .placeId(PlaceId.of("PLC-1"))
                .authorId(MemberId.of("MEM-1"))
                .rating(new Rating(5))
                .content("기본 리뷰 내용");
    }
}

// 사용
Review deleted = ReviewFixtures.aReview().status(ReviewStatus.DELETED).build();
```

- 기본값은 **항상 유효한 상태**로 둔다.
- 테스트마다 **관심 있는 필드만** 오버라이드한다. 그 필드가 곧 그 테스트의 주제다.

---

## 10. 커버리지 기준

- domain: 분기 커버리지 **95% 이상**
- application: 라인 커버리지 **90% 이상**
- infrastructure: 정상/예외 각 1개 이상
- presentation: 성공 / 검증 실패 / 예외 매핑 각 1개 이상
- 전체: 라인 커버리지 **80% 이상**

숫자보다 **의미 있는 분기를 놓치지 않는 것**이 중요하다.
커버리지 도구(JaCoCo)는 도입 시 `build.gradle.kts` 에 플러그인을 추가한다.

---

## 11. 안티 패턴

| 안티 패턴 | 올바른 방법 |
|---|---|
| VO / Aggregate 를 Mock | 실제 객체 생성 |
| 모든 테스트를 `@SpringBootTest` | 계층에 맞는 슬라이스 테스트 |
| Happy Path 만 검증 | 실패·경계 케이스 필수 |
| `LocalDateTime.now()` 직접 호출 | `Clock.fixed(...)` 주입 |
| `Thread.sleep()` 으로 대기 | Awaitility 또는 동기 검증 |
| `test1()`, `shouldWork()` | `<메서드>_<조건>_<기대결과>` + `@DisplayName` |
| 과도한 `verify` (모든 호출 검증) | 그 테스트의 주제만 검증 |
| `@Disabled` 방치 | 고치거나 삭제 |
| 한 테스트에 여러 시나리오 | 시나리오당 테스트 1개 |
| `@DataJpaTest` 에서 flush/clear 누락 | 조회 전 `flush()` + `clear()` |
| Controller 테스트를 `@SpringBootTest` 로 | `@WebMvcTest(대상Controller)` |
| Redis 폴백 경로 미검증 | 연결 예외 스텁으로 폴백 확인 |
| 동시성 테스트에서 executor 직접 구성 | `ConcurrentRunner` 사용 (8절) |
| 동시성 결과를 `isGreaterThan` 으로 단언 | 결정적인 사후 상태로 단언 (8.2절) |

---

## 12. 실행 명령

```bash
./gradlew test                            # 전체 (Docker 필요 — Testcontainers)
./gradlew test --tests '*ReviewTest'      # 단일 클래스
./gradlew test --tests '*domain*'         # 계층별 (Docker 불필요)
./gradlew build                           # 컴파일 + 전체 테스트
```

컨테이너 기동 실패로 깨지면 `docker info` 로 데몬부터 확인한다 (7.4절).

모든 테스트가 통과한 뒤에 커밋한다.

## 다음 단계

→ `.claude/skills/pr-guidelines/SKILL.md`

---
name: testing-junit
description: k-place 에서 JUnit 5 기반 테스트를 작성할 때 사용한다. 계층별 테스트 전략(persistence/application/presentation), Mock 사용 기준과 실제 객체 사용 기준, Given-When-Then 구조, 테스트 네이밍, Spring 슬라이스 테스트 선택, docker compose 인프라를 공유하고 @AfterEach 로 정리하는 테스트 환경(H2 를 쓰지 않는 이유), ConcurrentRunner 를 쓰는 동시성 테스트, Fixture 관리, 커버리지 기준을 포함한다. "테스트 작성", "단위 테스트", "통합 테스트", "동시성 테스트", "Mockito", "JUnit", "@SpringBootTest" 언급이 있거나 구현 직후 검증 단계에서 반드시 사용한다.
---

# Testing with JUnit 5

**독립적이고 빠른** 테스트를 작성하기 위한 지침. 구현이 끝난 직후, 커밋 전에 읽는다.

계층 구조는 `code-guidelines/SKILL.md` 2부를 따른다 — 규칙은 JpaEntity 안에 있고,
Application 은 JpaRepository 를 직접 호출한다. 테스트도 그 구조를 그대로 따라간다.

---

## 1. 핵심 원칙

1. **계층별 독립 테스트** — 각 계층은 자기 책임만 검증한다. 다른 계층이 망가져도 내 테스트는 돈다.
2. **Mock 은 경계에만** — 순수 객체는 실제로 쓴다.
3. **테스트도 문서** — 이름만 봐도 사양이 보여야 한다.
4. **빠른 피드백** — Docker 없이 도는 테스트 전체가 1초 이내.
5. **결정론적** — 시간·순서·외부 상태에 의존하지 않는다.

---

## 2. 계층별 전략

| 대상 | 애너테이션 | Spring | DB/Redis | Mock 대상 |
|---|---|---|---|---|
| JpaEntity 의 비즈니스 메서드 | 없음 (순수 JUnit) | ❌ | ❌ | 없음 |
| Application | `@ExtendWith(MockitoExtension.class)` | ❌ | ❌ | JpaRepository, 외부 연동, Clock |
| JpaRepository (쿼리·제약) | `@DataJpaTest` | 슬라이스 | ✅ | 없음 |
| Redis 컴포넌트 | `@DataRedisTest` 또는 직접 구성 | 슬라이스 | ✅ | 없음 |
| 외부 HTTP 연동 | `@RestClientTest` / MockWebServer | 슬라이스 | ❌ | HTTP 서버 |
| Controller | `@WebMvcTest(XxxController.class)` | 슬라이스 | ❌ | Application |
| 통합 (E2E) | `@SpringBootTest` | 전체 | ✅ | 최소화 |

**JpaEntity 의 비즈니스 메서드는 `new` 로 만들어 순수 JUnit 으로 검증한다.**
`@Entity` 가 붙어 있어도 그냥 자바 객체다. 규칙 테스트에 Spring 이나 DB 를 끌어들이지 않는다.

**반드시**: 분류와 애너테이션이 일치해야 한다. 규칙 테스트에 `@SpringBootTest` 가 보이면 즉시 수정.

DB/Redis 가 ✅ 인 테스트는 `IntegrationTest` 를 상속하고, **`docker compose up -d` 로 인프라가
떠 있어야 한다**(7절). 그 외에는 Docker 없이 돈다.

**테스트 패키지는 main 패키지 구조를 그대로 미러링한다.**
`com.k_place.persistence.RewardJpaEntityTest` 처럼 대상과 같은 패키지에 둔다.

---

## 3. Mock vs 실제 객체 ⭐

### 3.1 Mock 을 **써야** 하는 것
- 외부 시스템 경계: **MySQL, Redis, 외부 HTTP API, 파일 시스템, 메일**
- 비결정적 요소: **Clock, Random, UUID 생성기**
- 느리거나 비싼 작업
- **JpaRepository** — Application 테스트에서는 Mock 으로 대체한다

### 3.2 Mock 을 **쓰면 안** 되는 것
- **JpaEntity** (`RewardJpaEntity`, `PledgeJpaEntity`) → `new` 로 실제 객체
- DTO, Command, Request/Response → 실제 객체
- 외부 의존이 없는 계산 로직 → 실제 객체
- 테스트 대상(SUT) → 당연히 실제

### 3.3 판단 질문 4가지

```
Q1. 외부 리소스(DB, Redis, 네트워크, 시간)에 접근하는가?  → Yes: Mock
Q2. JpaRepository 또는 외부 연동 컴포넌트인가?             → Yes: Mock
Q3. 실제로 쓰면 테스트가 1초 이상 걸리는가?                → Yes: Mock
Q4. 데이터와 규칙만 가진 객체인가? (JpaEntity, DTO)        → Yes: 실제 객체
```

### 3.4 흔한 오용

```java
// ❌ JpaEntity 를 Mock — "호출되었는가" 만 보고 규칙 자체는 검증하지 않는다
RewardJpaEntity reward = mock(RewardJpaEntity.class);
pledgeApplication.create(command);
verify(reward).decreaseSoldQuantity(anyInt());

// ✅ 실제 객체로 상태 변화를 검증한다
RewardJpaEntity reward = new RewardJpaEntity(projectId, 10, 15_000L);
given(rewardJpaRepository.findById(1L)).willReturn(Optional.of(reward));

pledgeApplication.create(command);

assertThat(reward.availableQuantity()).isEqualTo(8);
```

`@Entity` 를 Mock 하고 싶어지면, 그 Entity 가 규칙을 갖고 있다는 뜻이다.
규칙을 검증하려면 실제 객체여야 한다.

---

## 4. Given-When-Then

모든 테스트를 세 블록으로 나누고 빈 줄로 구분한다.

```java
@Test
@DisplayName("재고가 남아 있으면 요청 수량만큼 차감된다")
void decreaseSoldQuantity_whenEnoughStock_shouldDecrease() {
    // given
    RewardJpaEntity reward = new RewardJpaEntity(1L, 10, 15_000L);

    // when
    reward.decreaseSoldQuantity(3);

    // then
    assertThat(reward.getSoldQuantity()).isEqualTo(3);
    assertThat(reward.availableQuantity()).isEqualTo(7);
}
```

---

## 5. 테스트 이름 규칙

**메서드명**: `<대상메서드>_<조건>_<기대결과>`
**`@DisplayName`**: 한국어 한 문장으로 사양을 서술한다.

```java
@Test
@DisplayName("남은 재고보다 많은 수량은 차감할 수 없다")
void decreaseSoldQuantity_whenExceedsStock_shouldThrow() { ... }
```

`test1`, `재고테스트`, `shouldWork` 같은 이름은 금지.

---

## 6. 계층별 상세 규칙

### 6.1 JpaEntity 규칙 테스트
- **Spring 컨텍스트 금지** (`@SpringBootTest`, `@DataJpaTest` 금지). `new` 로 만들어 메서드만 호출한다
- Mockito 를 쓰지 않는다
- `@Nested` 로 케이스를 그룹화한다
- **상태 전이의 모든 분기**와 불변식 위반 케이스를 검증한다
- getter 만 있는 Entity 는 테스트하지 않는다. **검증할 규칙이 있을 때만** 테스트를 쓴다

```java
class RewardJpaEntityTest {

    @Nested
    @DisplayName("재고 차감")
    class DecreaseSoldQuantity {

        @Test
        @DisplayName("남은 재고보다 많은 수량은 차감할 수 없다")
        void decreaseSoldQuantity_whenExceedsStock_shouldThrow() {
            RewardJpaEntity reward = new RewardJpaEntity(1L, 10, 15_000L);

            assertThatThrownBy(() -> reward.decreaseSoldQuantity(11))
                    .isInstanceOf(InvalidQuantityException.class);
            assertThat(reward.getSoldQuantity()).isZero();   // 실패해도 상태가 변하지 않는다
        }

        @Test
        @DisplayName("0 이하 수량은 차감할 수 없다")
        void decreaseSoldQuantity_whenNotPositive_shouldThrow() { ... }
    }
}
```

### 6.2 Application 테스트
- `@ExtendWith(MockitoExtension.class)`
- **JpaRepository 와 외부 연동만 Mock.** JpaEntity 는 `new` 로 실제 생성한다
- 시간이 규칙에 관여하면 `Clock.fixed(Instant.parse("..."), ZoneOffset.UTC)` 로 고정한다
- 성공 경로 + **모든 예외 경로**를 검증한다
- Mock 검증은 호출 여부뿐 아니라 **인자와 횟수**까지 확인한다

```java
@ExtendWith(MockitoExtension.class)
class PledgeApplicationTest {

    @Mock ProjectJpaRepository projectJpaRepository;
    @Mock RewardJpaRepository rewardJpaRepository;
    @Mock PledgeJpaRepository pledgeJpaRepository;

    @InjectMocks PledgeApplication pledgeApplication;

    @Test
    @DisplayName("재고가 부족하면 후원이 생성되지 않는다")
    void create_whenRewardSoldOut_shouldThrow() {
        // given
        given(projectJpaRepository.findById(1L)).willReturn(Optional.of(progressProject()));
        given(rewardJpaRepository.decreaseQuantity(10L, 2)).willReturn(0);   // 갱신 0건 = 재고 부족

        // when & then
        assertThatThrownBy(() -> pledgeApplication.create(command))
                .isInstanceOf(RewardSoldOutException.class);
        then(pledgeJpaRepository).should(never()).save(any());
    }
}
```

### 6.3 JpaRepository 테스트 (`@DataJpaTest`)
- 매핑이 아니라 **쿼리와 DB 제약**을 검증한다. 직접 만든 `@Query`·`@Modifying` 이 여기 대상이다
- **`TestEntityManager.flush()` + `clear()` 후 조회한다.** 생략하면 1차 캐시가 응답해
  실제 매핑 오류(컬럼 누락, 타입 불일치)를 못 잡는다
- unique / not-null / **CHECK 제약**이 실제로 막는지 확인한다
- 원자적 UPDATE 는 **갱신 행 수(0/1)** 를 단언한다. 조건에 걸려 0 이 나오는 경우가 핵심이다

### 6.4 Redis 컴포넌트 테스트
- 캐시 히트/미스뿐 아니라 **Redis 장애 시 폴백 경로**를 반드시 테스트한다
  (연결 예외를 던지도록 스텁하고, 예외가 밖으로 새지 않는지 확인)
- TTL 만료는 `Thread.sleep()` 대신 TTL 값 자체를 검증하거나 짧은 TTL 로 확인한다

### 6.5 Controller 테스트
- `@WebMvcTest(XxxController.class)` 로 **대상 Controller 만** 올린다
- Application 은 `@MockitoBean` 으로 대체한다
- **HTTP 상태, JSON 구조, 검증 실패(400), 예외 매핑(404/409)** 를 모두 검증한다
- 응답 JSON 의 속성명이 camelCase 인지 여기서 확인한다 (`api-conventions` 규칙)

### 6.6 통합 테스트
- `@SpringBootTest` 는 **10개 이하**로 제한한다
- 크리티컬 플로우만 (예: 후원 생성 → 리워드 재고 차감)
- 외부 HTTP 는 MockWebServer 로 대체한다. DB/Redis 는 실제 인프라를 쓴다(7절)

---

## 7. 테스트 환경 — 공유 컨테이너 + 데이터 정리

**테스트 전에 인프라를 띄운다.** 테스트가 컨테이너를 직접 만들지 않고 `docker-compose.yml` 의
MySQL/Redis 에 붙는다.

```bash
docker compose up -d      # 한 번만. 그 뒤로는 계속 재사용
./gradlew test
```

Spring 컨텍스트가 필요한 테스트는 `IntegrationTest` 를 상속한다.

```java
class PledgeIntegrationTest extends IntegrationTest {
    @Test
    void ...
}
```

### 7.1 격리는 데이터 정리로 만든다 ⭐

컨테이너를 새로 띄우는 대신 **매 테스트가 끝날 때 자기가 쓴 공간을 비운다**
(`support/cleanup/InfrastructureCleaner`). `IntegrationTest` 의 `@AfterEach` 가 호출한다.

개발 데이터와 섞이지 않게 공간을 나눠 둔다.

| | 개발 | 테스트 |
|---|---|---|
| MySQL | `k_place` 스키마 | `k_place_test` 스키마 |
| Redis | 0번 DB | 1번 DB |

정리 대상은 테스트 쪽뿐이다. `FLUSHALL` 이 아니라 `FLUSHDB`, `k_place` 스키마는 손대지 않는다.
실측으로 확인한 것 — 테스트를 돌려도 개발 데이터(`k_place` 의 행, Redis 0번 키)는 그대로 남는다.

**`@BeforeEach` 가 아니라 `@AfterEach` 인 이유** — 실패한 테스트의 데이터를 남겨 두면
다음 테스트가 그 영향을 받는다. 자기가 어지른 것은 자기가 치운다.

**`DELETE` 가 아니라 `TRUNCATE` 인 이유** — 자동 증가 값까지 되돌아간다.
"첫 저장이면 ID 가 1" 같은 단언이 실행 순서에 흔들리지 않는다.

### 7.2 이 방식의 한계 — 알고 써야 하는 것

컨테이너를 매번 새로 띄우는 방식과 달리 **정리 대상 밖의 상태는 남는다.**

- 스키마 변경(`ALTER TABLE`), 전역 변수, 다른 스키마의 데이터
- 테스트가 중간에 죽으면 `@AfterEach` 가 돌지 않아 데이터가 남는다
- **두 사람이 같은 DB 를 동시에 쓰면 서로의 데이터를 지운다** (로컬 전용이라 실무상 문제는 없지만,
  CI 에서 job 을 병렬로 돌린다면 job 마다 인프라를 분리해야 한다)

이상해지면 인프라를 새로 만든다.

```bash
docker compose down -v && docker compose up -d
```

### 7.3 왜 H2 를 쓰지 않나 ⭐

H2 의 MySQL 호환 모드는 **호환일 뿐 동일하지 않다.** 아래는 H2 에서 통과해도 MySQL 에서 깨진다.

- **락 동작** — `SELECT ... FOR UPDATE` 의 대기·갭 락, 격리 수준별 동시성
- unique 제약 위반 시점, `ON DUPLICATE KEY UPDATE`
- MySQL 고유 함수(`JSON_*`, `MATCH ... AGAINST`), 인덱스 힌트
- 컬럼 타입·길이 경계(utf8mb4 문자열 길이, `DATETIME` 정밀도), collation 정렬 순서

실제 MySQL 에서 측정한 결과 — 재고 100 개에 200 명이 동시에 신청:

| | 성공 | 잔여 재고 |
|---|---|---|
| `SELECT ... FOR UPDATE` | 100 | 0 |
| 락 없음 | **108** | **-8** ← 초과 발급 |

이 차이를 잡아내지 못하는 테스트 환경은 의미가 없다.
**동시성 테스트가 H2 에서 통과하고 운영에서 깨지는 상황은 테스트가 없느니만 못하다.**

### 7.4 Docker 가 필요한 테스트, 필요 없는 테스트

| 테스트 | Docker | 이유 |
|---|---|---|
| JpaEntity 규칙 테스트 | ❌ | Spring 자체가 없다 |
| Application 단위 테스트 (Mockito) | ❌ | JpaRepository 를 Mock |
| `@WebMvcTest` | ❌ | datasource 를 올리지 않는 슬라이스 |
| `@DataJpaTest` / `IntegrationTest` 상속 | ✅ | 실제 DB 연결 |

**DB 가 필요 없으면 `IntegrationTest` 를 상속하지 않는다.** 현재 18개 중 17개가 여기 해당하고
1초 안에 끝난다. 빠른 피드백이 필요하면 이것만 골라 돌린다.

```bash
./gradlew test --tests '*JpaEntityTest' --tests '*ApplicationTest'   # Docker 불필요
```

### 7.5 Redis 가 필요한 테스트

Redis 도 같은 컨테이너를 쓴다(1번 DB). **폴백 경로** 테스트는 인프라 없이 한다 —
연결 예외를 던지도록 스텁하고 예외가 밖으로 새지 않는지만 확인하면 된다(6.4절).

---

## 8. 동시성 테스트

재고 차감, 중복 방지, 조회수 증가처럼 **경합이 정답을 바꾸는 로직**은 단일 스레드 테스트로 검증되지 않는다.
`src/test/java/com/k_place/support/concurrent/` 의 `ConcurrentRunner` 를 쓴다. **직접 executor 를 짜지 않는다.**

```java
import static com.k_place.support.concurrent.ConcurrentRunner.run;

@Test
@DisplayName("재고 100개에 200명이 동시에 신청하면 정확히 100명만 성공한다")
void create_underContention_shouldNotOverSell() {
    run(200, i -> pledgeApplication.create(commandFor(userId(i), rewardId)))
            .assertSuccessCount(100)
            .assertFailureCount(100);

    assertThat(rewardJpaRepository.findById(rewardId).orElseThrow().availableQuantity()).isZero();
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
assertThat(reward.availableQuantity()).isZero();      // 음수면 초과 판매
assertThat(pledgeJpaRepository.count()).isEqualTo(100);
```

무엇이 깨지는지도 함께 생각한다 — 락이 없으면 `successCount > 100`, 재고가 음수, unique 제약 위반 등.
그 실패 모드가 단언에 걸리지 않으면 그 테스트는 락을 검증하지 못한다.

### 8.3 어느 계층에서 쓰나

| 대상 | 위치 | 비고 |
|---|---|---|
| DB 락 (비관적/낙관적 락, unique 제약) | `IntegrationTest` 상속 | 실제 MySQL 필요 (7.3절) |
| Redis 원자 연산 (`INCR`, Lua, 분산 락) | 위와 동일 | 실제 Redis 필요 |
| 순수 객체의 스레드 안전성 | 단위 테스트 | Spring·Docker 없이 가능 |

DB 락 정합성은 **반드시 실제 MySQL 위에서** 검증한다. H2 는 갭 락·격리 수준을 재현하지 못해
락이 없어도 통과할 수 있다 (7.3절의 측정 결과 참조).

**커넥션 한도가 동시 요청 수보다 커야 한다.** 작으면 락이 아니라 자원 고갈로 실패해
원인을 오해하게 된다. 아래 두 곳이 이미 맞춰져 있으니, 더 큰 동시성을 쓸 때만 올린다.

| 설정 | 값 | 위치 |
|---|---|---|
| Hikari `maximum-pool-size` | 32 | `src/test/resources/application.yaml` |
| MySQL `max_connections` | 500 | `docker-compose.yml` |

### 8.4 주의

- 동시성 테스트는 느리고 CI 를 불안정하게 만든다. **경합이 정답을 바꾸는 지점에만** 쓴다.
- 타임아웃 초과로 실패하면 데드락이나 DB lock-wait 을 의심한다. 타임아웃을 늘려 덮지 않는다.
- `Thread.sleep()` 으로 순서를 맞추지 않는다. 래치는 `ConcurrentRunner` 가 이미 처리한다.
- **행을 둘 이상 잠그는 요청은 서로 반대 순서로 동시에 흘려본다.** 락 순서가 고정돼 있지 않으면
  이 테스트가 데드락을 드러낸다 (`code-guidelines` 2.2절 락 순서).

---

## 9. Fixture 관리

**생성자 호출이 한 줄이면 픽스처를 만들지 않는다.** 같은 준비 코드가 세 군데 넘게 반복될 때만
static 팩토리로 뽑는다.

```java
// src/test/java/com/k_place/persistence/RewardFixtures.java
public final class RewardFixtures {

    /** 재고 10개, 15,000원짜리 기본 리워드. */
    public static RewardJpaEntity aReward() {
        return new RewardJpaEntity(1L, 10, 15_000L);
    }

    public static RewardJpaEntity soldOutReward() {
        RewardJpaEntity reward = aReward();
        reward.decreaseSoldQuantity(10);
        return reward;
    }
}
```

- 기본값은 **항상 유효한 상태**로 둔다.
- 테스트마다 **관심 있는 것만** 바꾼다. 그것이 곧 그 테스트의 주제다.
- 빌더가 필요할 만큼 필드가 많아지면, 픽스처가 아니라 **Entity 설계**를 먼저 의심한다.

---

## 10. 커버리지 기준

- JpaEntity 의 비즈니스 메서드: 분기 커버리지 **95% 이상**
- Application: 라인 커버리지 **90% 이상**
- JpaRepository / Redis: 정상·예외 각 1개 이상
- Controller: 성공 / 검증 실패 / 예외 매핑 각 1개 이상
- 전체: 라인 커버리지 **80% 이상**

getter 만 있는 Entity, 필드 나열뿐인 DTO 는 커버리지 대상이 아니다.

숫자보다 **의미 있는 분기를 놓치지 않는 것**이 중요하다.
커버리지 도구(JaCoCo)는 도입 시 `build.gradle.kts` 에 플러그인을 추가한다.

---

## 11. 안티 패턴

| 안티 패턴 | 올바른 방법 |
|---|---|
| JpaEntity 를 Mock | `new` 로 실제 객체 생성 |
| 모든 테스트를 `@SpringBootTest` | 대상에 맞는 슬라이스 테스트 (2절 표) |
| JpaEntity 규칙 테스트에 `@DataJpaTest` | 순수 JUnit — DB 없이 `new` 로 검증 |
| Happy Path 만 검증 | 실패·경계 케이스 필수 |
| `LocalDateTime.now()` 직접 호출 (시간이 규칙일 때) | `Clock.fixed(...)` 주입 |
| `Thread.sleep()` 으로 대기 | Awaitility 또는 동기 검증 |
| `test1()`, `shouldWork()` | `<메서드>_<조건>_<기대결과>` + `@DisplayName` |
| 과도한 `verify` (모든 호출 검증) | 그 테스트의 주제만 검증 |
| `@Disabled` 방치 | 고치거나 삭제 |
| 한 테스트에 여러 시나리오 | 시나리오당 테스트 1개 |
| `@DataJpaTest` 에서 flush/clear 누락 | 조회 전 `flush()` + `clear()` |
| 원자적 UPDATE 의 갱신 행 수 미검증 | 0 건(조건 미충족) 케이스를 단언 (6.3절) |
| Controller 테스트를 `@SpringBootTest` 로 | `@WebMvcTest(대상Controller)` |
| Redis 폴백 경로 미검증 | 연결 예외 스텁으로 폴백 확인 |
| 동시성 테스트에서 executor 직접 구성 | `ConcurrentRunner` 사용 (8절) |
| 동시성 결과를 `isGreaterThan` 으로 단언 | 결정적인 사후 상태로 단언 (8.2절) |

---

## 12. 실행 명령

```bash
docker compose up -d                      # 전체 테스트 전 한 번 (7절)
./gradlew test                            # 전체
./gradlew test --tests '*RewardJpaEntityTest'   # 단일 클래스
./gradlew test --tests '*ApplicationTest'      # 대상별 (Docker 불필요)
./gradlew build                           # 컴파일 + 전체 테스트
```

접속 오류로 깨지면 **인프라부터 확인한다** — `docker compose ps`.
상태가 꼬였으면 `docker compose down -v && docker compose up -d` (7.2절).

모든 테스트가 통과한 뒤에 커밋한다.

## 다음 단계

→ `.claude/skills/pr-guidelines/SKILL.md`

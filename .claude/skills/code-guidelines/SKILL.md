---
name: code-guidelines
description: k-place 에서 기능 구현, 버그 수정, 리팩토링 등 코드를 건드리는 모든 작업에 사용한다. 1부는 계획 단계(요구사항 재진술, 영향 범위, TodoList, 승인), 2부는 계층 구조(persistence / application / presentation / common)와 JpaEntity 에 비즈니스 로직을 두는 규칙, 트랜잭션 경계, 예외 처리, Redis 사용 규칙을 다룬다. "구현해줘", "추가해줘", "고쳐줘", "리팩토링", "Service", "Entity", "Repository" 같은 언급이 있으면 코드를 한 줄이라도 쓰기 전에 반드시 사용한다.
---

# Code Guidelines - 계획과 계층 구조

k-place 는 **단일 모놀리식 애플리케이션**이다. 계층은 얇게 유지하고,
비즈니스 규칙은 **JpaEntity 안**에 둔다.

**핵심 원칙 두 가지**
1. 계획 없이 코드를 쓰지 않는다.
2. 규칙은 데이터를 가진 객체(= JpaEntity)가 지킨다. 계층을 늘려서 해결하지 않는다.

> 도메인 객체와 JpaEntity 를 따로 두는 DDD 전술 패턴(Aggregate/VO/Mapper/Repository 인터페이스)은
> **이 프로젝트에서 쓰지 않는다.** 파일 수가 규칙의 수보다 많아지는 순간 설계가 아니라 비용이다.

---

# 1부. 계획 (코드 작성 전)

## 1.1 목표

- 요구사항의 **모호함 제거**
- **영향 범위**(변경 파일, 스키마 변경, API 호환성) 파악
- **작업 순서** 확정 후 사용자 **승인**

## 1.2 Step 1 — 요구사항 재진술

사용자의 요청을 내 말로 다시 정리해 보여준다. 해석이 들어간 부분은 `(가정)` 으로 명시한다.

```
[요구사항 재진술]
요청: "후원 생성 기능을 추가해줘"

내 이해:
- 회원이 진행 중(PROGRESS)인 프로젝트에 리워드를 골라 후원한다
- 리워드마다 수량이 있고, 재고를 넘겨 후원할 수 없다 (가정)
- 한 번의 후원에 여러 리워드를 담을 수 있다 (가정)

확인 필요:
1. 결제 연동이 이 범위에 포함되는가?
2. 후원 취소 시 재고를 되돌리는가?
```

**불명확한 점이 2개 이상이면 여기서 멈추고 질문한다.** 추측으로 진행하지 않는다.

## 1.3 Step 2 — 규칙과 상태 정리

표를 채우되, **답이 "없음" 이면 그 줄은 지운다.** 빈칸을 채우려고 개념을 만들어내지 않는다.

| 질문 | 답 |
|---|---|
| 어떤 Entity 가 관련되는가? | |
| 그 Entity 가 지켜야 할 불변식은? (예: `sold ≤ total`) | |
| 상태 전이가 있는가? (예: CREATED → PROGRESS) | |
| 여러 Entity 에 걸친 규칙인가? (→ Application 이 조율) | |
| 동시성 경합이 정답을 바꾸는가? (→ 원자적 UPDATE / 락) | |

## 1.4 Step 3 — 영향 범위

변경될 파일을 미리 나열한다.

```
[영향 범위]
persistence/    PledgeJpaEntity(신규), PledgeJpaRepository(신규)
                RewardJpaEntity(재고 차감 메서드 추가)
application/    PledgeApplication(신규)
presentation/   PledgeController, CreatePledgeRequest, PledgeResponse(신규)
common/         ErrorCode 에 REWARD_SOLD_OUT 추가
스키마           pledge / pledge_reward_match 테이블 추가
```

## 1.5 Step 4 — API 설계 (해당 시)

`.claude/skills/api-conventions/SKILL.md` 를 열어 규칙을 확인하고, 요청/응답/에러 케이스까지 적는다.

## 1.6 Step 5 — TodoList

**저장 구조 → 규칙 → 노출** 순으로 진행한다.

```
[ ] 1. persistence: PledgeJpaEntity + 불변식 검증 메서드 + 단위 테스트
[ ] 2. persistence: PledgeJpaRepository (재고 차감 원자적 UPDATE 포함)
[ ] 3. application: PledgeApplication + 단위 테스트
[ ] 4. presentation: Controller, Request/Response DTO + @WebMvcTest
[ ] 5. 통합 테스트 (동시성 경합이 있으면 필수)
[ ] 6. 커밋 및 PR
```

## 1.7 Step 6 — 승인

Step 1~5 를 **한 번에** 보여주고 명시적 확인을 받는다. 승인 없이 구현으로 넘어가지 않는다.

## 1.8 계획 단계에서 하지 말 것

1. "일단 만들고 나서 물어보자" — 되돌리기 어렵다.
2. "아마 이럴 것이다" 추측 — 요구사항을 임의 확장하는 가장 흔한 실수.
3. TodoList 없이 진행 — 범위가 팽창한다.
4. 요구사항에 없는 확장 포인트 미리 만들기 — 인터페이스 1개짜리 추상화, 안 쓰는 이벤트.

---

# 2부. 계층 구조 (구현)

## 2.1 패키지와 의존성 방향

```
com.k_place/
  common/         전역 설정, 공통 예외(BusinessException, ErrorCode), 응답 포맷, 예외 핸들러
  domain/         도메인 enum(ProjectStatus 등), 도메인 예외
  persistence/    XxxJpaEntity, XxxJpaRepository  ← 비즈니스 규칙이 여기 산다
  application/    XxxApplication  (@Service, @Transactional)
  presentation/   Controller, Request/Response DTO
```

```
presentation → application → persistence
                    ↓
                 domain (enum, 예외)   ← 어디서든 참조 가능
```

- **Application 은 JpaRepository 를 직접 주입받아 호출한다.** Repository 인터페이스를 따로 만들고
  구현체를 두는 이중화를 하지 않는다.
- Controller 는 **Application 만** 호출한다. Repository 를 직접 주입받지 않는다.
- `common` 에는 도메인이 아닌 것만 둔다. 비즈니스 규칙을 넣으면 소유자가 사라진다.

**왜 Application 이 Repository 를 직접 부르나** — 사이에 낀 인터페이스는 구현이 하나뿐이면
아무것도 막아주지 못한다. 갈아끼울 후보가 실제로 생겼을 때 그때 뽑아내면 된다.

## 2.2 persistence 계층 — 규칙이 사는 곳

### JpaEntity

- 클래스명은 **`XxxJpaEntity`**.
- **비즈니스 메서드를 여기에 둔다.** 상태 전이와 불변식 검증이 Entity 의 책임이다.
- **`@Setter` / `@Data` 금지.** `@Getter` 는 허용. 상태 변경은 의미 있는 메서드로
  (`decreaseSoldQuantity()`, `close()`, `approve()`).
- 생성은 의미가 드러나는 생성자 또는 static 팩토리. JPA 용 no-arg 생성자는 `protected`.
- 불변식은 **메서드 진입 시 즉시 검증**하고, 위반이면 도메인 예외를 던진다.
- **JPA 연관관계 애너테이션 금지** (`@ManyToOne`, `@OneToMany`, `@OneToOne`, `@ManyToMany`).
  외래 ID 컬럼만 보유하고, 조회 조건 컬럼에는 인덱스를 명시한다.
  부모 존재 검증은 Application 이 `findById` 로 명시적으로 한다.

```java
// ❌ 금지
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "project_id")
private ProjectJpaEntity project;

// ✅ 권장
@Column(name = "project_id", nullable = false)
private Long projectId;
```

**이유**: 연관관계는 N+1, `LazyInitializationException`, 의도치 않은 cascade 를 만든다.
필요한 조인은 조회 전용 쿼리에서 명시적으로 작성한다.

### 규칙을 어디에 두나

| 상황 | 위치 |
|---|---|
| 한 Entity 의 필드만으로 판단 가능 (`sold + n ≤ total`) | **Entity 메서드** |
| 여러 Entity 를 읽어야 판단 가능 (프로젝트 상태 + 리워드 재고) | Application 이 조회 후 각 Entity 메서드 호출 |
| 경합이 정답을 바꾸는 갱신 (재고 차감) | **Repository 의 원자적 UPDATE** + DB CHECK 제약 |

**불변식은 DB 에서도 막는다.** `CHECK (total_quantity >= sold_quantity)` 같은 제약을 함께 건다.
어떤 경로로 들어와도 깨지지 않게 하는 마지막 방어선이다.

### 락 순서 ⭐

**한 트랜잭션이 행을 둘 이상 잠글 때는 항상 ID 오름차순으로 잠근다.**
입력 순서를 그대로 따르면 반대 순서로 들어온 두 요청이 서로를 기다려 데드락이 난다.
정렬은 잠그기 **직전**에 한다 — 검증·차감은 그 뒤 메모리에서.

### JpaRepository

- `JpaRepository<XxxJpaEntity, Long>` 를 상속한 인터페이스 하나면 충분하다.
- 메서드 이름 쿼리로 표현되지 않으면 `@Query` 를 쓴다.
- 경합이 있는 갱신은 **영속성 컨텍스트로 하지 않고 원자적 UPDATE** 로 한다.
  `@Modifying` + `WHERE` 절에 조건을 넣어 갱신 행 수(0/1)로 성공 여부를 판단한다.

## 2.3 application 계층

- `@Service`, `@Transactional` 은 **여기에만** 붙는다.
- 클래스명은 **`XxxApplication`** (기존 `PledgeApplication` 을 따른다).
- 하는 일: **조회 → Entity 메서드 호출 → 저장**, 그리고 여러 Entity 사이의 조율.
  규칙 자체를 여기에 쓰기 시작하면 그 규칙은 Entity 로 내려야 한다는 신호다.
- 입력은 presentation DTO 가 아니라 **Command/Request 레코드**로 받는다.
  (파라미터가 2개 이하로 단순하면 그냥 받아도 된다. 억지로 감싸지 않는다.)
- 시간이 규칙에 관여하면(만료, 마감) `Clock` 을 주입해 `Instant.now(clock)` 을 쓴다.
  관여하지 않으면 `@CreationTimestamp` 등으로 충분하다.
- 외부 시스템(HTTP, 메일) 호출은 인터페이스로 감싸도 되고 직접 호출해도 된다.
  **테스트에서 대체해야 할 때만** 인터페이스를 뽑는다.

### 트랜잭션 경계

**트랜잭션은 커밋·롤백이 의미 있는 작업만 감싼다.**

- 트랜잭션 **안**에 두지 않는다 — Redis 접근, 외부 HTTP 호출, 무거운 CPU 작업(암호 해싱 등).
  롤백돼도 되돌아가지 않으므로 넣을 이득이 없고, 커넥션 점유 시간만 늘린다.
- 트랜잭션 **안**에 둔다 — 여러 조회를 일관된 스냅샷으로 읽어야 할 때 그 조회들만.
- 조회 전용은 `@Transactional(readOnly = true)` — 단, **그 경계 안에 외부 I/O 가 없는지** 확인.
- 같은 클래스에서 `this.transactionalMethod()` 를 호출하면 프록시를 안 거쳐 트랜잭션이 아예 안 걸린다
  (self-invocation). 메서드 분리로 해결되지 않으므로 `TransactionTemplate` 을 쓴다.

**왜 중요한가**: 처리량 ≈ 커넥션 풀 크기 ÷ 점유 시간. 트랜잭션 안의 Redis·HTTP 호출은
내가 통제할 수 없는 시간을 점유 시간에 더한다. 풀은 애플리케이션 공유 자원이므로,
한 엔드포인트가 풀을 쥐면 **무관한 다른 엔드포인트까지 함께 죽는다.**

## 2.4 presentation 계층

- Controller 는 **얇게**: 입력 검증, DTO 변환, Application 호출만.
- **Request/Response DTO 를 반드시 둔다.** JpaEntity 를 그대로 응답하지 않는다.
  DB 컬럼 추가가 곧 API 변경이 되고, 노출하면 안 되는 필드가 새어 나간다.
- 형식 검증(`@Valid`, `@NotBlank`, `@Min`)은 **Request DTO 에만**.
  비즈니스 규칙 검증은 Entity/Application 이 한다.
- 예외는 `common/presentation/GlobalExceptionHandler` 가 공통 처리한다. **핸들러를 새로 만들지 않는다.**
  `common/exception/BusinessException` 을 상속하고 `ErrorCode` 를 넘기면 자동 매핑된다
  (`api-conventions` 5.3 절).

## 2.5 Redis

- `common` 또는 사용처 옆에 두고, 직렬화 포맷·TTL·재시도는 그 클래스 안에서 처리한다.
- **Redis 장애가 기능 실패가 되지 않게 한다.** 캐시는 원본이 아니라 사본이므로,
  접근 실패 시 원본 조회로 폴백한다. 캐시 미스가 정상 경로여야 한다.
- 캐시 키에 버전을 넣는다 (`place:v1:view-count:{id}`). 포맷이 바뀌면 v2 로 올린다.

---

## 3. 설계 판단 가이드

**새 클래스를 만들까?** — 그 클래스가 지키는 규칙을 한 문장으로 못 쓰면 만들지 않는다.

**필드를 묶는 타입(`Money`, `Address`)을 만들까?** — 검증 로직이 **두 군데 이상**에서
반복될 때만. 한 곳에서만 쓰는 값은 필드 그대로 둔다.

**ID 를 타입으로 감쌀까?** — 감싸지 않는다. `Long` 을 쓴다.
인자 순서 실수가 걱정되면 메서드 시그니처를 줄이거나 Request 레코드로 받는다.

**인터페이스를 뽑을까?** — 구현이 둘 이상이거나, 테스트에서 반드시 대체해야 할 때만.

**이벤트를 쓸까?** — 커밋 이후 후처리가 실패해도 본 작업을 롤백하면 안 되는 경우에만
`@TransactionalEventListener(AFTER_COMMIT)` 를 쓴다. 그 외에는 직접 호출한다.

---

## 4. 자가 검증 체크리스트

### persistence
- [ ] JpaEntity 에 `@Setter` / `@Data` 가 없는가?
- [ ] 상태 변경이 의미 있는 메서드로 이뤄지는가?
- [ ] 불변식을 메서드 진입 시 검증하고, DB 제약으로도 막는가?
- [ ] JPA 연관관계 애너테이션이 없는가? (외래 ID 컬럼 + 인덱스)
- [ ] 경합이 있는 갱신을 원자적 UPDATE 로 했는가?
- [ ] 행을 둘 이상 잠근다면 ID 오름차순으로 잠그는가?

### application
- [ ] `@Transactional` 경계 안에 Redis/HTTP 호출이 없는가?
- [ ] 규칙 판단이 Application 이 아니라 Entity 에 있는가?
- [ ] presentation DTO 를 그대로 받지 않는가?

### presentation
- [ ] Controller 가 Repository 를 직접 주입받지 않는가?
- [ ] Request DTO 에 검증 애너테이션과 `@Valid` 가 있는가?
- [ ] JpaEntity 를 Response 로 직접 반환하지 않는가?

---

## 5. 피해야 할 것

| 피할 것 | 대신 |
|---|---|
| 규칙 없는 Entity + 규칙을 다 가진 Service | 규칙을 Entity 메서드로 내린다 |
| 도메인 객체 / JpaEntity 이중 모델 + Mapper | JpaEntity 하나로 간다 |
| Repository 인터페이스 + 단일 구현체 | `JpaRepository` 를 직접 쓴다 |
| Controller 가 Repository 직접 호출 | Application 을 거친다 |
| JpaEntity 를 그대로 응답 | Response DTO 로 변환 |
| 트랜잭션 안의 Redis·외부 HTTP | 트랜잭션 밖 또는 AFTER_COMMIT |
| `LocalDateTime.now()` 직접 호출 (시간이 규칙일 때) | `Clock` 주입 → `Instant.now(clock)` |
| `common` 에 `XxxUtils` 쌓기 | 그 로직이 속할 Entity 를 먼저 찾는다 |
| 안 쓰는 확장 포인트 선반영 | 필요해질 때 만든다 |
| 입력 순서대로 여러 행 잠그기 | ID 오름차순으로 잠근다 (2.2절) |

---

## 6. 참고 문서

- 코드 예시 → `references/examples.md`

## 다음 단계

구현이 끝나면 → `.claude/skills/testing-junit/SKILL.md`

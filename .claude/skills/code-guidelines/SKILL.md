---
name: code-guidelines
description: k-place 에서 기능 구현, 버그 수정, 리팩토링 등 코드를 건드리는 모든 작업에 사용한다. 1부는 계획 단계(요구사항 재진술, 도메인 분석, 영향 범위, TodoList, 승인), 2부는 DDD 계층 설계(Domain/Application/Infrastructure/Presentation, 도메인-JpaEntity 분리, Aggregate/VO, Repository 인터페이스 분리, 트랜잭션 경계, 도메인 이벤트)를 다룬다. "구현해줘", "추가해줘", "고쳐줘", "리팩토링", "도메인 모델링", "Service", "Entity", "Repository" 같은 언급이 있으면 코드를 한 줄이라도 쓰기 전에 반드시 사용한다.
---

# Code Guidelines - 계획과 DDD 설계

k-place 는 **단일 모놀리식 애플리케이션**이다. 아래 규칙은 하나의 애플리케이션 안에서
도메인 경계를 어떻게 긋고 계층을 어떻게 나눌지를 다룬다.

**핵심 원칙 두 가지**
1. 계획 없이 코드를 쓰지 않는다.
2. 도메인은 프레임워크로부터 자유로워야 한다.

---

# 1부. 계획 (코드 작성 전)

## 1.1 목표

- 요구사항의 **모호함 제거**
- **올바른 추상화 수준** 찾기
- **영향 범위**(변경 파일, 스키마 마이그레이션, API 호환성) 파악
- **작업 순서** 확정 후 사용자 **승인**

## 1.2 Step 1 — 요구사항 재진술

사용자의 요청을 내 말로 다시 정리해 보여준다. 해석이 들어간 부분은 명시한다.

```
[요구사항 재진술]
요청: "장소 리뷰 기능을 추가해줘"

내 이해:
- Place 에 대해 회원이 리뷰(Review)를 작성/수정/삭제
- 한 회원은 한 장소에 리뷰 1개 (가정)
- 별점은 1~5 정수 (가정)
- 폐업(CLOSED) 장소에는 리뷰 작성 불가 (가정)

확인 필요:
1. 리뷰 이미지 첨부가 필요한가?
2. 리뷰 수정 가능 기간 제한이 있는가?
```

**불명확한 점이 2개 이상이면 여기서 멈추고 질문한다.** 추측으로 진행하지 않는다.

## 1.3 Step 2 — 도메인 분석

| 질문 | 답 |
|---|---|
| 어떤 Aggregate 가 관련되는가? | |
| 새로운 도메인 개념(VO)이 필요한가? | |
| 상태 전이가 있는가? | |
| 불변식(invariant)은 무엇인가? | |
| 도메인 이벤트가 필요한가? | |
| 기존 Aggregate 수정인가, 신규인가? | |

## 1.4 Step 3 — 영향 범위

변경될 파일을 계층별로 미리 나열한다.

```
[영향 범위]
domain/         Review.java(신규), Rating.java(VO 신규), ReviewRepository.java(신규)
                Place.java(리뷰 가능 여부 검증 추가)
application/    WriteReviewService.java, WriteReviewCommand.java
infrastructure/ ReviewJpaEntity.java, ReviewMapper.java, ReviewRepositoryImpl.java
                V2__create_reviews.sql (스키마 마이그레이션)
presentation/   ReviewController.java, WriteReviewRequest.java, ReviewResponse.java
```

## 1.5 Step 4 — API 설계 (해당 시)

`.claude/skills/api-conventions/SKILL.md` 를 열어 규칙을 확인하고,
요청/응답/에러 케이스까지 적는다.

## 1.6 Step 5 — TodoList

**도메인부터 바깥으로** 진행한다.

```
[ ] 1. domain: Rating VO + 단위 테스트
[ ] 2. domain: Review Aggregate + 불변식 검증 + 단위 테스트
[ ] 3. domain: ReviewRepository 인터페이스
[ ] 4. application: WriteReviewCommand / WriteReviewService + 단위 테스트
[ ] 5. infrastructure: ReviewJpaEntity, Mapper, RepositoryImpl
[ ] 6. infrastructure: 스키마 마이그레이션 + @DataJpaTest
[ ] 7. presentation: Controller, Request/Response DTO + @WebMvcTest
[ ] 8. 통합 테스트 (필요 시)
[ ] 9. 커밋 및 PR
```

## 1.7 Step 6 — 승인

Step 1~5 를 **한 번에** 보여주고 명시적 확인을 받는다. 승인 없이 구현으로 넘어가지 않는다.

## 1.8 계획 단계 안티 패턴

1. "일단 만들고 나서 물어보자" — 되돌리기 어렵다.
2. **Controller 부터 작성** — 도메인이 흔들리면 전부 다시 짠다.
3. "아마 이럴 것이다" 추측 — 요구사항을 임의 확장하는 가장 흔한 실수.
4. TodoList 없이 진행 — 범위가 팽창한다.
5. **DB 스키마부터 설계** — 도메인이 DB 에 종속되는 전형적 안티 패턴.

---

# 2부. DDD 계층 설계 (구현)

## 2.1 계층과 의존성 방향

```
presentation   (Controller, Request/Response DTO)
     ↓
application    (Application Service, Command/Query)
     ↓
domain         (Aggregate, Entity, VO, Domain Service, Event, Repository 인터페이스)
     ↑
infrastructure (XxxJpaEntity, Repository 구현, Mapper, Redis/외부 연동)
```

- **domain 은 어떤 계층에도 의존하지 않는다.**
- application 은 domain 에만 의존한다.
- infrastructure 는 domain 의 인터페이스를 구현한다 (의존성 역전).
- presentation 은 application 에만 의존한다.

**금지**
- ❌ Controller → Repository 직접 호출
- ❌ domain → JPA / Spring / Jackson / Redis
- ❌ application → presentation DTO
- ❌ JpaEntity 와 도메인 객체 혼용

### 도메인 패키지 경계

`com.k_place.<context>` 아래에 4계층을 둔다(예: `com.k_place.place.domain`).
다른 도메인 패키지의 **infrastructure / presentation 을 import 하지 않는다.**
도메인 간 연동이 필요하면 상대 도메인의 **application 서비스**를 호출하거나 이벤트를 쓴다.
모놀리식이므로 물리적 분리는 하지 않지만, 이 선을 지켜야 나중에 나눌 수 있다.

## 2.2 domain 계층

### Aggregate / Entity
- **JPA/Spring/Jackson 애너테이션 절대 금지** (`@Entity`, `@Component`, `@JsonProperty`)
- **public setter 금지** — 상태 변경은 의미 있는 메서드로 (`close()`, `approve()`)
- 생성은 **static 팩토리 메서드** (`Review.write(...)`, `Place.register(...)`)
- 불변식은 생성자/메서드에서 **즉시 검증**
- ID 는 **VO 로 래핑** (`PlaceId`, `ReviewId`) — 원시 `Long`/`String` 금지
- Collection 반환 시 방어적 복사 또는 `Collections.unmodifiableList`
- `equals`/`hashCode`: Aggregate Root 는 ID 기반, VO 는 값 기반

### Value Object
- `record` 또는 불변 클래스
- 생성 시점에 유효성 검증
- 항상 함께 다니는 필드는 VO 로 묶는다 (`Address(roadAddress, detail, zipCode)`)

### Repository
- **인터페이스만 domain 에** 둔다. 구현은 infrastructure.
- 반환 타입은 **도메인 객체**. JpaEntity 를 노출하지 않는다.

### Domain Service
- 하나의 Aggregate 안에서 끝나는 로직은 **Aggregate 내부**에 둔다.
- 여러 Aggregate 에 걸친 로직만 Domain Service 로 분리한다.

### Domain Event
- 이름은 **과거형**: `ReviewWritten` (O) / `WriteReview` (X)
- Aggregate 내부에서 수집 → Application Service 가 `save()` 후 발행
- `@TransactionalEventListener(AFTER_COMMIT)` 로 후처리
- 데이터는 구독자가 필요한 최소한만 담는다

## 2.3 application 계층

- `@Service`, `@Transactional` 은 **여기에만** 붙는다.
- 입력은 **Command / Query 객체**로 받는다 (원시 타입 나열 금지).
- 시간은 `Clock` 을 주입받아 `Instant.now(clock)` (테스트 가능성).
- Application Service 는 **얇게**: 도메인 조립 + Repository 호출. 규칙은 도메인에 위임.
- **다른 Application Service 를 직접 호출하지 않는다** (Domain Service 또는 이벤트로 해결).
  단, 다른 도메인 패키지와의 연동은 예외적으로 허용하되 의존 방향이 순환하지 않아야 한다.
- 외부 시스템 호출이 필요하면 **domain 에 인터페이스**를 두고 infrastructure 가 구현한다.

### 트랜잭션 경계

**트랜잭션은 커밋·롤백이 의미 있는 작업만 감싼다.**

- ❌ 트랜잭션 **안**에 두지 않는다 — Redis 접근, 외부 HTTP 호출, 무거운 CPU 작업(암호 해싱 등).
  롤백돼도 되돌아가지 않으므로 넣을 이득이 없고, 커넥션 점유 시간만 늘린다.
- ✅ 트랜잭션 **안**에 둔다 — 여러 조회를 일관된 스냅샷으로 읽어야 할 때 그 조회들만.
- 조회 전용은 `@Transactional(readOnly = true)` — 단, **그 경계 안에 외부 I/O 가 없는지** 확인.
- 같은 클래스에서 `this.transactionalMethod()` 를 호출하면 프록시를 안 거쳐 트랜잭션이 아예 안 걸린다
  (self-invocation). 메서드 분리로 해결되지 않으므로 `TransactionTemplate` 을 쓴다.

**왜 중요한가**: 처리량 ≈ 커넥션 풀 크기 ÷ 점유 시간. 트랜잭션 안의 Redis·HTTP 호출은
내가 통제할 수 없는 시간을 점유 시간에 더한다. 풀은 애플리케이션 공유 자원이므로,
한 엔드포인트가 풀을 쥐면 **무관한 다른 엔드포인트까지 함께 죽는다.**

## 2.4 infrastructure 계층

### JPA Entity
- 클래스명은 **`XxxJpaEntity`** 로 끝낸다 (도메인과 이름 충돌 방지).
- **비즈니스 메서드 금지** — DB 매핑 전용.
- `protected` no-arg 생성자 (JPA 요구).
- DB 구조에 최적화된 형태로 설계 가능 (도메인과 달라도 된다).
- **JPA 연관관계 애너테이션 금지** (`@ManyToOne`, `@OneToMany`, `@OneToOne`, `@ManyToMany`).
  외래 ID 컬럼만 보유하고, 부모 존재 검증은 application/domain 이 `findById` 로 명시 처리한다.
  대신 조회 조건 컬럼에 인덱스를 명시한다.

```java
// ❌ 금지
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "place_id")
private PlaceJpaEntity place;

// ✅ 권장
@Column(name = "place_id", nullable = false)
private Long placeId;
```

**이유**: 연관관계는 N+1, 지연 로딩 예외, 의도치 않은 cascade 를 만들고, 애그리거트 경계를
JPA 가 대신 정하게 만든다. 필요한 조인은 조회 전용 쿼리에서 명시적으로 작성한다.

### Mapper
- `도메인 ↔ JpaEntity` **양방향** 변환을 제공한다.
- DB 에서 복원할 때는 `Xxx.reconstitute(...)` 팩토리를 쓴다 (생성 시 불변식 검증을 건너뛰는 용도).

### Repository 구현
- domain 의 Repository 인터페이스를 구현한다.
- 내부적으로 Spring Data JPA 인터페이스를 쓰되 **외부로 노출하지 않는다.**

### Redis / 외부 연동
- domain 에 정의한 인터페이스를 infrastructure 에서 구현한다.
- 직렬화 포맷, TTL, 재시도, 장애 시 폴백은 **이 계층에서** 처리한다.
- **Redis 장애가 기능 실패가 되지 않게** 한다. 캐시 조회 실패 시 원본 조회로 폴백.

## 2.5 presentation 계층

- Controller 는 **얇게**: 입력 검증, DTO 변환, Application 호출만.
- **Request/Response DTO 와 Application Command 를 분리**한다.
- 입력 검증(`@Valid`, `@NotBlank`)은 **presentation DTO 에만**.
- 도메인 객체를 Response 로 **직접 반환 금지**.
- 예외는 `@RestControllerAdvice` 에서 공통 처리 (포맷은 `api-conventions` 참조).

---

## 3. 설계 판단 가이드

**VO 인가 Entity 인가?** — 정체성이 중요하면 Entity, 값 자체가 의미면 VO. 애매하면 VO 로 시작.

**Aggregate 경계는?** — 외부에서 참조 가능한 단위가 Aggregate Root. 한 트랜잭션 = 한 AR 수정.

**로직을 어디에 두나?**
- 하나의 Aggregate 안에서 완결 → Aggregate 메서드
- 여러 Aggregate 에 걸침 → Domain Service
- 트랜잭션/외부 호출 필요 → Application Service

**새 도메인 패키지를 만들 것인가?** — 용어(Ubiquitous Language)가 다르면 나눈다.
같은 단어가 두 맥락에서 다른 뜻이면(예: 판매자의 "장소" vs 사용자의 "장소") 별도 패키지.

---

## 4. 자가 검증 체크리스트

### domain
- [ ] JPA/Spring 애너테이션이 없는가?
- [ ] public setter 가 없는가?
- [ ] 생성자에서 불변식을 검증하는가?
- [ ] ID 가 VO 로 래핑되어 있는가?
- [ ] 상태 변경 메서드 이름이 의미를 드러내는가?
- [ ] import 가 `java.*` 와 프로젝트 내부 패키지뿐인가?

### application
- [ ] `@Transactional` 경계 안에 Redis/HTTP 호출이 없는가?
- [ ] Command/Query 객체로 입력받는가?
- [ ] `Clock` 주입으로 시간을 처리하는가?
- [ ] 도메인 객체를 presentation 에 그대로 넘기지 않는가?

### infrastructure
- [ ] JpaEntity 가 `XxxJpaEntity` 네이밍인가?
- [ ] JPA 연관관계 애너테이션이 없는가?
- [ ] Mapper 가 양방향 변환을 제공하는가?
- [ ] Spring Data JPA 인터페이스가 domain 에 노출되지 않는가?
- [ ] Redis 장애 시 폴백 경로가 있는가?

### presentation
- [ ] Controller 가 Repository 를 직접 주입받지 않는가?
- [ ] Request DTO 에 검증 애너테이션과 `@Valid` 가 있는가?
- [ ] 도메인 객체를 Response 로 직접 반환하지 않는가?

---

## 5. 참고 문서

- 코드 예시 → `references/examples.md`
- 안티 패턴 상세 → `references/anti-patterns.md`

## 다음 단계

구현이 끝나면 → `.claude/skills/testing-junit/SKILL.md`

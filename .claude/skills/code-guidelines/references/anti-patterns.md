# 안티 패턴

`code-guidelines/SKILL.md` 를 어겼을 때 실제로 나타나는 형태와 교정법.
**증상 → 왜 나쁜가 → 교정** 순으로 읽는다.

---

## 1. 도메인 객체 = JPA Entity

```java
// ❌
@Entity
@Table(name = "reviews")
@Getter @Setter @NoArgsConstructor
public class Review {
    @Id @GeneratedValue Long id;
    int rating;
    String content;
}
```

**왜 나쁜가**
- `@Setter` 로 어떤 필드든 아무 때나 바뀐다. 불변식을 지킬 지점이 사라진다.
- JPA 요구(기본 생성자, 프록시, 필드 접근)가 도메인 설계를 지배한다.
- 도메인 테스트에 JPA 가 따라 들어와 느려진다.
- DB 스키마 변경이 곧 도메인 변경이 된다.

**교정** — 도메인 `Review` 와 `ReviewJpaEntity` 를 분리하고 `ReviewMapper` 로 잇는다.
"파일이 늘어난다"는 비용은 도메인 규칙이 한 곳에 모이는 이득으로 회수된다.

---

## 2. Controller 가 Repository 를 직접 호출

```java
// ❌
@RestController
public class ReviewController {
    private final ReviewJpaRepository repository;

    @GetMapping("/v1/reviews/{id}")
    public ReviewJpaEntity get(@PathVariable String id) {
        return repository.findById(id).orElseThrow();
    }
}
```

**왜 나쁜가**
- 트랜잭션 경계가 사라진다.
- DB 스키마(컬럼명, 내부 상태값)가 그대로 API 응답이 되어 하위 호환이 묶인다.
- 권한 검증·비즈니스 규칙을 넣을 자리가 없어 Controller 가 비대해진다.

**교정** — `presentation → application → domain`. Controller 는 Application Service 만 호출하고
Response DTO 로 변환해 반환한다.

---

## 3. 빈혈 도메인 (Anemic Domain Model)

```java
// ❌ 도메인은 getter/setter 만, 규칙은 전부 Service 에
@Service
public class ReviewService {
    public void delete(String reviewId, String requesterId) {
        Review review = repository.findById(reviewId).orElseThrow();
        if (!review.getAuthorId().equals(requesterId)) throw new AccessDeniedException();
        if (review.getStatus() == DELETED) throw new IllegalStateException();
        review.setStatus(DELETED);
        review.setUpdatedAt(LocalDateTime.now());
        repository.save(review);
    }
}
```

**왜 나쁜가** — 같은 규칙이 다른 Service 에도 복사된다. 한 곳만 고치면 나머지가 남아 버그가 된다.
도메인 객체는 "규칙 없이 저장만 되는 구조체" 가 되어 존재 의미를 잃는다.

**교정** — `review.delete(requester, now)` 로 규칙을 Aggregate 안으로 옮긴다.
Application Service 는 조회 → 도메인 메서드 호출 → 저장만 한다.

---

## 4. 트랜잭션 안의 외부 I/O

```java
// ❌
@Transactional
public void write(WriteReviewCommand command) {
    Review review = Review.write(...);
    reviewRepository.save(review);
    imageUploader.upload(command.images());     // 외부 HTTP
    cache.evict(command.placeId());             // Redis
    notifier.send(...);                         // 외부 HTTP
}
```

**왜 나쁜가**
- DB 커넥션을 외부 호출이 끝날 때까지 붙잡는다. 처리량 ≈ 풀 크기 ÷ 점유 시간.
- 커넥션 풀은 애플리케이션 공유 자원이라, 이 엔드포인트 하나가 **무관한 API 까지 죽인다.**
- 롤백해도 업로드된 이미지와 발송된 알림은 되돌아가지 않는다. 넣을 이득 자체가 없다.

**교정** — 트랜잭션은 DB 작업만 감싼다. 나머지는 `@TransactionalEventListener(AFTER_COMMIT)`
또는 트랜잭션 밖으로 뺀다. 커밋 여부가 외부 호출 결과에 달렸다면 호출을 앞으로 당기고
멱등·보상 설계를 함께 만든다.

---

## 5. self-invocation 으로 트랜잭션이 안 걸림

```java
// ❌ 같은 클래스 안에서 호출 → 프록시를 안 거쳐 @Transactional 이 무시된다
public void process(Command c) {
    doInTransaction(c);
}

@Transactional
public void doInTransaction(Command c) { ... }
```

**왜 나쁜가** — 컴파일도 되고 테스트도 통과하는데 **운영에서만** 트랜잭션이 없다.
발견 시점이 늦고 데이터가 이미 깨져 있다.

**교정** — `TransactionTemplate` 을 주입해 명시적으로 감싸거나, 별도 빈으로 분리한다.

---

## 6. JPA 연관관계 남용

```java
// ❌
@OneToMany(mappedBy = "place", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
private List<ReviewJpaEntity> reviews = new ArrayList<>();
```

**왜 나쁜가**
- 목록 조회에서 N+1 이 조용히 발생한다.
- 트랜잭션 밖 접근 시 `LazyInitializationException`.
- `cascade = ALL` 로 의도치 않은 대량 삭제가 일어난다.
- 애그리거트 경계를 내가 아니라 JPA 매핑이 정하게 된다.

**교정** — 외래 ID 컬럼(`placeId`)만 보유하고 조회 조건 컬럼에 인덱스를 명시한다.
필요한 조인은 조회 전용 쿼리에서 명시적으로 작성한다.

---

## 7. 원시 타입 강박 (Primitive Obsession)

```java
// ❌
public void transfer(String from, String to, long amount) { ... }
transfer(placeId, memberId, 15000);   // 인자 순서가 바뀌어도 컴파일된다
```

**왜 나쁜가** — 타입이 같아 순서를 바꿔도 컴파일러가 못 잡는다. 검증 로직이 호출부마다 흩어진다.

**교정** — `PlaceId`, `MemberId`, `Money` 같은 VO 로 래핑한다. 생성 시점에 한 번 검증하면
이후 모든 코드가 "이미 유효한 값" 을 다룬다.

---

## 8. `LocalDateTime.now()` 직접 호출

```java
// ❌
review.setUpdatedAt(LocalDateTime.now());
```

**왜 나쁜가** — 테스트가 실행 시각에 의존해 결정론을 잃는다. "만료 24시간 후" 같은 경계 테스트가 불가능해진다.

**교정** — `Clock` 을 주입해 `Instant.now(clock)`. 테스트는 `Clock.fixed(...)` 로 시간을 고정한다.

---

## 9. Domain 이 Spring/Redis 를 import

```java
// ❌
package com.k_place.review.domain;

import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
```

**왜 나쁜가** — 도메인 테스트에 Spring 컨텍스트가 필요해지고, 인프라 교체가 도메인 변경이 된다.

**교정** — domain 에는 인터페이스만(`PlaceViewCounter`), 구현은 infrastructure 에
(`RedisPlaceViewCounter`). 의존성 역전.

**빠른 점검**: domain 패키지의 import 문이 `java.*` 와 `com.k_place.*` 뿐인지 본다.

---

## 10. Redis 장애가 곧 기능 장애

```java
// ❌ 캐시가 죽으면 조회 API 전체가 500
public PlaceView get(PlaceId id) {
    String cached = redis.opsForValue().get(key(id));   // 예외 시 그대로 터진다
    ...
}
```

**왜 나쁜가** — 캐시는 원본이 아니라 **사본**이다. 사본이 없다고 기능이 멈추면 안 된다.

**교정** — 캐시 접근을 try/catch 로 감싸고 원본 조회로 폴백한다. 캐시 미스가 정상 경로여야 한다.

---

## 11. 도메인 객체를 그대로 응답

```java
// ❌
@GetMapping("/v1/reviews/{id}")
public Review get(@PathVariable String id) { return service.get(id); }
```

**왜 나쁜가** — 내부 필드 추가가 곧 API 변경이 된다. 노출하면 안 되는 필드가 조용히 새어 나간다.
직렬화를 위해 도메인에 `@JsonIgnore` 를 붙이기 시작하면 도메인이 웹 스펙에 오염된다.

**교정** — `ReviewResponse.from(...)` 으로 변환해 반환한다.

---

## 12. common 패키지가 쓰레기통이 됨

```
common/
  utils/ReviewUtils.java        ← 리뷰 규칙이 왜 여기에?
  service/CommonService.java    ← 무엇을 하는 클래스인가?
```

**왜 나쁜가** — 소유 도메인이 불명확해져 아무도 고치지 못하는 코드가 된다.
`common` 이 커질수록 모든 도메인이 서로 결합된다.

**교정** — `common` 에는 도메인이 아닌 것(전역 설정, 공통 예외 처리, 응답 포맷)만 둔다.
`XxxUtils` 를 만들고 싶어지면, 그 로직이 속할 도메인 객체가 없는지 먼저 의심한다.

---

## 13. 다른 도메인의 infrastructure 를 import

```java
// ❌ review 가 place 의 JpaEntity 를 직접 쓴다
import com.k_place.place.infrastructure.PlaceJpaEntity;
```

**왜 나쁜가** — 모놀리식이라 컴파일은 되지만, 두 도메인이 DB 매핑 수준에서 붙는다.
place 의 컬럼 변경이 review 를 깨뜨리고, 나중에 어느 쪽도 분리할 수 없게 된다.

**교정** — 상대 도메인의 **application 서비스**나 domain 에 정의한 조회 인터페이스를 거친다.
`presentation`/`infrastructure` 는 도메인 패키지 밖으로 노출하지 않는다.

---

## 14. 계획 없이 바로 구현

**증상** — 요청을 받자마자 Controller 부터 만든다. 중간에 요구사항이 바뀌어 전부 다시 짠다.

**왜 나쁜가** — 되돌리는 비용이 계획하는 비용보다 항상 크다. 특히 도메인 모델이 틀리면
그 위에 쌓은 모든 계층을 다시 만들어야 한다.

**교정** — `code-guidelines/SKILL.md` 1부의 6단계를 거친다.
불명확한 점이 2개 이상이면 멈추고 질문한다.

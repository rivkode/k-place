# 계층별 코드 예시

`code-guidelines/SKILL.md` 의 규칙을 실제 코드로 옮긴 예시. 도메인은 `review` 를 가정한다.

---

## 1. domain

### 1.1 Value Object

```java
package com.k_place.review.domain;

public record Rating(int value) {

    public Rating {
        if (value < 1 || value > 5) {
            throw new IllegalArgumentException("별점은 1~5 사이여야 합니다: " + value);
        }
    }
}
```

```java
package com.k_place.review.domain;

import java.util.Objects;

public record ReviewId(String value) {

    public ReviewId {
        Objects.requireNonNull(value, "reviewId 는 null 일 수 없습니다");
        if (value.isBlank()) {
            throw new IllegalArgumentException("reviewId 는 공백일 수 없습니다");
        }
    }

    public static ReviewId of(String value) {
        return new ReviewId(value);
    }
}
```

### 1.2 Aggregate Root

```java
package com.k_place.review.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Review {

    private final ReviewId id;
    private final PlaceId placeId;
    private final MemberId authorId;
    private Rating rating;
    private String content;
    private ReviewStatus status;
    private final Instant writtenAt;
    private Instant updatedAt;

    private final List<Object> events = new ArrayList<>();

    private Review(ReviewId id, PlaceId placeId, MemberId authorId,
                   Rating rating, String content, ReviewStatus status,
                   Instant writtenAt, Instant updatedAt) {
        this.id = id;
        this.placeId = placeId;
        this.authorId = authorId;
        this.rating = rating;
        this.content = content;
        this.status = status;
        this.writtenAt = writtenAt;
        this.updatedAt = updatedAt;
    }

    /** 신규 작성. 불변식을 모두 검증한다. */
    public static Review write(ReviewId id, PlaceId placeId, MemberId authorId,
                               Rating rating, String content, Instant now) {
        validateContent(content);
        Review review = new Review(id, placeId, authorId, rating, content,
                ReviewStatus.PUBLISHED, now, now);
        review.events.add(new ReviewWritten(id, placeId, rating, now));
        return review;
    }

    /** DB 복원 전용. 이미 저장된 상태이므로 불변식 재검증을 하지 않는다. */
    public static Review reconstitute(ReviewId id, PlaceId placeId, MemberId authorId,
                                      Rating rating, String content, ReviewStatus status,
                                      Instant writtenAt, Instant updatedAt) {
        return new Review(id, placeId, authorId, rating, content, status, writtenAt, updatedAt);
    }

    public void edit(Rating newRating, String newContent, MemberId editor, Instant now) {
        if (!authorId.equals(editor)) {
            throw new ReviewNotEditableException("작성자만 리뷰를 수정할 수 있습니다");
        }
        if (status == ReviewStatus.DELETED) {
            throw new ReviewNotEditableException("삭제된 리뷰는 수정할 수 없습니다");
        }
        validateContent(newContent);
        this.rating = newRating;
        this.content = newContent;
        this.updatedAt = now;
    }

    public void delete(MemberId requester, Instant now) {
        if (!authorId.equals(requester)) {
            throw new ReviewNotEditableException("작성자만 리뷰를 삭제할 수 있습니다");
        }
        if (status == ReviewStatus.DELETED) {
            return; // 멱등
        }
        this.status = ReviewStatus.DELETED;
        this.updatedAt = now;
        this.events.add(new ReviewDeleted(id, placeId, now));
    }

    private static void validateContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("리뷰 내용은 비어 있을 수 없습니다");
        }
        if (content.length() > 1000) {
            throw new IllegalArgumentException("리뷰 내용은 1000자를 넘을 수 없습니다");
        }
    }

    public List<Object> pullEvents() {
        List<Object> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    public ReviewId id() { return id; }
    public PlaceId placeId() { return placeId; }
    public MemberId authorId() { return authorId; }
    public Rating rating() { return rating; }
    public String content() { return content; }
    public ReviewStatus status() { return status; }
    public Instant writtenAt() { return writtenAt; }
    public Instant updatedAt() { return updatedAt; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Review other)) return false;
        return id.equals(other.id);   // Aggregate Root 는 ID 기반
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
```

### 1.3 Repository 인터페이스 (domain 에 위치)

```java
package com.k_place.review.domain;

import java.util.Optional;

public interface ReviewRepository {

    Review save(Review review);

    Optional<Review> findById(ReviewId id);

    boolean existsByPlaceIdAndAuthorId(PlaceId placeId, MemberId authorId);
}
```

### 1.4 Domain Event

```java
package com.k_place.review.domain;

import java.time.Instant;

public record ReviewWritten(ReviewId reviewId, PlaceId placeId, Rating rating, Instant occurredAt) {}
```

---

## 2. application

### 2.1 Command

```java
package com.k_place.review.application;

public record WriteReviewCommand(String placeId, String authorId, int rating, String content) {}
```

### 2.2 Application Service

```java
package com.k_place.review.application;

import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WriteReviewService {

    private final ReviewRepository reviewRepository;
    private final PlaceReader placeReader;          // domain 인터페이스
    private final ReviewIdGenerator idGenerator;    // domain 인터페이스
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public WriteReviewService(ReviewRepository reviewRepository,
                              PlaceReader placeReader,
                              ReviewIdGenerator idGenerator,
                              ApplicationEventPublisher eventPublisher,
                              Clock clock) {
        this.reviewRepository = reviewRepository;
        this.placeReader = placeReader;
        this.idGenerator = idGenerator;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public ReviewId write(WriteReviewCommand command) {
        PlaceId placeId = PlaceId.of(command.placeId());
        MemberId authorId = MemberId.of(command.authorId());

        Place place = placeReader.getById(placeId);
        place.ensureReviewable();                    // 규칙은 도메인이 판단

        if (reviewRepository.existsByPlaceIdAndAuthorId(placeId, authorId)) {
            throw new DuplicateReviewException(placeId, authorId);
        }

        Instant now = Instant.now(clock);
        Review review = Review.write(
                idGenerator.next(), placeId, authorId,
                new Rating(command.rating()), command.content(), now);

        Review saved = reviewRepository.save(review);
        saved.pullEvents().forEach(eventPublisher::publishEvent);
        return saved.id();
    }
}
```

### 2.3 트랜잭션 밖으로 외부 I/O 빼기

```java
// ❌ Redis 왕복 동안 DB 커넥션을 점유한다
@Transactional(readOnly = true)
public PlaceDetailView detail(PlaceId id) {
    Place place = placeRepository.findById(id).orElseThrow();
    long viewCount = viewCounter.get(id);           // Redis!
    return PlaceDetailView.of(place, viewCount);
}

// ✅ DB 만 트랜잭션, Redis 는 밖에서
public PlaceDetailView detail(PlaceId id) {
    Place place = transactionTemplate.execute(s -> placeRepository.findById(id).orElseThrow());
    long viewCount = viewCounter.get(id);           // 트랜잭션 밖
    return PlaceDetailView.of(place, viewCount);
}
```

---

## 3. infrastructure

### 3.1 JpaEntity

```java
package com.k_place.review.infrastructure;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reviews", indexes = {
        @Index(name = "idx_reviews_place_id", columnList = "place_id"),
        @Index(name = "idx_reviews_author_id", columnList = "author_id")
})
public class ReviewJpaEntity {

    @Id
    @Column(name = "review_id", length = 36, nullable = false)
    private String reviewId;

    // 연관관계 애너테이션 금지 — 외래 ID 컬럼만 보유
    @Column(name = "place_id", length = 36, nullable = false)
    private String placeId;

    @Column(name = "author_id", length = 36, nullable = false)
    private String authorId;

    @Column(name = "rating", nullable = false)
    private int rating;

    @Column(name = "content", length = 1000, nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "written_at", nullable = false)
    private Instant writtenAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReviewJpaEntity() {}   // JPA 요구

    // 생성자 / getter 만. 비즈니스 메서드는 두지 않는다.
}
```

### 3.2 Mapper

```java
package com.k_place.review.infrastructure;

import org.springframework.stereotype.Component;

@Component
public class ReviewMapper {

    public ReviewJpaEntity toEntity(Review review) {
        return new ReviewJpaEntity(
                review.id().value(),
                review.placeId().value(),
                review.authorId().value(),
                review.rating().value(),
                review.content(),
                review.status().name(),
                review.writtenAt(),
                review.updatedAt());
    }

    public Review toDomain(ReviewJpaEntity entity) {
        return Review.reconstitute(
                ReviewId.of(entity.getReviewId()),
                PlaceId.of(entity.getPlaceId()),
                MemberId.of(entity.getAuthorId()),
                new Rating(entity.getRating()),
                entity.getContent(),
                ReviewStatus.valueOf(entity.getStatus()),
                entity.getWrittenAt(),
                entity.getUpdatedAt());
    }
}
```

### 3.3 Repository 구현

```java
package com.k_place.review.infrastructure;

import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewRepositoryImpl implements ReviewRepository {

    private final ReviewJpaRepository jpaRepository;   // 외부로 노출하지 않는다
    private final ReviewMapper mapper;

    public ReviewRepositoryImpl(ReviewJpaRepository jpaRepository, ReviewMapper mapper) {
        this.jpaRepository = jpaRepository;
        this.mapper = mapper;
    }

    @Override
    public Review save(Review review) {
        return mapper.toDomain(jpaRepository.save(mapper.toEntity(review)));
    }

    @Override
    public Optional<Review> findById(ReviewId id) {
        return jpaRepository.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public boolean existsByPlaceIdAndAuthorId(PlaceId placeId, MemberId authorId) {
        return jpaRepository.existsByPlaceIdAndAuthorId(placeId.value(), authorId.value());
    }
}
```

### 3.4 Redis 어댑터 — 장애 시 폴백

```java
package com.k_place.place.infrastructure;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisPlaceViewCounter implements PlaceViewCounter {   // domain 인터페이스 구현

    private static final Logger log = LoggerFactory.getLogger(RedisPlaceViewCounter.class);

    private final StringRedisTemplate redis;

    public RedisPlaceViewCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long get(PlaceId placeId) {
        try {
            String value = redis.opsForValue().get(key(placeId));
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException e) {
            log.warn("조회수 캐시 조회 실패, 0 으로 폴백: placeId={}", placeId.value(), e);
            return 0L;                  // 캐시 장애가 기능 실패가 되지 않게 한다
        }
    }

    private String key(PlaceId placeId) {
        return "place:v1:view-count:" + placeId.value();   // 키에 버전 포함
    }
}
```

---

## 4. presentation

### 4.1 Request / Response DTO

```java
package com.k_place.review.presentation;

import jakarta.validation.constraints.*;

public record WriteReviewRequest(
        @Min(1) @Max(5) int rating,
        @NotBlank @Size(max = 1000) String content) {

    public WriteReviewCommand toCommand(String placeId, String authorId) {
        return new WriteReviewCommand(placeId, authorId, rating, content);
    }
}
```

```java
package com.k_place.review.presentation;

import java.time.Instant;

public record ReviewResponse(
        String reviewId, String placeId, String authorId,
        int rating, String content, Instant writtenAt) {

    public static ReviewResponse from(ReviewView view) {
        return new ReviewResponse(
                view.reviewId(), view.placeId(), view.authorId(),
                view.rating(), view.content(), view.writtenAt());
    }
}
```

### 4.2 Controller

```java
package com.k_place.review.presentation;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/places/{placeId}/reviews")     // kebab-case + 버전
public class ReviewController {

    private final WriteReviewService writeReviewService;

    public ReviewController(WriteReviewService writeReviewService) {
        this.writeReviewService = writeReviewService;
    }

    @PostMapping
    public ResponseEntity<Void> write(@PathVariable String placeId,
                                      @AuthenticationPrincipal String authorId,
                                      @Valid @RequestBody WriteReviewRequest request) {
        ReviewId reviewId = writeReviewService.write(request.toCommand(placeId, authorId));
        return ResponseEntity
                .created(URI.create("/v1/reviews/" + reviewId.value()))
                .build();
    }
}
```

### 4.3 예외 처리 — 도메인 예외만 추가한다

`GlobalExceptionHandler` 는 `common/presentation` 에 이미 있다. **핸들러를 새로 만들지 않는다.**
`BusinessException` 을 상속하면 `ErrorCode` 에 정의된 HTTP 상태로 자동 매핑된다.

```java
// common/exception/ErrorCode.java — 상수 추가 (상태 + 사용자 메시지를 여기서 결정)
REVIEW_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리뷰를 찾을 수 없습니다."),
DUPLICATE_REVIEW(HttpStatus.CONFLICT, "이미 이 장소에 리뷰를 작성했습니다."),
PLACE_NOT_REVIEWABLE(HttpStatus.UNPROCESSABLE_ENTITY, "리뷰를 작성할 수 없는 장소입니다."),
```

```java
package com.k_place.review.domain;

import com.k_place.common.exception.BusinessException;
import com.k_place.common.exception.ErrorCode;

/** 두 번째 인자는 로그용 상세다. 클라이언트에는 ErrorCode 의 메시지만 나간다. */
public class DuplicateReviewException extends BusinessException {

    public DuplicateReviewException(PlaceId placeId, MemberId authorId) {
        super(ErrorCode.DUPLICATE_REVIEW,
                "duplicate review: placeId=%s, authorId=%s".formatted(placeId.value(), authorId.value()));
    }
}
```

응답:

```http
POST /v1/places/PLC-1024/reviews  →  409 Conflict

{ "code": "DUPLICATE_REVIEW", "message": "이미 이 장소에 리뷰를 작성했습니다." }
```

**핸들러를 직접 고쳐야 하는 경우**는 프레임워크 예외를 새로 매핑할 때뿐이다
(예: `OptimisticLockingFailureException` → 409). 도메인 예외 때문에 고칠 일은 없다.

### 4.4 이벤트 리스너 (커밋 이후 후처리)

```java
package com.k_place.place.application;

import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

@Component
public class ReviewWrittenListener {

    private final PlaceRatingUpdater ratingUpdater;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ReviewWritten event) {
        ratingUpdater.recalculate(event.placeId());   // 실패해도 리뷰 작성은 롤백되지 않는다
    }
}
```

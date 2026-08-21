# 계층별 코드 예시

`code-guidelines/SKILL.md` 의 규칙을 실제 코드로 옮긴 예시. 후원(pledge) 기능을 가정한다.

**읽는 순서**: persistence(규칙) → application(조율) → presentation(노출).

---

## 1. persistence

### 1.1 JpaEntity — 규칙은 여기 있다

```java
package com.k_place.persistence;

import com.k_place.domain.exception.InvalidQuantityException;
import jakarta.persistence.*;
import lombok.Getter;

/**
 * 재고 불변식은 두 겹으로 막는다.
 * 1) 이 클래스의 decreaseSoldQuantity() — 정상 경로의 방어
 * 2) 스키마의 CHECK (total_quantity >= sold_quantity) — 어떤 경로로 들어와도 깨지지 않게
 */
@Entity
@Getter
@Table(name = "reward", indexes = @Index(name = "idx_reward_project_id", columnList = "project_id"))
public class RewardJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reward_id")
    private Long id;

    // 연관관계 애너테이션 금지 — 외래 ID 컬럼만 보유한다
    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "sold_quantity", nullable = false)
    private int soldQuantity;

    @Column(name = "price", nullable = false)
    private long price;

    protected RewardJpaEntity() {}   // JPA 요구

    public RewardJpaEntity(Long projectId, int totalQuantity, long price) {
        this.projectId = projectId;
        this.totalQuantity = totalQuantity;
        this.soldQuantity = 0;
        this.price = price;
    }

    /** 상태 변경은 의미 있는 메서드로. setter 는 두지 않는다. */
    public void decreaseSoldQuantity(int quantity) {
        if (quantity <= 0) {
            throw new InvalidQuantityException(quantity);
        }
        if (availableQuantity() < quantity) {
            throw new InvalidQuantityException(quantity);
        }
        this.soldQuantity += quantity;
    }

    public int availableQuantity() {
        return totalQuantity - soldQuantity;
    }
}
```

### 1.2 JpaRepository — 경합이 있는 갱신은 원자적 UPDATE

```java
package com.k_place.persistence;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface RewardJpaRepository extends JpaRepository<RewardJpaEntity, Long> {

    /**
     * 재고 차감. 영속성 컨텍스트로 하지 않는 이유 —
     * 조회 시점과 저장 시점 사이에 다른 트랜잭션이 끼어들면 초과 판매가 난다.
     * WHERE 절에 조건을 넣어 DB 가 한 번에 판단하게 하고, 갱신 행 수로 성공 여부를 안다.
     *
     * @return 1 이면 차감 성공, 0 이면 재고 부족
     */
    @Modifying
    @Query("""
            update RewardJpaEntity r
               set r.soldQuantity = r.soldQuantity + :quantity
             where r.id = :rewardId
               and r.totalQuantity >= r.soldQuantity + :quantity
            """)
    int decreaseQuantity(@Param("rewardId") Long rewardId, @Param("quantity") int quantity);
}
```

리턴값을 반드시 확인한다. 0 을 무시하면 재고 없이 후원이 만들어진다.

---

## 2. application

```java
package com.k_place.application;

import com.k_place.common.exception.ErrorCode;
import com.k_place.domain.ProjectStatus;
import com.k_place.domain.exception.RewardSoldOutException;
import com.k_place.persistence.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PledgeApplication {

    private final ProjectJpaRepository projectJpaRepository;      // 직접 주입해 직접 호출한다
    private final RewardJpaRepository rewardJpaRepository;
    private final PledgeJpaRepository pledgeJpaRepository;

    @Transactional
    public Long create(CreatePledgeCommand command) {
        // 1. 여러 Entity 에 걸친 판단은 Application 이 조율한다
        ProjectJpaEntity project = projectJpaRepository.findById(command.projectId())
                .orElseThrow(() -> new ProjectNotFoundException(command.projectId()));
        project.ensurePledgeable();                    // 규칙 판단 자체는 Entity 가 한다

        // 2. 경합이 있는 갱신은 원자적 UPDATE. 실패하면 예외로 롤백한다
        for (RewardOrder order : command.rewards()) {
            int updated = rewardJpaRepository.decreaseQuantity(order.rewardId(), order.quantity());
            if (updated == 0) {
                throw new RewardSoldOutException(order.rewardId());
            }
        }

        // 3. 저장
        PledgeJpaEntity pledge = PledgeJpaEntity.of(command.projectId(), command.userId(), command.rewards());
        return pledgeJpaRepository.save(pledge).getPledgeId();
    }
}
```

```java
package com.k_place.application;

import java.util.List;

/** 입력은 presentation DTO 가 아니라 Command 로 받는다. */
public record CreatePledgeCommand(Long projectId, Long userId, List<RewardOrder> rewards) {

    public record RewardOrder(Long rewardId, int quantity) {}
}
```

### 2.1 트랜잭션 밖으로 외부 I/O 빼기

```java
// ❌ Redis 왕복 동안 DB 커넥션을 점유한다
@Transactional(readOnly = true)
public ProjectDetail detail(Long projectId) {
    ProjectJpaEntity project = projectJpaRepository.findById(projectId).orElseThrow();
    long viewCount = viewCounter.get(projectId);         // Redis!
    return ProjectDetail.of(project, viewCount);
}

// ✅ DB 만 트랜잭션, Redis 는 밖에서
public ProjectDetail detail(Long projectId) {
    ProjectJpaEntity project = transactionTemplate.execute(
            s -> projectJpaRepository.findById(projectId).orElseThrow());
    long viewCount = viewCounter.get(projectId);         // 트랜잭션 밖
    return ProjectDetail.of(project, viewCount);
}
```

---

## 3. presentation

### 3.1 Request / Response DTO

```java
package com.k_place.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public record CreatePledgeRequest(
        @NotEmpty @Valid List<RewardOrderRequest> rewards) {

    public record RewardOrderRequest(
            @NotNull Long rewardId,
            @Min(1) int quantity) {}

    /** 형식 검증은 여기까지. 재고·상태 같은 비즈니스 규칙은 Entity 가 판단한다. */
    public CreatePledgeCommand toCommand(Long projectId, Long userId) {
        return new CreatePledgeCommand(projectId, userId,
                rewards.stream()
                        .map(r -> new CreatePledgeCommand.RewardOrder(r.rewardId(), r.quantity()))
                        .toList());
    }
}
```

```java
package com.k_place.presentation;

import com.k_place.persistence.PledgeJpaEntity;
import java.time.Instant;

public record PledgeResponse(Long pledgeId, Long projectId, long totalPrice, Instant createdAt) {

    /** JpaEntity 를 그대로 반환하지 않는다. 컬럼 추가가 곧 API 변경이 되면 안 된다. */
    public static PledgeResponse from(PledgeJpaEntity pledge) {
        return new PledgeResponse(
                pledge.getPledgeId(), pledge.getProjectId(),
                pledge.getTotalPrice(), pledge.getCreatedAt());
    }
}
```

### 3.2 Controller

```java
package com.k_place.presentation;

import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/projects/{projectId}/pledges")   // kebab-case + 버전
@RequiredArgsConstructor
public class PledgeController {

    private final PledgeApplication pledgeApplication;   // Repository 를 직접 주입받지 않는다

    @PostMapping
    public ResponseEntity<Void> create(@PathVariable Long projectId,
                                       @AuthenticationPrincipal Long userId,
                                       @Valid @RequestBody CreatePledgeRequest request) {
        Long pledgeId = pledgeApplication.create(request.toCommand(projectId, userId));
        return ResponseEntity.created(URI.create("/v1/pledges/" + pledgeId)).build();
    }
}
```

### 3.3 예외 처리 — 도메인 예외만 추가한다

`GlobalExceptionHandler` 는 `common/presentation` 에 이미 있다. **핸들러를 새로 만들지 않는다.**
`BusinessException` 을 상속하면 `ErrorCode` 에 정의된 HTTP 상태로 자동 매핑된다.

```java
// common/exception/ErrorCode.java — 상수 추가 (상태 + 사용자 메시지를 여기서 결정)
REWARD_SOLD_OUT(HttpStatus.CONFLICT, "선택한 리워드의 재고가 부족합니다."),
PROJECT_NOT_PLEDGEABLE(HttpStatus.UNPROCESSABLE_ENTITY, "후원할 수 없는 프로젝트입니다."),
```

```java
package com.k_place.domain.exception;

import com.k_place.common.exception.BusinessException;
import com.k_place.common.exception.ErrorCode;

/** 두 번째 인자는 로그용 상세다. 클라이언트에는 ErrorCode 의 메시지만 나간다. */
public class RewardSoldOutException extends BusinessException {

    public RewardSoldOutException(Long rewardId) {
        super(ErrorCode.REWARD_SOLD_OUT, "reward sold out: rewardId=" + rewardId);
    }
}
```

응답:

```http
POST /v1/projects/1/pledges  →  409 Conflict

{ "code": "REWARD_SOLD_OUT", "message": "선택한 리워드의 재고가 부족합니다." }
```

**핸들러를 직접 고쳐야 하는 경우**는 프레임워크 예외를 새로 매핑할 때뿐이다
(예: `OptimisticLockingFailureException` → 409).

---

## 4. Redis — 장애 시 폴백

```java
package com.k_place.persistence;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProjectViewCounter {

    private static final Logger log = LoggerFactory.getLogger(ProjectViewCounter.class);

    private final StringRedisTemplate redis;

    public ProjectViewCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public long get(Long projectId) {
        try {
            String value = redis.opsForValue().get(key(projectId));
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException e) {
            log.warn("조회수 캐시 조회 실패, 0 으로 폴백: projectId={}", projectId, e);
            return 0L;                  // 캐시 장애가 기능 실패가 되지 않게 한다
        }
    }

    private String key(Long projectId) {
        return "project:v1:view-count:" + projectId;   // 키에 버전 포함
    }
}
```

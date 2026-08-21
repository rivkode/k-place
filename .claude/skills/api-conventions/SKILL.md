---
name: api-conventions
description: k-place 의 REST API 를 설계하거나 변경할 때 사용한다. URL 경로 규칙(kebab-case, /v1 버전), JSON 속성 규칙(camelCase), 목록 엔드포인트의 페이지네이션, HTTP 메서드/상태 코드 선택, 공통 응답·에러 포맷, 날짜·ID 표현, 하위 호환 정책을 포함한다. "API 추가", "엔드포인트", "Controller", "요청/응답 DTO", "REST" 같은 언급이 있으면 코드를 쓰기 전에 반드시 이 스킬을 먼저 확인한다.
---

# API Conventions - REST API 설계 규칙

Controller 와 Request/Response DTO 를 작성하기 전에 읽는다.
**핵심 원칙**: API 는 한 번 공개되면 바꾸기 어렵다. 일관성이 영리함보다 중요하다.

---

## 1. 절대 규칙 (예외 없음)

1. **URL 경로는 kebab-case** — `/v1/place-reviews`
2. **JSON 속성은 camelCase** — `{ "placeId": ..., "createdAt": ... }`
3. **목록 엔드포인트는 항상 페이지네이션 포함** — 전체 반환 금지
4. **API 버전은 URL 경로에 명시** — `/v1/`, `/v2/`

---

## 2. URL 설계

### 2.1 형식

```
/{version}/{resource}/{id}/{sub-resource}
```

- 리소스는 **복수형 명사**: `/v1/places` (O), `/v1/place` (X)
- 동사를 경로에 넣지 않는다: `/v1/places/get` (X)
- 단어 구분은 **하이픈**: `/v1/place-reviews` (O), `/v1/place_reviews`, `/v1/placeReviews` (X)
- 소문자만 사용
- 중첩은 **2단계까지**. 그 이상 필요하면 리소스를 독립시킨다.
  - `/v1/places/{placeId}/reviews` (O)
  - `/v1/places/{placeId}/reviews/{reviewId}/comments/{commentId}` (X)
    → `/v1/review-comments/{commentId}`

### 2.2 상태 변경 액션

CRUD 로 표현되지 않는 도메인 동작은 하위 경로 + `POST` 로 표현한다.

```
POST /v1/places/{placeId}/close        영업 종료 처리
POST /v1/reviews/{reviewId}/report     신고
```

이때도 경로 세그먼트는 kebab-case 다: `POST /v1/orders/{id}/cancel-request`

### 2.3 버전 정책

- 모든 엔드포인트는 `/v1` 로 시작한다. 버전 없는 경로를 만들지 않는다.
- **하위 호환이 깨지는 변경만** `/v2` 를 만든다.
  - 깨짐: 필드 삭제, 타입 변경, 필수 파라미터 추가, 의미 변경
  - 안 깨짐: 선택 필드 추가, 새 엔드포인트 추가, 에러 메시지 문구 변경
- `/v2` 를 만들면 `/v1` 은 최소 한 번의 릴리스 동안 유지하고, 응답 헤더에 `Deprecation` 을 명시한다.

---

## 3. HTTP 메서드와 상태 코드

| 메서드 | 용도 | 성공 코드 |
|---|---|---|
| `GET` | 조회 (부수효과 없음) | `200` |
| `POST` | 생성 / 액션 | 생성 `201` + `Location`, 액션 `200` |
| `PUT` | 전체 교체 (멱등) | `200` |
| `PATCH` | 부분 수정 | `200` |
| `DELETE` | 삭제 (멱등) | `204` |

### 3.1 에러 코드 선택

| 코드 | 사용 시점 |
|---|---|
| `400 Bad Request` | 입력 형식·검증 실패 (`@Valid` 실패) |
| `401 Unauthorized` | 인증 없음 / 만료 |
| `403 Forbidden` | 인증됐지만 권한 없음 |
| `404 Not Found` | 리소스 없음 |
| `409 Conflict` | 도메인 상태 충돌 (이미 취소된 주문 재취소 등) |
| `422 Unprocessable Entity` | 형식은 맞지만 비즈니스 규칙 위반 |
| `500 Internal Server Error` | 처리하지 못한 예외 (의도적으로 반환하지 않는다) |

**`200 OK` 에 에러를 담지 않는다.** `{"success": false}` 를 200 으로 주는 패턴 금지.

---

## 4. 페이지네이션 (필수)

목록을 반환하는 모든 엔드포인트에 적용한다.

### 4.1 요청 파라미터

```
GET /v1/places?page=0&size=20&sort=createdAt,desc
```

- `page`: 0-based, 기본값 `0`
- `size`: 기본값 `20`, **최대 `100`** (초과 시 100 으로 절삭하거나 `400`)
- `sort`: `필드명,asc|desc`. 허용 필드를 화이트리스트로 제한한다.

### 4.2 응답 형태

```json
{
  "content": [ { "placeId": "...", "name": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 137,
  "totalPages": 7,
  "hasNext": true
}
```

- Spring 의 `Page<T>` 를 **그대로 직렬화하지 않는다**. 위 형태의 자체 `PageResponse<T>` 로 감싼다.
  (`Page` 직렬화 결과는 Spring 버전에 따라 바뀌고 내부 구조가 노출된다.)
- 대용량·실시간 목록은 커서 방식을 쓸 수 있다: `?cursor=<opaque>&size=20` → 응답에 `nextCursor`.
  커서 값은 클라이언트가 해석하지 않는 불투명 문자열로 유지한다.

---

## 5. 요청 / 응답 본문

### 5.1 공통 규칙

- 최상위는 항상 **JSON 객체**. 배열을 최상위로 반환하지 않는다(확장 불가).
- 속성은 `camelCase`. 축약하지 않는다: `desc` (X) → `description` (O)
- `null` 과 "필드 없음" 을 구분하지 않는다. 값이 없으면 필드를 생략하거나 항상 `null` 로, **한 가지로 통일**.
- boolean 은 `is` 접두사 없이: `active`, `deleted` (O) / `isActive` (X)

### 5.2 타입 표현

| 종류 | 표현 | 예 |
|---|---|---|
| 식별자 | 숫자 (DB 의 auto increment id 를 그대로) | `"pledgeId": 1` |
| 날짜시간 | ISO-8601 UTC | `"createdAt": "2026-08-19T10:00:00Z"` |
| 날짜 | ISO-8601 | `"openDate": "2026-08-19"` |
| 금액 | 정수(최소 단위) + 통화 | `{ "amount": 15000, "currency": "KRW" }` |
| Enum | 대문자 스네이크 문자열 | `"status": "TEMPORARILY_CLOSED"` |

- **ID 는 DB 의 숫자 id 를 그대로 노출한다.** 순차 ID 가 데이터 규모를 짐작하게 한다는 단점은 알고 있으나,
  클라이언트·PRD 와 타입을 맞추는 편익이 더 크다고 판단했다. 감춰야 할 리소스가 생기면 그때 그 리소스만 별도 식별자를 쓴다.
- **금액에 `double` 을 쓰지 않는다.** 서버 내부는 `BigDecimal`, 응답은 정수 최소 단위.
- 시간은 항상 UTC 로 응답하고, 표시용 변환은 클라이언트 책임.

### 5.3 에러 응답 (전 엔드포인트 공통)

`@RestControllerAdvice` 에서 단일 포맷으로 반환한다.

구현은 이미 있다 — 새로 만들지 말고 아래를 쓴다.

| 클래스 | 위치 |
|---|---|
| `ErrorCode` (enum) | `common/exception/ErrorCode.java` |
| `BusinessException` | `common/exception/BusinessException.java` |
| `ErrorResponse` | `common/presentation/ErrorResponse.java` |
| `GlobalExceptionHandler` | `common/presentation/GlobalExceptionHandler.java` |

```json
{
  "code": "VALIDATION_FAILED",
  "message": "입력값 검증에 실패했습니다.",
  "fieldErrors": [
    { "field": "name", "message": "공백일 수 없습니다" }
  ]
}
```

- `code`: 기계가 분기하는 값. **`ErrorCode` enum 이름이 그대로 나간다.** 문자열을 흩뿌리지 않는다.
- `message`: 사람이 읽는 값. **여기에 스택트레이스·SQL·내부 클래스명을 넣지 않는다.**
- `fieldErrors`: 검증 실패일 때만 채워지고, 그 외에는 **직렬화에서 생략**된다(`NON_NULL`).

**새 에러를 추가하는 방법** — `GlobalExceptionHandler` 를 고치지 않는다.

1. `ErrorCode` 에 상수를 추가한다 (HTTP 상태 + 사용자 메시지를 여기서 결정).
2. `BusinessException` 을 상속한 도메인 예외를 만들고 그 ErrorCode 를 넘긴다.

```java
// common/exception/ErrorCode.java 에 추가
PLACE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 장소를 찾을 수 없습니다."),

// domain/exception/ProjectNotFoundException.java
public class PlaceNotFoundException extends BusinessException {
    public PlaceNotFoundException(PlaceId id) {
        super(ErrorCode.PLACE_NOT_FOUND, "place not found: " + id.value());
    }
}
```

생성자의 두 번째 인자는 **로그용 상세**다. 클라이언트에는 ErrorCode 의 메시지만 나간다.
`IllegalArgumentException` → 400, `IllegalStateException` → 409 로도 매핑되지만,
의미가 분명한 도메인 예외를 쓰는 편이 낫다.

---

## 6. 검증

- 입력 검증 애너테이션(`@NotBlank`, `@Size`, `@Positive`)은 **Presentation 의 Request DTO 에만** 둔다.
- Controller 파라미터에 `@Valid` 를 빠뜨리면 검증이 통째로 무시된다. 반드시 확인한다.
- 형식 검증은 DTO 가, **비즈니스 규칙 검증은 JpaEntity/Application 이** 한다. 둘을 섞지 않는다.
  - DTO: "수량은 1 이상" / Entity: "남은 재고보다 많이 살 수 없다"

---

## 7. 캐시와 Redis

- 조회 API 에 Redis 캐시를 붙일 때, **캐시 미스가 정상 경로**여야 한다. 캐시가 비어도 200 이 나가야 한다.
- 캐시 키에 버전을 포함한다: `place:v1:{placeId}`. 응답 구조가 바뀌면 키 버전을 올린다.
- 목록 응답 전체를 캐싱하기보다 단건을 캐싱하고 조립한다(무효화 범위가 좁아진다).

---

## 8. 자가 검증 체크리스트

API 를 추가·변경한 뒤 확인한다.

- [ ] 경로가 `/v1/` 로 시작하고 kebab-case 인가?
- [ ] 리소스가 복수형 명사이고 경로에 동사가 없는가?
- [ ] JSON 속성이 모두 camelCase 인가?
- [ ] 목록 엔드포인트에 `page`/`size` 와 `size` 상한이 있는가?
- [ ] 응답이 `Page<T>` 직렬화가 아니라 `PageResponse<T>` 인가?
- [ ] 상태 코드가 성공/실패 모두 의미에 맞는가? (에러를 200 으로 주지 않는가?)
- [ ] 에러 응답이 공통 포맷이고 `ErrorCode` 를 사용하는가?
- [ ] 날짜가 ISO-8601 UTC 인가?
- [ ] Request DTO 에 검증 애너테이션과 `@Valid` 가 모두 있는가?
- [ ] 하위 호환을 깨는 변경이라면 `/v2` 를 만들었는가?
- [ ] JpaEntity 를 그대로 응답하지 않고 Response DTO 로 변환했는가?

---

## 9. 안티 패턴

| 안티 패턴 | 올바른 방법 |
|---|---|
| `GET /v1/getPlaceList` | `GET /v1/places` |
| `/v1/place_reviews` | `/v1/place-reviews` |
| 목록 전체 반환 | `page`/`size` 필수, 상한 100 |
| `Page<PlaceResponse>` 직렬화 | 자체 `PageResponse<T>` |
| 200 + `{"success": false}` | 의미에 맞는 4xx |
| 예외 메시지를 그대로 `message` 에 | `ErrorCode` 기반 사용자 메시지 |
| JpaEntity 를 `@ResponseBody` 로 반환 | Response DTO 변환 |
| 버전 없는 `/places` 신설 | `/v1/places` |

---

## 다음 단계

- 계층 구조와 Entity 설계 → `.claude/skills/code-guidelines/SKILL.md`
- Controller 테스트 → `.claude/skills/testing-junit/SKILL.md`

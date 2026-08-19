---
name: pr-guidelines
description: k-place 에서 커밋을 만들고 Pull Request 를 생성할 때 사용한다. Conventional Commits 형식, 원자적 커밋 기준, 브랜치 네이밍, PR 제목·본문 템플릿, 셀프 리뷰 체크리스트, PR 크기 기준과 분할 전략, 머지 전략, 리뷰 코멘트 규약, GitHub 이슈 수정 워크플로우를 포함한다. "커밋해줘", "commit", "PR 올려줘", "pull request", "리뷰 요청", "이슈 고쳐줘" 같은 언급이 있거나 구현·테스트가 끝난 직후 반드시 사용한다.
---

# PR Guidelines - 커밋과 Pull Request

**코드, 테스트가 모두 통과한 후** 사용한다.
PR 은 **리뷰를 위해 최적화**되어야 한다. 작성자가 리뷰어에게 맥락을 제공할 책임이 있다.

> 이 저장소는 아직 git 초기화 전일 수 있다. `git status` 로 확인하고,
> 필요하면 `git init` 과 원격 저장소 연결을 먼저 사용자에게 확인받는다.

---

## 1. 핵심 원칙

1. **작게 유지** — 400줄 이하, 파일 20개 이하가 이상적. 크면 나눈다.
2. **단일 목적** — 한 PR 은 하나의 기능/버그/리팩토링만 담는다.
3. **맥락 제공** — 리뷰어가 배경을 파악하는 데 5분을 넘기지 않게 한다.
4. **통과 가능한 상태로 열기** — 빌드가 깨진 채로 리뷰 요청하지 않는다.
5. **셀프 리뷰 먼저** — PR 을 연 직후 본인이 diff 를 먼저 읽는다.

---

## 2. 커밋 규칙

### 2.1 세 가지 원칙

1. **원자적(Atomic)** — 한 커밋은 하나의 논리적 변경. 따로 롤백 가능해야 한다.
2. **자기 설명적** — 메시지만 보고 변경 내용을 이해할 수 있어야 한다.
3. **빌드 가능** — 각 커밋 시점에서 컴파일되고 테스트가 통과해야 한다 (`git bisect` 를 위해).

### 2.2 형식 (Conventional Commits)

```
<type>(<scope>): <subject>

<body>

<footer>
```

- `type`: **필수**
- `scope`: 권장 — 도메인 패키지명 (`review`, `place`, `member`, `common`)
- `subject`: **필수** — 한 줄, 50자 이내, 마침표 없음, 한국어
- `body`: 권장 — **왜** 이 변경이 필요한지
- `footer`: 선택 — `Closes #142`, `BREAKING CHANGE:`

### 2.3 Type 선택

| Type | 용도 |
|---|---|
| `feat` | 새 기능 (사용자/API 관점) |
| `fix` | 버그 수정 |
| `refactor` | 동작 변화 없는 내부 구조 개선 |
| `perf` | 성능 개선 (쿼리 최적화, 캐시 추가) |
| `test` | 테스트만 추가/수정 |
| `docs` | 문서만 변경 (JavaDoc, README, CLAUDE.md) |
| `style` | 포매팅 (동작 영향 없음) |
| `chore` | 의존성, 잡무 |
| `build` | Gradle 설정 변경 |
| `ci` | CI/CD 파이프라인 변경 |
| `revert` | 이전 커밋 되돌리기 |

**헷갈릴 때**: "이 변경이 사용자 또는 API 클라이언트에게 보이는가?"
→ 보이면 `feat`/`fix`, 안 보이면 `refactor`/`perf`/`test`/`docs`/`chore`.

### 2.4 예시

```
feat(review): 장소 리뷰 작성 API 추가

한 회원이 한 장소에 리뷰를 1개만 쓸 수 있도록 제한한다.
장소 평점 재계산은 ReviewWritten 이벤트를 AFTER_COMMIT 에서 처리하므로
평점 갱신 실패가 리뷰 작성을 롤백시키지 않는다.

- POST /v1/places/{placeId}/reviews
- 중복 작성 시 409 DUPLICATE_REVIEW
- 폐업 장소는 422 PLACE_NOT_REVIEWABLE

Closes #12
```

### 2.5 커밋 전 체크

- [ ] 한 커밋에 하나의 관심사만 있는가? (포매팅 + 기능이 섞이지 않았는가?)
- [ ] `./gradlew build` 가 통과하는가?
- [ ] 디버그 로그, 주석 처리된 코드, `System.out.println` 이 남아있지 않은가?
- [ ] 자격 증명·토큰이 포함되지 않았는가?

---

## 3. 브랜치

### 3.1 상시 브랜치는 `main` + `dev` 둘뿐이다

```
main   릴리스 브랜치. 배포 가능한 상태만 올라간다.
dev    개발 브랜치. 기본 통합 지점.
```

**두 브랜치 모두 직접 커밋하지 않는다. 반영은 오직 PR 로만 한다.**

- 작업은 **항상 새 작업 브랜치**에서 한다: `dev` 에서 분기 → 커밋 → 푸시 → **`dev` 로 PR**.
- 작업 브랜치는 머지 후 **삭제**한다. 그래야 상시 브랜치가 `main` + `dev` 둘로 유지된다.
- `main` 은 `dev` → `main` 릴리스 PR 로만 갱신된다.
- 작업 시작 전: `git checkout dev && git pull origin dev && git checkout -b <type>/<...>`

```
dev ──┬─→ feat/xxx ──PR──→ dev ──PR──→ main
      └─→ fix/yyy  ──PR──→ dev
```

**`dev` 에 직접 push 하지 않는다.** 급한 수정이라도 브랜치를 파고 PR 을 연다.
(리뷰 없이 머지하더라도 PR 은 변경 이력과 맥락을 남기는 문서다.)

### 3.2 작업 브랜치 네이밍

```
<type>/<scope>-<short-description>
```

- `feat/review-write-api`
- `fix/review-duplicate-check`
- `refactor/place-rating-policy`
- `chore/bump-spring-boot-3.5.14`

**규칙**: 소문자 + kebab-case, 80자 이내. 이슈 번호를 넣어도 된다 (`feat/12-review-write-api`).
`type` 은 2.3 절의 커밋 타입과 같은 값을 쓴다.

---

## 4. PR 제목

**Conventional Commits 형식을 그대로 따른다.**

```
feat(review): 장소 리뷰 작성 API 추가
fix(place): 폐업 장소가 목록에 노출되는 문제 수정
```

**이유**: Squash Merge 시 PR 제목이 곧 커밋 메시지가 되고, CHANGELOG 자동 생성과 호환된다.

---

## 5. PR 본문 템플릿

아래를 `.github/pull_request_template.md` 로 커밋해 모든 PR 에 사용한다.

```markdown
## 🎯 목적 (Why)

<!-- 이 PR 이 왜 필요한가? 해결하려는 문제 -->

Closes #<이슈번호>

## 📋 변경 사항 (What)

<!-- 파일 나열이 아니라 개념 수준으로 -->

- domain: `Review` Aggregate, `Rating` VO 추가
- application: `WriteReviewService`, `WriteReviewCommand` 추가
- infrastructure: `reviews` 테이블 추가, `ReviewMapper`/`ReviewRepositoryImpl`
- presentation: `POST /v1/places/{placeId}/reviews` 추가

## 🧪 테스트

- [x] domain 단위 테스트: `ReviewTest` — 불변식 및 상태 전이 전 분기
- [x] application 단위 테스트: `WriteReviewServiceTest` — 성공/중복/폐업 케이스
- [x] infrastructure: `ReviewRepositoryImplTest` (`@DataJpaTest`)
- [x] presentation: `ReviewControllerTest` (`@WebMvcTest`)

## 🖼 실행 결과

```http
POST /v1/places/PLC-1024/reviews
{ "rating": 5, "content": "좋았습니다" }

→ 201 Created
Location: /v1/reviews/REV-8821
```

## ⚠️ 리뷰 포인트

<!-- 특히 봐주었으면 하는 부분, 트레이드오프, 논의가 필요한 결정 -->

## 🔄 Breaking Change

- [ ] 없음
- [ ] 있음: 영향 범위와 마이그레이션 방법 기술

## ✅ 체크리스트

- [ ] CLAUDE.md 의 원칙을 지켰는가?
- [ ] 도메인 객체와 JpaEntity 가 분리되어 있는가?
- [ ] `@Transactional` 안에 Redis/HTTP 호출이 없는가?
- [ ] API 규칙(kebab-case, camelCase, 페이지네이션, /v1)을 지켰는가?
- [ ] `./gradlew build` 가 로컬에서 성공하는가?
- [ ] DB 스키마 변경이 있다면 마이그레이션 스크립트를 포함했는가?
- [ ] 셀프 리뷰를 완료했는가?
```

---

## 6. 셀프 리뷰 체크리스트

PR 을 연 직후 **본인이 먼저** 기계적으로 확인한다.

### 6.1 코드 품질
- [ ] 주석이 "왜" 를 설명하는가? ("무엇" 은 코드가 말한다)
- [ ] 이름이 의도를 드러내는가?
- [ ] 반복 코드가 있는가? 추출할 만한가?
- [ ] 예외 처리가 누락된 경로가 없는가?
- [ ] 매직 넘버 / 매직 스트링이 남아있지 않은가?
- [ ] 죽은 코드, 주석 처리된 코드가 없는가?

### 6.2 설계 (code-guidelines)
- [ ] domain 에 JPA/Spring 애너테이션이 없는가?
- [ ] JpaEntity 와 도메인 객체가 Mapper 를 통해 분리되어 있는가?
- [ ] Controller 가 Application 만 호출하는가?
- [ ] Application Service 에 비즈니스 규칙이 새지 않았는가?
- [ ] 다른 도메인 패키지의 infrastructure 를 import 하지 않았는가?
- [ ] `Clock` 등 비결정적 의존성이 주입 가능한가?

### 6.3 테스트 (testing-junit)
- [ ] 새 public 메서드에 테스트가 있는가?
- [ ] 성공 케이스뿐 아니라 실패·경계 케이스도 있는가?
- [ ] `@Disabled` 가 남아있지 않은가?
- [ ] VO/Aggregate 를 Mock 하지 않았는가?

### 6.4 성능 / 보안
- [ ] N+1 쿼리를 만들지 않는가?
- [ ] 조회 조건 컬럼에 인덱스가 있는가?
- [ ] 입력 검증이 presentation 계층에 있는가?
- [ ] 권한 검증이 필요한 엔드포인트에 누락이 없는가?
- [ ] 민감 정보가 로그·응답 메시지에 남지 않는가?

---

## 7. PR 크기와 분할

| 등급 | 기준 | 조치 |
|---|---|---|
| S (이상적) | 변경 < 100줄, 파일 < 10개 | 30분 내 리뷰 |
| M | 100~400줄, 파일 10~20개 | 1시간 내 리뷰 |
| L | 400~800줄 | 분할 고려 |
| XL | > 800줄 | **반드시 분할** |

### 분할 전략

1. **계층별 분할 (선호)**: domain → application → infrastructure → presentation
   각 PR 이 독립적으로 빌드 가능하고, 리뷰어가 안쪽부터 이해할 수 있다.
2. **기능별 분할**: 리뷰 작성 → 평점 재계산 리스너 → 리뷰 목록 조회
3. **리팩토링 선행**: `refactor: ...` 를 먼저 머지하고 `feat: ...` 를 올린다.

### 스택 PR

```
dev ← pr1 (refactor) ← pr2 (feat 1) ← pr3 (feat 2)
```
pr1 이 머지되면 pr2 의 base 를 `dev` 로 변경한다.

---

## 8. Draft PR

아래 경우 Draft 로 먼저 연다. `[WIP]` 접두사 대신 GitHub 의 Draft 기능을 쓴다.
- 설계 방향을 미리 공유하고 싶을 때
- CI 결과를 먼저 보고 싶을 때
- 작업 중간에 피드백이 필요할 때

---

## 9. 머지 전략

- **기본: Squash and Merge.** 히스토리가 PR 단위로 깔끔하게 유지된다.
  커밋 메시지가 PR 제목이 되므로 **제목을 정확히 쓴다.**
- 예외 — **Rebase and Merge**: 개별 커밋이 모두 의미 있고 독립적으로 빌드 가능할 때.

### 머지 전 최종 확인
- [ ] base 브랜치가 `dev` 인가? (릴리스 PR 이 아닌데 `main` 을 고르지 않았는가?)
- [ ] 머지 후 작업 브랜치를 삭제했는가?
- [ ] 리뷰 Approve 를 받았는가?
- [ ] 빌드/CI 가 녹색인가?
- [ ] 리뷰 중 논의된 변경이 반영되었는가?
- [ ] `main` 과 충돌이 없는가?

---

## 10. 리뷰 커뮤니케이션

- **모든 코멘트에 응답한다** (반영 / 반대 / 토론).
- 단순 수정은 "반영했습니다 (커밋 SHA)" 로 짧게.
- 반대 의견이면 근거를 명확히 적는다.
- 지적받은 것만 고치고 같은 패턴을 방치하지 않는다 → "동일 패턴을 X 에서도 수정했습니다".

### 코멘트 prefix

| Prefix | 의미 |
|---|---|
| `nit:` | 사소한 의견 (안 고쳐도 됨) |
| `question:` | 단순 질문 |
| `suggestion:` | 제안 (선택) |
| `issue:` | 반드시 수정 필요 |
| `blocking:` | 머지 차단 수준 |
| `praise:` | 칭찬 |

---

## 11. GitHub 이슈 수정 워크플로우

이슈 번호를 받아 수정할 때 아래 순서를 따른다. 각 단계는 이 문서와
`code-guidelines`, `testing-junit`, `api-conventions` 규칙을 그대로 적용한다.

1. `gh issue view <번호>` 로 이슈 세부 내용을 가져온다.
2. 문제를 이해하고 **재현 조건**을 정리한다. 불명확하면 이슈에 코멘트로 질문한다.
3. 관련 코드를 검색해 **영향 범위**를 계층별로 파악한다
   (`code-guidelines` 1부 Step 3).
4. `dev` 를 최신화하고 작업 브랜치를 판다: `git checkout dev && git pull origin dev && git checkout -b fix/<번호>-<short-description>`.
5. **먼저 실패하는 테스트**를 작성해 버그를 고정한다 (`testing-junit`).
6. 수정을 구현한다. 규칙은 `code-guidelines` 2부, API 변경이면 `api-conventions`.
7. `./gradlew build` 로 컴파일 + 전체 테스트를 확인한다.
8. Conventional Commits 형식으로 커밋한다. 본문에 **원인과 해결 방식**을 적고 `Closes #<번호>`.
9. 작업 브랜치를 푸시하고 `gh pr create --base dev` 로 PR 을 만든다. 본문은 5절 템플릿.
10. 셀프 리뷰(6절)를 마친 뒤 리뷰를 요청한다.

**주의**: 커밋과 푸시, PR 생성은 되돌리기 어렵거나 외부에 드러나는 행위다.
사용자가 명시적으로 요청하지 않았다면 **먼저 확인을 받는다.**

---

## 12. 안티 패턴

| 안티 패턴 | 올바른 방법 |
|---|---|
| "WIP: 일단 올려봅니다" | Draft PR 로 열고 Ready 전까지 맥락 정리 |
| PR 본문에 "코드 보면 압니다" | 목적/변경/테스트를 항상 명시 |
| 1,500줄 단일 PR | 계층·기능별 분할 |
| 포매팅과 기능 수정을 한 커밋에 | `style` 과 `feat` 커밋 분리 |
| 리뷰 중 전혀 다른 변경 추가 | 새 PR 로 분리 |
| 빌드 실패한 채로 리뷰 요청 | 녹색으로 만든 뒤 요청 |
| 리뷰 코멘트 무응답 | 모든 코멘트에 응답 |
| 제목이 `update`, `수정` | `<type>(<scope>): <subject>` |
| `dev` 에 직접 push | 작업 브랜치 → PR → `dev` |

---

## 13. 전체 흐름

```
요구사항 → 계획(code-guidelines 1부) → 구현(2부) → 테스트(testing-junit)
→ 커밋 → PR → 셀프 리뷰 → 리뷰 → 머지
```

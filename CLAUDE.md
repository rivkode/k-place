# k-place

## WHAT — 기술 스택과 구조

- **Java 21 / Spring Boot 3.5.14 / Gradle (Kotlin DSL, Wrapper)**
- **MySQL** (영속 저장소, Spring Data JPA) / **Redis** (캐시·세션·분산 락)
- **단일 모놀리식 애플리케이션**. 멀티모듈이 아니며 서비스 분리 계획도 없다.
  메시지 브로커, gRPC, 서비스 디스커버리 같은 MSA 요소를 도입하지 않는다.
- 환경별 저장소: **dev·테스트 모두 docker-compose.yml 의 MySQL/Redis** (H2 를 쓰지 않는다.
  스키마·Redis DB 번호로 공간만 분리), 운영 = 외부 MySQL/Redis (환경변수 주입)

```
src/main/java/com/k_place/
  KPlaceApplication.java        진입점
  common/                       전역 설정, 공통 예외(BusinessException, ErrorCode), 응답 포맷
  domain/                       도메인 enum(ProjectStatus 등), 도메인 예외
  persistence/                  XxxJpaEntity, XxxJpaRepository — 비즈니스 규칙이 여기 산다
  application/                  XxxApplication (@Service, @Transactional)
  presentation/                 Controller, Request/Response DTO
src/main/resources/
  application.yaml              공통 + 운영 기본값 (ddl-auto: validate)
  application-dev.yaml          dev 프로파일 — docker compose 자동 기동
src/test/resources/application.yaml   테스트 — k_place_test 스키마 / Redis 1번 DB
docker/mysql/init/                    MySQL 최초 기동 시 테스트 스키마 생성
docker-compose.yml                    개발용 mysql / redis / server (server 는 app 프로파일)
Dockerfile                            server 이미지 — bootJar 산출물을 COPY
src/test/java/com/k_place/      main 패키지 구조를 그대로 미러링
```

## WHY — 각 부분이 존재하는 이유

- **얇은 계층 3개**: persistence / application / presentation 이면 충분하다.
  도메인 객체와 JpaEntity 를 따로 두고 Mapper 로 잇는 이중 모델은 **쓰지 않는다.**
  파일 수가 규칙의 수보다 많아지는 순간 그것은 설계가 아니라 비용이다.
- **규칙은 JpaEntity 안에**: 데이터를 가진 객체가 자기 불변식을 지킨다
  (예: `RewardJpaEntity.decreaseSoldQuantity()` 가 재고 초과를 막는다).
  규칙이 Service 로 흩어지면 같은 검증이 복사되고, 한 곳만 고쳤을 때 나머지가 버그로 남는다.
- **application**: 여러 Entity 에 걸친 조율과 트랜잭션 경계만 담당한다.
  JpaRepository 를 직접 주입해 호출한다 — 구현이 하나뿐인 Repository 인터페이스는 아무것도 막아주지 못한다.
- **presentation**: JpaEntity 를 그대로 내보내지 않는다. DB 컬럼 추가가 곧 API 변경이 되면 안 된다.
- **common**: 도메인이 아닌 것(공통 예외 처리, 설정 클래스)만 둔다.
  비즈니스 규칙을 여기에 넣으면 소유자가 사라지므로 금지.
- **Redis**: MySQL 을 보호하기 위한 계층. 유실돼도 기능이 동작해야 한다(캐시는 정답이 아니라 사본).

## HOW — 작업 방식

### 명령어

```bash
docker compose up -d               # mysql + redis (테스트·개발 공용, 먼저 띄운다)
./gradlew build                    # 컴파일 + 전체 테스트
./gradlew test                     # 테스트만
./gradlew test --tests '*Place*'   # 특정 테스트
./gradlew bootRun                  # dev 프로파일로 실행 — mysql/redis 컨테이너 자동 기동
./gradlew bootJar && docker compose --profile app up -d --build   # 앱까지 컨테이너로
```

- **테스트는 개발과 같은 컨테이너를 쓴다.** 공간만 나눈다 — MySQL 은 `k_place_test` 스키마,
  Redis 는 1번 DB. 격리는 `IntegrationTest` 의 `@AfterEach` 가 그 공간만 비워서 만든다.
  컨테이너를 매번 띄우지 않으므로 통합 테스트가 16초에서 3초로 줄었다.
  **H2 를 쓰지 않는다** — 락·격리 수준이 달라 동시성 테스트가 거짓 통과하기 때문이다.
  `IntegrationTest` 를 상속한 테스트만 인프라를 요구하고, 도메인·Mockito·`@WebMvcTest` 는 없이도 돈다.
- **dev 실행은 Docker 가 떠 있어야 한다.** `spring-boot-docker-compose` 가 `docker-compose.yml` 을 읽어
  컨테이너를 기동하고 접속 정보를 주입하므로, dev 설정에 DB 자격 증명을 적지 않는다.
- `server` 서비스는 compose 프로파일 `app` 뒤에 있다. 프로파일 없이 `up` 하면 mysql/redis 만 뜨므로
  `bootRun` 이 자기 자신을 컨테이너로 중복 실행하지 않는다.
- 운영 접속 정보는 환경변수로 주입한다(`DB_HOST`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`).
  자격 증명을 소스에 하드코딩하지 않는다.
- **상시 브랜치는 `main` 과 `dev` 둘뿐이고, 둘 다 직접 push 하지 않는다.**
  작업은 항상 `dev` 에서 분기한 작업 브랜치에서 하고, `dev` 반영은 **PR 로만** 한다 (머지 후 브랜치 삭제).

### 규칙이 적힌 곳 (필요할 때 열어볼 것)

작업 종류에 맞는 스킬을 **먼저 읽고** 시작한다. 전체를 미리 읽지 말고 해당 파일만 연다.

| 상황 | 문서 |
|---|---|
| 기능 구현·리팩토링 시작, 계층/Entity 설계 | `.claude/skills/code-guidelines/SKILL.md` |
| REST API 추가·변경 | `.claude/skills/api-conventions/SKILL.md` |
| 테스트 작성 | `.claude/skills/testing-junit/SKILL.md` |
| 커밋·PR 생성, 이슈 수정 워크플로우 | `.claude/skills/pr-guidelines/SKILL.md` |

### 지켜야 할 최소 원칙

1. **코드 작성 전 계획부터**. 요구사항 재진술 → 영향 범위 → TodoList → 승인.
   (`code-guidelines` 의 계획 단계)
2. **의존성 방향**: `presentation → application → persistence`.
   Application 은 JpaRepository 를 **직접** 호출한다 (중간 인터페이스를 만들지 않는다).
3. **비즈니스 규칙은 JpaEntity 안에** 둔다. `@Setter`/`@Data` 금지, 상태 변경은 의미 있는 메서드로.
   불변식은 Entity 메서드와 DB 제약(CHECK, unique) 두 겹으로 막는다.
4. **Controller 가 Repository 를 직접 호출하지 않는다.** 반드시 Application 을 거친다.
5. **`@Transactional` 은 Application 계층에만** 붙이고, 트랜잭션 안에 외부 I/O(Redis, HTTP)를 넣지 않는다.
6. **JpaEntity 를 HTTP 응답으로 직접 반환하지 않는다.** Response DTO 로 변환한다.
7. **요구사항에 없는 추상화를 미리 만들지 않는다.** 인터페이스는 구현이 둘 이상이거나
   테스트에서 반드시 대체해야 할 때만 뽑는다.
8. 기존 파일을 수정할 때는 **주변 코드의 스타일·네이밍을 그대로 따른다.**
   새 패턴을 도입하려면 먼저 이유를 말하고 합의한다.

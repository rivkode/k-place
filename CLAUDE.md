# k-place

## WHAT — 기술 스택과 구조

- **Java 21 / Spring Boot 3.5.14 / Gradle (Kotlin DSL, Wrapper)**
- **MySQL** (영속 저장소, Spring Data JPA) / **Redis** (캐시·세션·분산 락)
- **단일 모놀리식 애플리케이션**. 멀티모듈이 아니며 서비스 분리 계획도 없다.
  메시지 브로커, gRPC, 서비스 디스커버리 같은 MSA 요소를 도입하지 않는다.
- 환경별 저장소: **테스트 = Testcontainers 의 MySQL/Redis** (H2 를 쓰지 않는다),
  **dev = docker-compose.yml 의 MySQL/Redis 컨테이너**, 운영 = 외부 MySQL/Redis (환경변수 주입)

```
src/main/java/com/k_place/
  KPlaceApplication.java        진입점
  common/                       전역 설정, 공통 예외, 응답 포맷, ErrorCode
  <context>/                    도메인별 패키지 (예: place, member, review)
    domain/                     Aggregate, Entity, VO, Domain Service, Repository 인터페이스
    application/                Application Service, Command/Query
    infrastructure/             XxxJpaEntity, Repository 구현, Mapper, 외부 연동
    presentation/               Controller, Request/Response DTO
src/main/resources/
  application.yaml              공통 + 운영 기본값 (ddl-auto: validate)
  application-dev.yaml          dev 프로파일 — docker compose 자동 기동
src/test/resources/application.yaml   테스트 — 접속 정보는 Testcontainers 가 주입
docker-compose.yml                    mysql / redis / server (server 는 app 프로파일)
Dockerfile                            server 이미지 — bootJar 산출물을 COPY
src/test/java/com/k_place/      main 패키지 구조를 그대로 미러링
```

## WHY — 각 부분이 존재하는 이유

- **도메인별 패키지 분리**: 계층(controller/service/entity)이 아니라 도메인으로 먼저 나눈다.
  변경은 대부분 하나의 도메인 안에서 끝나므로, 수정 범위를 한 디렉터리로 가둘 수 있다.
- **domain 계층**: 비즈니스 규칙의 유일한 출처. 프레임워크에 의존하지 않아 테스트가 빠르고,
  JPA·웹 스펙이 바뀌어도 규칙이 흔들리지 않는다.
- **infrastructure 의 JpaEntity 분리**: DB 스키마의 사정(컬럼 타입, 인덱스, 정규화)이
  도메인 모델을 오염시키지 않게 한다. 둘 사이는 Mapper 가 잇는다.
- **common**: 도메인이 아닌 것(공통 예외 처리, 설정 클래스)만 둔다.
  비즈니스 규칙을 여기에 넣으면 어느 도메인 소유인지 알 수 없게 되므로 금지.
- **Redis**: MySQL 을 보호하기 위한 계층. 유실돼도 기능이 동작해야 한다(캐시는 정답이 아니라 사본).

## HOW — 작업 방식

### 명령어

```bash
./gradlew build                    # 컴파일 + 전체 테스트
./gradlew test                     # 테스트만 (Testcontainers — Docker 필요)
./gradlew test --tests '*Place*'   # 특정 테스트
./gradlew bootRun                  # dev 프로파일로 실행 — mysql/redis 컨테이너 자동 기동
docker compose up -d               # mysql + redis 만 수동으로 띄울 때
./gradlew bootJar && docker compose --profile app up -d --build   # 앱까지 컨테이너로
```

- **테스트는 운영과 같은 MySQL/Redis 위에서 돈다.** `support/container/TestcontainersConfiguration`
  이 컨테이너를 띄우고 `@ServiceConnection` 이 접속 정보를 주입한다.
  **H2 를 쓰지 않는다** — 락·격리 수준이 달라 동시성 테스트가 거짓 통과하기 때문이다.
  Spring 컨텍스트가 필요한 테스트만 Docker 를 요구하고, 도메인·Mockito·`@WebMvcTest` 는 없이도 돈다.
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
| 기능 구현·리팩토링 시작, 도메인 설계 | `.claude/skills/code-guidelines/SKILL.md` |
| REST API 추가·변경 | `.claude/skills/api-conventions/SKILL.md` |
| 테스트 작성 | `.claude/skills/testing-junit/SKILL.md` |
| 커밋·PR 생성, 이슈 수정 워크플로우 | `.claude/skills/pr-guidelines/SKILL.md` |

### 지켜야 할 최소 원칙

1. **코드 작성 전 계획부터**. 요구사항 재진술 → 영향 범위 → TodoList → 승인.
   (`code-guidelines` 의 계획 단계)
2. **의존성 방향**: `presentation → application → domain ← infrastructure`.
   domain 은 어떤 계층도, 어떤 프레임워크도 import 하지 않는다.
3. **Controller 가 Repository 를 직접 호출하지 않는다.** 반드시 Application Service 를 거친다.
4. **`@Transactional` 은 Application 계층에만** 붙이고, 트랜잭션 안에 외부 I/O(Redis, HTTP)를 넣지 않는다.
5. **도메인 객체를 HTTP 응답으로 직접 반환하지 않는다.** Response DTO 로 변환한다.
6. 기존 파일을 수정할 때는 **주변 코드의 스타일·네이밍을 그대로 따른다.**
   새 패턴을 도입하려면 먼저 이유를 말하고 합의한다.

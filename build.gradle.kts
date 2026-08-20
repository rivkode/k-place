plugins {
	java
	id("org.springframework.boot") version "3.5.14"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	runtimeOnly("com.mysql:mysql-connector-j")

	// dev 프로파일 실행 시 docker-compose.yml 의 MySQL/Redis 를 자동 기동하고 접속 정보를 주입한다
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")

	// 테스트도 운영과 같은 MySQL/Redis 위에서 돈다. H2 는 쓰지 않는다 —
	// 락·격리 수준·제약 동작이 달라 동시성 테스트가 거짓 통과하기 때문이다.
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:mysql")

	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()

	// Testcontainers 는 정리용 Ryuk 컨테이너에 도커 소켓을 마운트한다. Colima / Rancher Desktop 처럼
	// 소켓이 VM 안에 있는 런타임에서는 호스트 경로(~/.colima/.../docker.sock)를 그대로 마운트하려다
	// 실패하므로, VM 기준 경로로 바로잡는다. Docker Desktop 도 같은 경로라 무해하다.
	// 이미 설정한 값이 있으면 존중한다.
	environment(
		"TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE",
		System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") ?: "/var/run/docker.sock",
	)
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active", "dev"))
}

// bootJar 만 남긴다 — Dockerfile 이 build/libs/*.jar 를 단일 파일로 COPY 하기 때문
tasks.named<Jar>("jar") {
	enabled = false
}

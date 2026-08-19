# k-place 애플리케이션 컨테이너.
# 호스트에서 ./gradlew bootJar 한 결과를 그대로 COPY 하는 단순 구조.
# (빌드 흐름을 Dockerfile 만 보고 따라갈 수 있게 멀티스테이지를 쓰지 않았다.)

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# build.gradle.kts 에서 plain jar 를 비활성화했으므로 build/libs 에는 실행 가능 jar 하나만 남는다.
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} /app/k-place.jar

EXPOSE 8080

# JVM 옵션:
# - UseContainerSupport (Java 21 기본 true) 로 컨테이너 cpu/memory 한도를 자동 검출
# - MaxRAMPercentage=70 → 나머지 30% 는 OS/native/metaspace 몫
# - urandom — SecureRandom 부팅 시 entropy 부족으로 지연되는 것을 회피
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=70.0", \
    "-XX:+UseG1GC", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/k-place.jar"]

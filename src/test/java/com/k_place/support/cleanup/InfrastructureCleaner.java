package com.k_place.support.cleanup;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 테스트 사이에 인프라 상태를 되돌린다.
 *
 * <p>테스트가 컨테이너를 새로 띄우지 않고 개발용 MySQL/Redis 를 공유하므로, 격리는
 * <b>물리적 분리가 아니라 데이터 정리</b>로 만든다. 정리를 빠뜨리면 앞선 테스트가 남긴 행 때문에
 * 뒤 테스트가 실패하거나, 더 나쁘게는 <b>실행 순서에 따라 통과와 실패가 갈린다.</b>
 *
 * <p>대상은 테스트 전용 공간뿐이다 — MySQL 은 {@code k_place_test} 스키마,
 * Redis 는 1번 DB. 개발 데이터({@code k_place}, Redis 0번)는 건드리지 않는다.
 */
public class InfrastructureCleaner {

    private final DataSource dataSource;

    private final RedisConnectionFactory redisConnectionFactory;

    public InfrastructureCleaner(DataSource dataSource, RedisConnectionFactory redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public void clean() {
        truncateAllTables();
        flushRedis();
    }

    /**
     * 현재 스키마의 모든 테이블을 비운다.
     *
     * <p>{@code DELETE} 가 아니라 {@code TRUNCATE} 를 쓴다. 자동 증가 값까지 되돌아가므로
     * "첫 저장이면 ID 가 1" 같은 단언이 실행 순서에 흔들리지 않는다.
     *
     * <p>외래 키 제약은 잠시 끈다. 켜 둔 채로는 참조 순서를 맞춰 지워야 하는데, 그 순서가
     * 스키마가 바뀔 때마다 깨진다. <b>한 커넥션 안에서</b> 처리하는 것이 중요하다 —
     * {@code FOREIGN_KEY_CHECKS} 는 세션 단위라, 커넥션이 갈리면 끈 적 없는 세션에서 지우게 된다.
     */
    private void truncateAllTables() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {

            List<String> tables = findTables(statement);
            if (tables.isEmpty()) {
                return;
            }

            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String table : tables) {
                    statement.execute("TRUNCATE TABLE `" + table + "`");
                }
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("테스트 데이터 정리에 실패했다", e);
        }
    }

    private List<String> findTables(Statement statement) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (var resultSet = statement.executeQuery("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_type = 'BASE TABLE'
                """)) {
            while (resultSet.next()) {
                tables.add(resultSet.getString(1));
            }
        }
        return tables;
    }

    /** 테스트용 1번 DB 만 비운다. {@code FLUSHALL} 이 아니라 {@code FLUSHDB} 인 이유다. */
    private void flushRedis() {
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }
}

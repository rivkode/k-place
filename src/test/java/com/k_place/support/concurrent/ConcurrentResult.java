package com.k_place.support.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

/**
 * {@link ConcurrentRunner} 실행 결과.
 *
 * <p>작업이 던진 예외는 실행을 중단시키지 않고 여기에 모인다.
 * "N 개 중 정확히 M 개만 성공해야 한다" 같은 경합 검증은 예외를 세는 방식으로 표현한다.
 *
 * @param successCount 예외 없이 끝난 작업 수
 * @param exceptions   작업이 던진 예외. 제출 순서가 아니라 <b>발생 순서</b>다.
 */
public record ConcurrentResult(int successCount, List<Throwable> exceptions) {

    public ConcurrentResult {
        exceptions = List.copyOf(exceptions);
    }

    public int failureCount() {
        return exceptions.size();
    }

    public int totalCount() {
        return successCount + failureCount();
    }

    /** 특정 타입의 예외만 추린다. 하위 타입도 포함된다. */
    public <T extends Throwable> List<T> exceptionsOf(Class<T> type) {
        return exceptions.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * 모든 작업이 예외 없이 끝났음을 검증한다.
     *
     * <p>실패 시 첫 예외를 원인(cause)으로 달아 스택트레이스를 보존한다.
     * 예외를 삼키면 "왜 실패했는지" 를 찾느라 시간을 버리게 된다.
     */
    public ConcurrentResult assertAllSucceeded() {
        if (!exceptions.isEmpty()) {
            throw new AssertionError(
                    "%d/%d 개 작업이 실패했다. 첫 예외를 cause 로 첨부한다."
                            .formatted(failureCount(), totalCount()),
                    exceptions.getFirst());
        }
        return this;
    }

    /** 성공한 작업 수를 검증한다. 재고 100 개에 200 명이 몰리는 식의 경합 검증에 쓴다. */
    public ConcurrentResult assertSuccessCount(int expected) {
        assertThat(successCount)
                .as("성공 %d 건을 기대했으나 %d 건 (실패 %d 건)", expected, successCount, failureCount())
                .isEqualTo(expected);
        return this;
    }

    /** 실패한 작업 수를 검증한다. */
    public ConcurrentResult assertFailureCount(int expected) {
        assertThat(failureCount())
                .as("실패 %d 건을 기대했으나 %d 건", expected, failureCount())
                .isEqualTo(expected);
        return this;
    }
}

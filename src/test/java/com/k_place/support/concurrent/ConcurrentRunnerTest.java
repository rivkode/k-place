package com.k_place.support.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 동시성 테스트 유틸 자체의 검증.
 *
 * <p>이 유틸을 믿고 다른 테스트를 쓰게 되므로, "정말 동시에 출발하는가" 와
 * "멈춘 작업이 있을 때 매달리지 않고 실패하는가" 를 먼저 확인한다.
 */
class ConcurrentRunnerTest {

    @Nested
    @DisplayName("동시 실행")
    class Run {

        @Test
        @DisplayName("모든 작업이 실행되고 성공 수가 집계된다")
        void run_whenAllSucceed_shouldCountEvery() {
            // given
            AtomicInteger executed = new AtomicInteger();

            // when
            ConcurrentResult result = ConcurrentRunner.run(50, i -> executed.incrementAndGet());

            // then
            assertThat(executed.get()).isEqualTo(50);
            assertThat(result.successCount()).isEqualTo(50);
            assertThat(result.failureCount()).isZero();
            assertThat(result.totalCount()).isEqualTo(50);
        }

        @Test
        @DisplayName("작업들이 순차가 아니라 동시에 출발한다")
        void run_shouldStartTasksSimultaneously() {
            // given — 모든 작업이 출발한 뒤 풀리는 래치. 순차 실행이면 첫 작업에서 영원히 멈춘다.
            int concurrency = 20;
            CountDownLatch allStarted = new CountDownLatch(concurrency);

            // when
            ConcurrentResult result = ConcurrentRunner.run(concurrency, Duration.ofSeconds(5), i -> {
                allStarted.countDown();
                allStarted.await();   // 20개가 모두 도달해야 통과한다
            });

            // then
            result.assertAllSucceeded();
            assertThat(allStarted.getCount()).isZero();
        }

        @Test
        @DisplayName("각 작업은 0 부터 시작하는 고유한 인덱스를 받는다")
        void run_shouldPassUniqueIndex() {
            // given
            boolean[] seen = new boolean[30];

            // when
            ConcurrentRunner.run(30, i -> seen[i] = true).assertAllSucceeded();

            // then
            assertThat(seen).containsOnly(true);
        }

        @Test
        @DisplayName("concurrency 가 1 미만이면 즉시 거부한다")
        void run_whenConcurrencyBelowOne_shouldThrow() {
            assertThatThrownBy(() -> ConcurrentRunner.run(0, i -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1 이상");
        }
    }

    @Nested
    @DisplayName("예외 수집")
    class Exceptions {

        @Test
        @DisplayName("일부 작업이 실패해도 나머지는 끝까지 실행되고 예외가 수집된다")
        void run_whenSomeFail_shouldCollectExceptionsAndKeepGoing() {
            // given — 짝수 인덱스만 실패
            int concurrency = 20;

            // when
            ConcurrentResult result = ConcurrentRunner.run(concurrency, i -> {
                if (i % 2 == 0) {
                    throw new IllegalStateException("실패 " + i);
                }
            });

            // then
            result.assertSuccessCount(10).assertFailureCount(10);
            assertThat(result.exceptionsOf(IllegalStateException.class)).hasSize(10);
        }

        @Test
        @DisplayName("예외 타입별로 추릴 수 있다")
        void exceptionsOf_shouldFilterByType() {
            // given & when
            ConcurrentResult result = ConcurrentRunner.run(9, i -> {
                if (i % 3 == 0) {
                    throw new IllegalStateException("state");
                }
                if (i % 3 == 1) {
                    throw new IllegalArgumentException("argument");
                }
            });

            // then
            assertThat(result.exceptionsOf(IllegalStateException.class)).hasSize(3);
            assertThat(result.exceptionsOf(IllegalArgumentException.class)).hasSize(3);
            assertThat(result.exceptionsOf(RuntimeException.class)).hasSize(6);   // 하위 타입 포함
            assertThat(result.successCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("검사 예외도 그대로 수집한다")
        void run_whenCheckedException_shouldCollect() {
            // when
            ConcurrentResult result = ConcurrentRunner.run(3, i -> {
                throw new Exception("checked");
            });

            // then
            assertThat(result.failureCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("assertAllSucceeded 는 실패 시 첫 예외를 cause 로 남긴다")
        void assertAllSucceeded_whenFailed_shouldAttachCause() {
            // given
            ConcurrentResult result = ConcurrentRunner.run(4, i -> {
                throw new IllegalStateException("원인 " + i);
            });

            // when & then
            assertThatThrownBy(result::assertAllSucceeded)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("4/4")
                    .cause()
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("타임아웃")
    class Timeout {

        @Test
        @DisplayName("끝나지 않는 작업이 있으면 매달리지 않고 제한 시간 뒤 실패한다")
        void run_whenTaskNeverFinishes_shouldFailFast() {
            // given — 테스트가 끝나면 풀어줄 래치. shutdownNow 로 인터럽트되므로 실제로는 그 전에 깨어난다.
            CountDownLatch neverReleased = new CountDownLatch(1);

            // when & then
            assertThatThrownBy(() ->
                    ConcurrentRunner.run(2, Duration.ofMillis(300), i -> neverReleased.await()))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("제한 시간");
        }
    }
}

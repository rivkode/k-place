package com.k_place.support.concurrent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 여러 작업을 <b>동시에</b> 실행해 경합을 재현하는 테스트 유틸.
 *
 * <pre>{@code
 * ConcurrentRunner.run(200, i -> reviewService.write(command(i)))
 *         .assertSuccessCount(100)
 *         .assertFailureCount(100);
 * }</pre>
 *
 * <p><b>왜 필요한가</b> — 그냥 스레드를 띄우면 먼저 시작한 스레드가 끝난 뒤에 다음이 시작되어
 * 경합이 재현되지 않는다. 이 클래스는 두 단계 래치로 <b>모든 작업이 출발선에 선 뒤 동시에</b>
 * 출발시킨다.
 *
 * <ol>
 *   <li>각 작업이 {@code ready} 를 countDown 하고 {@code start} 를 기다린다</li>
 *   <li>모든 작업이 준비되면 메인 스레드가 {@code start} 를 열어 한꺼번에 출발시킨다</li>
 *   <li>{@code done} 으로 전부 끝날 때까지 기다린다 (타임아웃 초과 시 실패)</li>
 * </ol>
 *
 * <p><b>가상 스레드를 쓴다.</b> 고정 크기 풀을 쓰면 풀 크기보다 많은 작업을 넣었을 때
 * 앞선 작업들이 {@code start} 를 기다리며 스레드를 점유해 나머지가 영영 시작되지 못한다.
 * 가상 스레드는 작업당 하나씩 생기므로 이 함정이 없고, DB·Redis 대기처럼 블로킹 I/O 경합에도 적합하다.
 *
 * <p><b>주의</b> — 이 유틸은 경합을 <i>드러내기 쉽게</i> 만들 뿐 <i>보장</i> 하지는 않는다.
 * 동시성 버그는 확률적으로 나타나므로, 한 번 통과했다고 락이 올바르다는 뜻은 아니다.
 * 정합성 검증은 "정확히 N 개만 성공" 처럼 <b>결정적인 사후 상태</b>로 표현해야 한다.
 */
public final class ConcurrentRunner {

    /** 기본 타임아웃. DB 락 대기까지 감안한 값이며, 초과하면 데드락이나 lock-wait 을 의심한다. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private ConcurrentRunner() {
    }

    /** {@code concurrency} 개의 작업을 동시에 실행한다. 타임아웃 10초. */
    public static ConcurrentResult run(int concurrency, ConcurrentTask task) {
        return run(concurrency, DEFAULT_TIMEOUT, task);
    }

    /**
     * {@code concurrency} 개의 작업을 동시에 실행한다.
     *
     * @param concurrency 동시에 실행할 작업 수 (1 이상)
     * @param timeout     전체 완료를 기다리는 시간. 초과하면 {@link AssertionError}
     * @param task        각 작업. 던진 예외는 수집되며 다른 작업을 중단시키지 않는다
     * @return 성공 수와 수집된 예외
     */
    public static ConcurrentResult run(int concurrency, Duration timeout, ConcurrentTask task) {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency 는 1 이상이어야 한다: " + concurrency);
        }

        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);

        AtomicInteger successCount = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

        // try-with-resources 를 쓰지 않는다. ExecutorService.close() 는 종료를 무기한 기다리므로,
        // 작업 하나가 멈추면 타임아웃으로 실패시킨 뒤 close() 에서 다시 영원히 블로킹된다.
        // 여기서는 shutdownNow() 로 멈춘 작업을 인터럽트해 테스트가 매달리지 않고 실패하게 한다.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (int i = 0; i < concurrency; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        ready.countDown();
                        start.await();
                        task.run(index);
                        successCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        exceptions.add(e);
                    } catch (Throwable t) {
                        exceptions.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            await(ready, timeout, "모든 작업이 출발선에 서지 못했다");
            start.countDown();
            await(done, timeout, "제한 시간 안에 끝나지 않았다 — 데드락이나 DB lock-wait 을 의심한다");
        } finally {
            executor.shutdownNow();
        }

        return new ConcurrentResult(successCount.get(), List.copyOf(exceptions));
    }

    private static void await(CountDownLatch latch, Duration timeout, String message) {
        try {
            if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("%s (남은 작업 %d개, 타임아웃 %s)"
                        .formatted(message, latch.getCount(), timeout));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("대기 중 인터럽트됨: " + message, e);
        }
    }
}

package com.k_place.support.concurrent;

/**
 * 동시에 실행할 작업. 인덱스는 0 부터 {@code concurrency - 1} 까지 주어진다.
 *
 * <p>검사 예외를 던질 수 있다. 던져진 예외는 테스트를 중단시키지 않고
 * {@link ConcurrentResult} 에 수집되므로, 람다 안에서 try/catch 로 감쌀 필요가 없다.
 */
@FunctionalInterface
public interface ConcurrentTask {

    void run(int index) throws Exception;
}

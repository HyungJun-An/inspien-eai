package com.inspien.eai.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 주기(분) 대신 몇 ms 주기로 돌려서 확인한다
class FixedDelaySchedulerTest {

    private final FixedDelayScheduler scheduler = new FixedDelayScheduler();

    @AfterEach
    void stop() {
        scheduler.stop(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("작업에서 예외가 나도 다음 실행은 계속된다")
    void keepsRunningAfterTaskThrows() throws Exception {
        // ScheduledExecutorService 는 작업에서 예외가 밖으로 나가면 그 작업의 다음 실행을 조용히 멈춘다
        CountDownLatch threeRuns = new CountDownLatch(3);
        scheduler.schedule("실패하는 작업", Duration.ofMillis(10), () -> {
            threeRuns.countDown();
            throw new IllegalStateException("DB 오류");
        });

        assertThat(threeRuns.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("같은 작업은 겹쳐서 실행되지 않는다")
    void doesNotOverlapSameTask() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        CountDownLatch fiveRuns = new CountDownLatch(5);
        scheduler.schedule("느린 작업", Duration.ofMillis(1), () -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            sleep(20); // 주기보다 오래 걸려도
            running.decrementAndGet();
            fiveRuns.countDown();
        });

        assertThat(fiveRuns.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(maxRunning).hasValue(1);
    }

    @Test
    @DisplayName("멈추면 더 실행하지 않는다")
    void runsNoMoreAfterStop() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch firstRun = new CountDownLatch(1);
        scheduler.schedule("작업", Duration.ofMillis(10), () -> {
            runs.incrementAndGet();
            firstRun.countDown();
        });
        assertThat(firstRun.await(2, TimeUnit.SECONDS)).isTrue();

        scheduler.stop(Duration.ofSeconds(1));
        int afterStop = runs.get();
        sleep(100);

        assertThat(runs).hasValue(afterStop);
        assertThat(scheduler.taskNames()).containsExactly("작업");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

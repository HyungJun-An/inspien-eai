package com.inspien.eai.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// 주기 작업 (운송 연계 배치, 영수증 재처리). 이전 실행이 끝나고 delay 뒤에 다음 실행 → 같은 작업이 겹치지 않는다
// 작업에서 예외가 나도 다음 실행은 계속된다
public class FixedDelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(FixedDelayScheduler.class);
    // 작업마다 스레드 1개: 영수증 재처리(FTP)가 느려도 운송 연계 배치가 밀리지 않게
    private static final int THREADS = 2;

    private final ScheduledExecutorService executor;
    private final List<String> taskNames = new CopyOnWriteArrayList<>();

    public FixedDelayScheduler() {
        AtomicInteger count = new AtomicInteger();
        executor = Executors.newScheduledThreadPool(THREADS, r -> new Thread(r, "batch-" + count.incrementAndGet()));
    }

    // 첫 실행도 delay 뒤에 한다 (앱을 켜자마자 실제 DB 를 바꾸지 않게)
    public void schedule(String name, Duration delay, Runnable task) {
        taskNames.add(name);
        long millis = delay.toMillis();
        // scheduleWithFixedDelay: 끝난 시점부터 delay (scheduleAtFixedRate 는 시작 시점 기준이라 오래 걸리면 밀려서 연달아 돈다)
        executor.scheduleWithFixedDelay(() -> runSafely(name, task), millis, millis, TimeUnit.MILLISECONDS);
    }

    // 등록된 작업 이름 (시작 로그·테스트용)
    public List<String> taskNames() {
        return List.copyOf(taskNames);
    }

    // 새 실행을 멈추고, 실행 중인 작업은 끝날 때까지 최대 timeout 기다린다 (배치가 주문 중간에 끊기지 않게)
    public void stop(Duration timeout) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("주기 작업이 {}초 안에 끝나지 않아 중단합니다", timeout.toSeconds());
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    // ScheduledExecutorService 는 작업에서 예외가 밖으로 나가면 그 작업의 다음 실행을 조용히 멈춘다(로그도 없음)
    // → 여기서 잡아서 로그로 남기고, 다음 주기는 계속 돌게 한다
    private static void runSafely(String name, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("[{}] 주기 작업 실패 - 다음 주기에 다시 실행", name, e);
        }
    }
}

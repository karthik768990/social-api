package com.socialapi;

import com.socialapi.service.RedisGuardrailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency test for the Redis Horizontal Cap guardrail.
 *
 * Fires 200 concurrent threads all trying to increment the bot count for the same post.
 * Only 100 should succeed -- verifying the atomic Lua script works under load.
 */
@SpringBootTest
class HorizontalCapConcurrencyTest {

    @Autowired
    private RedisGuardrailService redisGuardrailService;

    @Test
    void horizontalCap_shouldAllowExactly100BotReplies_under200ConcurrentRequests()
            throws InterruptedException {

        Long testPostId = 99999L; // Use a unique post ID so tests don't interfere

        // Reset any existing count for this post (for test repeatability)
        // In real tests, use @BeforeEach to flush Redis test keys

        int totalRequests = 200;
        int expectedAllowed = 100;

        AtomicInteger allowed  = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(50);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(totalRequests);

        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < totalRequests; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // All threads start simultaneously
                    boolean ok = redisGuardrailService.checkAndIncrementBotCount(testPostId);
                    if (ok) allowed.incrementAndGet();
                    else    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown(); // Release all threads at once
        doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        System.out.printf("Allowed: %d | Rejected: %d%n", allowed.get(), rejected.get());

        assertThat(allowed.get())
            .as("Exactly 100 bot replies should be allowed")
            .isEqualTo(expectedAllowed);

        assertThat(rejected.get())
            .as("Remaining 100 requests should be rejected")
            .isEqualTo(totalRequests - expectedAllowed);
    }
}

package com.socialapi.scheduler;

import com.socialapi.service.RedisGuardrailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NotificationScheduler — CRON Sweeper
 *
 * Runs every 5 minutes (simulating a 15-minute production sweep).
 * Scans Redis for all users with pending notifications, batches them,
 * logs a summarized push notification, and clears the list.
 *
 * This is purely a Redis → Console operation; no DB writes needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final RedisGuardrailService redisGuardrailService;

    /**
     * Runs every 5 minutes: 300,000ms.
     * Cron expression: "0 */5 * * * *""
     */
    @Scheduled(fixedRateString = "300000")
    public void sweepPendingNotifications() {
        log.info("[CRON SWEEPER] Starting pending notification sweep...");

        List<String> pendingKeys = redisGuardrailService.getPendingNotifKeys();

        if (pendingKeys.isEmpty()) {
            log.info("[CRON SWEEPER] No pending notifications found.");
            return;
        }

        log.info("[CRON SWEEPER] Found {} users with pending notifications.", pendingKeys.size());

        for (String pendingKey : pendingKeys) {
            // Extract userId from key pattern "user:{id}:pending_notifs"
            String userId = extractUserId(pendingKey);

            List<String> messages = redisGuardrailService.popAllPendingNotifications(pendingKey);

            if (messages == null || messages.isEmpty()) {
                continue;
            }

            // Build summarized notification
            // First message is the "primary" bot, rest are counted as "[N] others"
            String summary = buildSummary(messages);
            log.info("[NOTIFICATION] Summarized Push Notification to User {}: {}", userId, summary);
        }

        log.info("[CRON SWEEPER] Sweep complete.");
    }

    /**
     * Builds a summary string from a list of notification messages.
     *
     * Example output:
     *   "Bot 'Alpha' and 2 others interacted with your posts."
     */
    private String buildSummary(List<String> messages) {
        if (messages.size() == 1) {
            return messages.get(0);
        }

        // Extract bot name from first message for the "primary" entry
        String first = messages.get(0);
        int others = messages.size() - 1;
        return String.format("%s and [%d] others interacted with your posts.", first, others);
    }

    private String extractUserId(String key) {
        // key format: "user:{id}:pending_notifs"
        String[] parts = key.split(":");
        return parts.length >= 2 ? parts[1] : "unknown";
    }
}

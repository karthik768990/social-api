package com.socialapi.scheduler;

import com.socialapi.service.RedisGuardrailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
// Removed @RequiredArgsConstructor
public class NotificationScheduler {

    private final RedisGuardrailService redisGuardrailService;

    // Added explicit constructor for Spring Dependency Injection
    public NotificationScheduler(RedisGuardrailService redisGuardrailService) {
        this.redisGuardrailService = redisGuardrailService;
    }

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
            String userId = extractUserId(pendingKey);
            List<String> messages = redisGuardrailService.popAllPendingNotifications(pendingKey);

            if (messages == null || messages.isEmpty()) {
                continue;
            }

            String summary = buildSummary(messages);
            log.info("[NOTIFICATION] Summarized Push Notification to User {}: {}", userId, summary);
        }

        log.info("[CRON SWEEPER] Sweep complete.");
    }

    private String buildSummary(List<String> messages) {
        if (messages.size() == 1) {
            return messages.get(0);
        }

        String first = messages.get(0);
        int others = messages.size() - 1;
        return String.format("%s and [%d] others interacted with your posts.", first, others);
    }

    private String extractUserId(String key) {
        String[] parts = key.split(":");
        return parts.length >= 2 ? parts[1] : "unknown";
    }
}
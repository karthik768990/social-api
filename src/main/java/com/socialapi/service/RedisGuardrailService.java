package com.socialapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * RedisGuardrailService
 *
 * All atomic operations use Lua scripts executed via Redis EVAL.
 * Lua scripts run atomically on the Redis server -- no other command can be
 * interleaved between reads and writes within a single script execution.
 *
 * Why Lua scripts and not MULTI/EXEC?
 *   WATCH + MULTI/EXEC uses optimistic locking -- it retries on conflict,
 *   causing thundering-herd problems under 200 concurrent requests.
 *   Lua scripts are single-pass, zero-retry, and guaranteed atomic by the
 *   Redis single-threaded event loop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisGuardrailService {

    private final StringRedisTemplate redisTemplate;

    // Key templates
    private static final String VIRALITY_KEY   = "post:%d:virality_score";
    private static final String BOT_COUNT_KEY  = "post:%d:bot_count";
    private static final String COOLDOWN_KEY   = "cooldown:bot_%d:human_%d";
    private static final String NOTIF_COOLDOWN = "notif_cooldown:user_%d";
    private static final String PENDING_NOTIFS = "user:%d:pending_notifs";
    private static final String PENDING_SCAN   = "user:*:pending_notifs";

    // Virality points
    private static final long BOT_REPLY_POINTS     = 1L;
    private static final long HUMAN_LIKE_POINTS    = 20L;
    private static final long HUMAN_COMMENT_POINTS = 50L;

    // Caps and TTLs
    private static final long HORIZONTAL_CAP   = 100L;
    private static final int  VERTICAL_CAP     = 20;
    private static final long COOLDOWN_TTL_SEC = 600L;
    private static final long NOTIF_TTL_SEC    = 900L;

    public boolean checkAndIncrementBotCount(Long postId) {
        String key = String.format(BOT_COUNT_KEY, postId);
        String lua =
            "local current = tonumber(redis.call('GET', KEYS[1]) or '0')\n" +
            "if current >= tonumber(ARGV[1]) then\n" +
            "  return 0\n" +
            "end\n" +
            "return redis.call('INCR', KEYS[1])";
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(lua, Long.class);
        Long result = redisTemplate.execute(script,
            Collections.singletonList(key), String.valueOf(HORIZONTAL_CAP));
        boolean allowed = result != null && result > 0;
        log.debug("Horizontal cap check for post {}: counter={}, allowed={}", postId, result, allowed);
        return allowed;
    }

    public void decrementBotCount(Long postId) {
        String key = String.format(BOT_COUNT_KEY, postId);
        redisTemplate.opsForValue().decrement(key);
        log.debug("Rolled back bot count for post {}", postId);
    }

    public boolean checkVerticalCap(int depthLevel) {
        return depthLevel <= VERTICAL_CAP;
    }

    public boolean checkAndSetCooldown(Long botId, Long humanId) {
        String key = String.format(COOLDOWN_KEY, botId, humanId);
        String lua =
            "if redis.call('EXISTS', KEYS[1]) == 1 then\n" +
            "  return 0\n" +
            "end\n" +
            "redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])\n" +
            "return 1";
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(lua, Long.class);
        Long result = redisTemplate.execute(script,
            Collections.singletonList(key), String.valueOf(COOLDOWN_TTL_SEC));
        boolean allowed = result != null && result == 1L;
        log.debug("Cooldown check for bot {} -> human {}: allowed={}", botId, humanId, allowed);
        return allowed;
    }

    public void incrementViralityForBotReply(Long postId) {
        incrementVirality(postId, BOT_REPLY_POINTS);
    }

    public void incrementViralityForHumanLike(Long postId) {
        incrementVirality(postId, HUMAN_LIKE_POINTS);
    }

    public void incrementViralityForHumanComment(Long postId) {
        incrementVirality(postId, HUMAN_COMMENT_POINTS);
    }

    private void incrementVirality(Long postId, long points) {
        String key = String.format(VIRALITY_KEY, postId);
        Long newScore = redisTemplate.opsForValue().increment(key, points);
        log.debug("Virality score for post {} -> {}", postId, newScore);
    }

    public Long getViralityScore(Long postId) {
        String key = String.format(VIRALITY_KEY, postId);
        String val = redisTemplate.opsForValue().get(key);
        return val == null ? 0L : Long.parseLong(val);
    }

    public void handleBotNotification(Long userId, Long botId, String botName, Long postId) {
        String cooldownKey = String.format(NOTIF_COOLDOWN, userId);
        String pendingKey  = String.format(PENDING_NOTIFS, userId);
        String lua =
            "if redis.call('EXISTS', KEYS[1]) == 1 then\n" +
            "  return 0\n" +
            "end\n" +
            "redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])\n" +
            "return 1";
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(lua, Long.class);
        Long result = redisTemplate.execute(script,
            Collections.singletonList(cooldownKey), String.valueOf(NOTIF_TTL_SEC));
        if (result != null && result == 1L) {
            log.info("[NOTIFICATION] Push Notification Sent to User {}: Bot '{}' replied to post {}",
                userId, botName, postId);
        } else {
            String message = String.format("Bot '%s' replied to your post (post_id=%d)", botName, postId);
            redisTemplate.opsForList().rightPush(pendingKey, message);
            log.debug("[NOTIFICATION] Queued notification for user {}: {}", userId, message);
        }
    }

    public List<String> getPendingNotifKeys() {
        var keys = redisTemplate.keys(PENDING_SCAN);
        if (keys == null) return Collections.emptyList();
        return keys.stream().filter(k -> k != null).toList();
    }

    public List<String> popAllPendingNotifications(String pendingKey) {
        String lua =
            "local msgs = redis.call('LRANGE', KEYS[1], 0, -1)\n" +
            "redis.call('DEL', KEYS[1])\n" +
            "return msgs";
        DefaultRedisScript<List> script = new DefaultRedisScript<>(lua, List.class);
        List<String> messages = redisTemplate.execute(script, Collections.singletonList(pendingKey));
        return messages == null ? Collections.emptyList() : messages;
    }
}

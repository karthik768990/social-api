package com.socialapi.service;

import com.socialapi.dto.CommentDto;
import com.socialapi.entity.Bot;
import com.socialapi.entity.Comment;
import com.socialapi.entity.Post;
import com.socialapi.exception.GuardrailException;
import com.socialapi.repository.BotRepository;
import com.socialapi.repository.CommentRepository;
import com.socialapi.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final BotRepository botRepository;
    private final RedisGuardrailService redisGuardrailService;

    /**
     * Add a comment to a post.
     *
     * Guardrail flow for Bot comments:
     *  1. Vertical cap check  (depth <= 20)
     *  2. Cooldown cap check  (bot -> human, atomic Lua SET NX EX)
     *  3. Horizontal cap      (post bot count, atomic Lua check+INCR)
     *  4. DB write            (only if all guards pass)
     *  5. Virality score      (Redis INCR)
     *  6. Notification        (smart batch via Redis)
     *
     * On any DB failure after step 3, we DECR the Redis bot count to rollback.
     */
    @Transactional
    public CommentDto.CommentResponse addComment(Long postId, CommentDto.CreateCommentRequest request) {

        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        // Guardrail 1: Vertical Cap
        if (!redisGuardrailService.checkVerticalCap(request.getDepthLevel())) {
            throw new GuardrailException(
                HttpStatus.TOO_MANY_REQUESTS,
                String.format("Vertical cap exceeded: thread depth %d > 20", request.getDepthLevel())
            );
        }

        boolean isBotComment = request.getAuthorType() == Comment.AuthorType.BOT;
        Bot bot = null;

        if (isBotComment) {
            bot = botRepository.findById(request.getAuthorId())
                .orElseThrow(() -> new IllegalArgumentException("Bot not found: " + request.getAuthorId()));

            if (request.getTargetUserId() == null) {
                throw new IllegalArgumentException("targetUserId is required for bot comments");
            }

            // Guardrail 2: Cooldown Cap
            boolean cooldownOk = redisGuardrailService.checkAndSetCooldown(
                request.getAuthorId(), request.getTargetUserId());

            if (!cooldownOk) {
                throw new GuardrailException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    String.format("Cooldown active: bot %d cannot interact with user %d for 10 minutes",
                        request.getAuthorId(), request.getTargetUserId())
                );
            }

            // Guardrail 3: Horizontal Cap (atomic check+increment)
            boolean horizontalOk = redisGuardrailService.checkAndIncrementBotCount(postId);

            if (!horizontalOk) {
                throw new GuardrailException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    String.format("Horizontal cap exceeded: post %d already has 100 bot replies", postId)
                );
            }
        }

        // DB Write
        Comment comment;
        try {
            comment = Comment.builder()
                .postId(postId)
                .authorId(request.getAuthorId())
                .authorType(request.getAuthorType())
                .content(request.getContent())
                .depthLevel(request.getDepthLevel())
                .parentCommentId(request.getParentCommentId())
                .build();

            comment = commentRepository.save(comment);
            log.info("Saved comment {} on post {} by {} ({})",
                comment.getId(), postId, comment.getAuthorId(), comment.getAuthorType());

        } catch (Exception e) {
            if (isBotComment) {
                redisGuardrailService.decrementBotCount(postId);
                log.warn("DB write failed for bot comment on post {}; rolled back Redis counter", postId);
            }
            throw e;
        }

        // Post-write: Virality + Notification
        if (isBotComment) {
            redisGuardrailService.incrementViralityForBotReply(postId);
            if (post.getAuthorType() == Post.AuthorType.USER && request.getTargetUserId() != null) {
                redisGuardrailService.handleBotNotification(
                    request.getTargetUserId(), bot.getId(), bot.getName(), postId);
            }
        } else {
            redisGuardrailService.incrementViralityForHumanComment(postId);
        }

        return toResponse(comment);
    }

    private CommentDto.CommentResponse toResponse(Comment comment) {
        return CommentDto.CommentResponse.builder()
            .id(comment.getId())
            .postId(comment.getPostId())
            .authorId(comment.getAuthorId())
            .authorType(comment.getAuthorType())
            .content(comment.getContent())
            .depthLevel(comment.getDepthLevel())
            .parentCommentId(comment.getParentCommentId())
            .createdAt(comment.getCreatedAt() != null ? comment.getCreatedAt().toString() : null)
            .build();
    }
}

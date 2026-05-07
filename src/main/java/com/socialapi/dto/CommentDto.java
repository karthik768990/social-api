package com.socialapi.dto;

import com.socialapi.entity.Comment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CommentDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateCommentRequest {
        @NotNull(message = "authorId is required")
        private Long authorId;

        @NotNull(message = "authorType is required")
        private Comment.AuthorType authorType;

        @NotBlank(message = "content is required")
        private String content;

        @Min(value = 0, message = "depthLevel must be >= 0")
        private int depthLevel;

        // Optional: parent comment id for threaded replies
        private Long parentCommentId;

        // Required when authorType = BOT: the human user whose post is being interacted with
        private Long targetUserId;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommentResponse {
        private Long id;
        private Long postId;
        private Long authorId;
        private Comment.AuthorType authorType;
        private String content;
        private int depthLevel;
        private Long parentCommentId;
        private String createdAt;
    }
}

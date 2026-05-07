package com.socialapi.dto;

import com.socialapi.entity.Post;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PostDto {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatePostRequest {
        @NotNull(message = "authorId is required")
        private Long authorId;

        @NotNull(message = "authorType is required")
        private Post.AuthorType authorType;

        @NotBlank(message = "content is required")
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PostResponse {
        private Long id;
        private Long authorId;
        private Post.AuthorType authorType;
        private String content;
        private String createdAt;
        private int likeCount;
        private Long viralityScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LikeRequest {
        @NotNull(message = "userId is required")
        private Long userId;
    }
}

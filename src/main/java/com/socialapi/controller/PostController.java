package com.socialapi.controller;

import com.socialapi.dto.ApiResponse;
import com.socialapi.dto.CommentDto;
import com.socialapi.dto.PostDto;
import com.socialapi.service.CommentService;
import com.socialapi.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final CommentService commentService;

    /**
     * POST /api/posts
     * Create a new post (by a User or Bot).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<PostDto.PostResponse>> createPost(
            @Valid @RequestBody PostDto.CreatePostRequest request) {
        PostDto.PostResponse response = postService.createPost(request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Post created successfully", response));
    }

    /**
     * GET /api/posts/{postId}
     * Get a post with its current virality score.
     */
    @GetMapping("/{postId}")
    public ResponseEntity<ApiResponse<PostDto.PostResponse>> getPost(@PathVariable Long postId) {
        PostDto.PostResponse response = postService.getPost(postId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * POST /api/posts/{postId}/comments
     * Add a comment to a post.
     * For bot comments, all Redis guardrails are applied atomically.
     */
    @PostMapping("/{postId}/comments")
    public ResponseEntity<ApiResponse<CommentDto.CommentResponse>> addComment(
            @PathVariable Long postId,
            @Valid @RequestBody CommentDto.CreateCommentRequest request) {
        CommentDto.CommentResponse response = commentService.addComment(postId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok("Comment added successfully", response));
    }

    /**
     * POST /api/posts/{postId}/like
     * Like a post (human user only).
     * Updates virality score in Redis (+20 points).
     */
    @PostMapping("/{postId}/like")
    public ResponseEntity<ApiResponse<PostDto.PostResponse>> likePost(
            @PathVariable Long postId,
            @Valid @RequestBody PostDto.LikeRequest request) {
        PostDto.PostResponse response = postService.likePost(postId, request.getUserId());
        return ResponseEntity.ok(ApiResponse.ok("Post liked successfully", response));
    }
}

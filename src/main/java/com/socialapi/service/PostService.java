package com.socialapi.service;

import com.socialapi.dto.PostDto;
import com.socialapi.entity.Post;
import com.socialapi.entity.User;
import com.socialapi.repository.PostRepository;
import com.socialapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final RedisGuardrailService redisGuardrailService;

    @Transactional
    public PostDto.PostResponse createPost(PostDto.CreatePostRequest request) {
        Post post = Post.builder()
            .authorId(request.getAuthorId())
            .authorType(request.getAuthorType())
            .content(request.getContent())
            .likeCount(0)
            .build();

        Post saved = postRepository.save(post);
        log.info("Created post {} by {} ({})", saved.getId(), saved.getAuthorId(), saved.getAuthorType());
        return toResponse(saved);
    }

    @Transactional
    public PostDto.PostResponse likePost(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));

        // Increment virality score in Redis (Human Like = +20)
        redisGuardrailService.incrementViralityForHumanLike(postId);

        // Increment like count in DB
        post.setLikeCount(post.getLikeCount() + 1);
        Post saved = postRepository.save(post);

        log.info("User {} liked post {}. Virality +20", userId, postId);
        return toResponse(saved);
    }

    public PostDto.PostResponse getPost(Long postId) {
        Post post = postRepository.findById(postId)
            .orElseThrow(() -> new IllegalArgumentException("Post not found: " + postId));
        return toResponse(post);
    }

    private PostDto.PostResponse toResponse(Post post) {
        return PostDto.PostResponse.builder()
            .id(post.getId())
            .authorId(post.getAuthorId())
            .authorType(post.getAuthorType())
            .content(post.getContent())
            .createdAt(post.getCreatedAt() != null ? post.getCreatedAt().toString() : null)
            .likeCount(post.getLikeCount())
            .viralityScore(redisGuardrailService.getViralityScore(post.getId()))
            .build();
    }
}

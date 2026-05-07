package com.socialapi.controller;

import com.socialapi.dto.ApiResponse;
import com.socialapi.entity.Bot;
import com.socialapi.entity.User;
import com.socialapi.repository.BotRepository;
import com.socialapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class UserController {

    private final UserRepository userRepository;
    private final BotRepository botRepository;

    @PostMapping("/users")
    public ResponseEntity<ApiResponse<User>> createUser(@RequestBody User user) {
        User saved = userRepository.save(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(saved));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponse<User>> getUser(@PathVariable Long id) {
        return userRepository.findById(id)
            .map(u -> ResponseEntity.ok(ApiResponse.ok(u)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/bots")
    public ResponseEntity<ApiResponse<Bot>> createBot(@RequestBody Bot bot) {
        Bot saved = botRepository.save(bot);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(saved));
    }

    @GetMapping("/bots/{id}")
    public ResponseEntity<ApiResponse<Bot>> getBot(@PathVariable Long id) {
        return botRepository.findById(id)
            .map(b -> ResponseEntity.ok(ApiResponse.ok(b)))
            .orElse(ResponseEntity.notFound().build());
    }
}

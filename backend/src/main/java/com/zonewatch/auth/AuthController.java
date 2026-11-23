package com.zonewatch.auth;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public AuthService.LoginResult login(@RequestBody LoginRequest request) {
        return auth.login(request.username(), request.password());
    }
}

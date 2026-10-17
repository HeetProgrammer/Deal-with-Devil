package org.example.deal_with_devil_csa.web.controller;

import org.example.deal_with_devil_csa.service.AuthService;
import org.example.deal_with_devil_csa.web.dto.AuthResponse;
import org.example.deal_with_devil_csa.web.dto.GoogleLoginRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController
{
    private final AuthService authService;

    /** The React app sends the Google ID token here and gets our own JWT back. */
    @PostMapping("/google")
    public AuthResponse google(@Valid @RequestBody GoogleLoginRequest request)
    {
        return authService.loginWithGoogle(request.idToken());
    }
}

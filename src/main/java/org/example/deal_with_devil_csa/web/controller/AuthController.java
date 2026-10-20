package org.example.deal_with_devil_csa.web.controller;

import org.example.deal_with_devil_csa.security.CurrentUser;
import org.example.deal_with_devil_csa.service.AuthService;
import org.example.deal_with_devil_csa.web.dto.AdminFallbackRequest;
import org.example.deal_with_devil_csa.web.dto.AuthResponse;
import org.example.deal_with_devil_csa.web.dto.GoogleLoginRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    /** Backup admin sign-in for when Google sign-in is unavailable. */
    @PostMapping("/admin-fallback")
    public AuthResponse adminFallback(@Valid @RequestBody AdminFallbackRequest request)
    {
        return authService.loginWithFallback(request.email(), request.passcode());
    }

    /** A fresh token for the signed-in user. Needs a still-valid token. */
    @PostMapping("/refresh")
    public AuthResponse refresh(@AuthenticationPrincipal CurrentUser me)
    {
        return authService.refresh(me.id());
    }
}

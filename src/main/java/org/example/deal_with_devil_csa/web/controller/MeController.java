package org.example.deal_with_devil_csa.web.controller;

import org.example.deal_with_devil_csa.security.CurrentUser;
import org.example.deal_with_devil_csa.service.UserService;
import org.example.deal_with_devil_csa.web.dto.MeResponse;
import org.example.deal_with_devil_csa.web.dto.ProfileRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController
{
    private final UserService userService;

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal CurrentUser me)
    {
        return userService.getMe(me.id());
    }

    @PutMapping("/profile")
    public MeResponse updateProfile(@AuthenticationPrincipal CurrentUser me,
                                    @Valid @RequestBody ProfileRequest request)
    {
        return userService.updateProfile(me.id(), request);
    }
}

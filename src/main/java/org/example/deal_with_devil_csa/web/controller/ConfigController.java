package org.example.deal_with_devil_csa.web.controller;

import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.web.dto.GameConfigResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** The game numbers (prices, rewards, devil amounts, corruption range) so the frontend never hard-codes them. */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController
{
    private final GameProperties gameProperties;

    @GetMapping
    public GameConfigResponse config()
    {
        return GameConfigResponse.from(gameProperties);
    }
}

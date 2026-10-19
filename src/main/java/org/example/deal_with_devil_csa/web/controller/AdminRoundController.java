package org.example.deal_with_devil_csa.web.controller;

import org.example.deal_with_devil_csa.service.RoundService;
import org.example.deal_with_devil_csa.web.dto.RoundResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Round and phase controls. /api/admin/** is already restricted to ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/admin/rounds")
@RequiredArgsConstructor
public class AdminRoundController
{
    private final RoundService roundService;

    /** Start round 1, or the next round once the current one has reached RESULTS. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoundResponse start()
    {
        return roundService.start();
    }

    @PostMapping("/current/advance")
    public RoundResponse advance()
    {
        return roundService.advance();
    }

    @PostMapping("/current/pause")
    public RoundResponse pause()
    {
        return roundService.pause();
    }

    @PostMapping("/current/resume")
    public RoundResponse resume()
    {
        return roundService.resume();
    }
}

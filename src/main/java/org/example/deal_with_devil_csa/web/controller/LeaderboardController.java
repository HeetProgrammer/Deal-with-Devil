package org.example.deal_with_devil_csa.web.controller;

import java.util.List;

import org.example.deal_with_devil_csa.service.LeaderboardService;
import org.example.deal_with_devil_csa.web.dto.LeaderboardEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
public class LeaderboardController
{
    private final LeaderboardService leaderboardService;

    /** Participants ranked by coins, highest first. */
    @GetMapping
    public List<LeaderboardEntry> leaderboard()
    {
        return leaderboardService.get();
    }
}

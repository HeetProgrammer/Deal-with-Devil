package org.example.deal_with_devil_csa.web.controller;

import java.util.List;

import org.example.deal_with_devil_csa.service.ProblemService;
import org.example.deal_with_devil_csa.service.RoundService;
import org.example.deal_with_devil_csa.web.dto.ProblemPreviewResponse;
import org.example.deal_with_devil_csa.web.dto.RoundResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** What a participant can see about the current round. Returns ROUND_NOT_FOUND (404) before the first round starts. */
@RestController
@RequestMapping("/api/rounds")
@RequiredArgsConstructor
public class RoundController
{
    private final RoundService roundService;
    private final ProblemService problemService;

    @GetMapping("/current")
    public RoundResponse current()
    {
        return roundService.getCurrent();
    }

    /** Title, category, tier, teaser, slots and reserve price. No link, hints or passwords. */
    @GetMapping("/current/problems")
    public List<ProblemPreviewResponse> currentProblems()
    {
        return problemService.currentRoundPreview();
    }
}

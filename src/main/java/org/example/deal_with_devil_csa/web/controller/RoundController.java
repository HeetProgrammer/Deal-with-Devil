package org.example.deal_with_devil_csa.web.controller;

import java.util.List;

import org.example.deal_with_devil_csa.security.CurrentUser;
import org.example.deal_with_devil_csa.service.BidService;
import org.example.deal_with_devil_csa.service.ProblemService;
import org.example.deal_with_devil_csa.service.RoundService;
import org.example.deal_with_devil_csa.web.dto.BidRequest;
import org.example.deal_with_devil_csa.web.dto.BidResponse;
import org.example.deal_with_devil_csa.web.dto.ProblemPreviewResponse;
import org.example.deal_with_devil_csa.web.dto.RoundResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** What a participant can see and do in the current round. Returns ROUND_NOT_FOUND (404) before the first round starts. */
@RestController
@RequestMapping("/api/rounds")
@RequiredArgsConstructor
public class RoundController
{
    private final RoundService roundService;
    private final ProblemService problemService;
    private final BidService bidService;

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

    /** Places or changes the participant's sealed bid on a problem of the current round. Participants only (checked in BidService). */
    @PutMapping("/current/problems/{problemId}/bid")
    public BidResponse placeBid(@AuthenticationPrincipal CurrentUser me,
                                @PathVariable Long problemId,
                                @Valid @RequestBody BidRequest request)
    {
        return bidService.placeBid(me.id(), problemId, request.amount());
    }
}

package org.example.deal_with_devil_csa.web.controller;

import org.example.deal_with_devil_csa.service.RoundService;
import org.example.deal_with_devil_csa.service.SettlementService;
import org.example.deal_with_devil_csa.web.dto.BiddingDeadlineRequest;
import org.example.deal_with_devil_csa.web.dto.RoundResponse;
import org.example.deal_with_devil_csa.web.dto.SettlementSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Round, phase and auction controls. /api/admin/** is already restricted to ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/admin/rounds")
@RequiredArgsConstructor
public class AdminRoundController
{
    private final RoundService roundService;
    private final SettlementService settlementService;

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

    /** Sets the bidding deadline, or clears it when the body's deadline is null. */
    @PutMapping("/current/bidding-deadline")
    public RoundResponse setBiddingDeadline(@RequestBody BiddingDeadlineRequest request)
    {
        return roundService.setBiddingDeadline(request.deadline());
    }

    /** Runs the Vickrey settlement (only in the SETTLEMENT phase) and moves the round to SOLVING. */
    @PostMapping("/current/settle")
    public SettlementSummaryResponse settle()
    {
        return settlementService.settle();
    }

    /** The settlement summary again, after the round has been settled. */
    @GetMapping("/current/settlement")
    public SettlementSummaryResponse settlement()
    {
        return settlementService.currentSummary();
    }
}

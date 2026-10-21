package org.example.deal_with_devil_csa.web.controller;

import org.example.deal_with_devil_csa.security.CurrentUser;
import org.example.deal_with_devil_csa.service.ProblemService;
import org.example.deal_with_devil_csa.service.RoundAllocationService;
import org.example.deal_with_devil_csa.service.RoundService;
import org.example.deal_with_devil_csa.service.SettlementService;
import org.example.deal_with_devil_csa.web.dto.AllocationViewResponse;
import org.example.deal_with_devil_csa.web.dto.AutoFillResponse;
import org.example.deal_with_devil_csa.web.dto.AutoSlotsResponse;
import org.example.deal_with_devil_csa.web.dto.BidCoverageResponse;
import org.example.deal_with_devil_csa.web.dto.BiddingDeadlineRequest;
import org.example.deal_with_devil_csa.web.dto.ManualAssignmentRequest;
import org.example.deal_with_devil_csa.web.dto.ManualAssignmentResponse;
import org.example.deal_with_devil_csa.web.dto.RoundResponse;
import org.example.deal_with_devil_csa.web.dto.SettlementSummaryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Round, phase and auction controls. /api/admin/** is already restricted to ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/admin/rounds")
@RequiredArgsConstructor
public class AdminRoundController
{
    private final RoundService roundService;
    private final SettlementService settlementService;
    private final ProblemService problemService;
    private final RoundAllocationService roundAllocationService;

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

    /**
     * The auto slots button: sets every problem's slots so the total is exactly 3 x participants, spread evenly.
     * Only in PREVIEW. Safe to press again whenever someone new has signed in. With dryRun=true nothing is saved.
     * dryRun is required on purpose, so a missing parameter can never silently mean "apply" or "preview".
     */
    @PostMapping("/current/slots/auto")
    public AutoSlotsResponse autoSlots(@RequestParam boolean dryRun)
    {
        return problemService.autoSlots(dryRun);
    }

    /** Runs the settlement (one global allocation with the wins-per-round cap) and moves the round to SOLVING. */
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

    // ---- Exactly wins-per-round problems per person (Phase 9) ----

    /** Before bidding closes: every participant and how many problems of the round they have bid on (fewest first). */
    @GetMapping("/current/bid-coverage")
    public BidCoverageResponse bidCoverage()
    {
        return roundAllocationService.bidCoverage();
    }

    /** After settlement: every participant's number of problems (anyone not at the target is flagged) and unsold slots. */
    @GetMapping("/current/allocation")
    public AllocationViewResponse allocation()
    {
        return roundAllocationService.allocationView();
    }

    /**
     * The auto fill button: tops up everybody who has fewer than wins-per-round problems from the unsold slots, at the
     * reserve price. Only in SOLVING. Safe to press again. dryRun is required on purpose (see autoSlots), so a missing
     * parameter can never silently mean "apply".
     */
    @PostMapping("/current/auto-fill")
    public AutoFillResponse autoFill(@AuthenticationPrincipal CurrentUser admin, @RequestParam boolean dryRun)
    {
        return roundAllocationService.autoFill(admin.id(), dryRun);
    }

    /** Gives one specific problem to one participant at its reserve price, for whoever auto fill could not finish. */
    @PostMapping("/current/assignments")
    public ManualAssignmentResponse assign(@AuthenticationPrincipal CurrentUser admin,
                                           @Valid @RequestBody ManualAssignmentRequest request)
    {
        return roundAllocationService.assign(admin.id(), request);
    }
}

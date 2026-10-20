package org.example.deal_with_devil_csa.web.controller;

import java.util.List;

import org.example.deal_with_devil_csa.security.CurrentUser;
import org.example.deal_with_devil_csa.service.BidService;
import org.example.deal_with_devil_csa.service.EventService;
import org.example.deal_with_devil_csa.service.UserService;
import org.example.deal_with_devil_csa.service.WalletService;
import org.example.deal_with_devil_csa.web.dto.BidResponse;
import org.example.deal_with_devil_csa.web.dto.EventResponse;
import org.example.deal_with_devil_csa.web.dto.LedgerEntryResponse;
import org.example.deal_with_devil_csa.web.dto.MeResponse;
import org.example.deal_with_devil_csa.web.dto.ProfileRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController
{
    private final UserService userService;
    private final WalletService walletService;
    private final BidService bidService;
    private final EventService eventService;

    @GetMapping
    public MeResponse me(@AuthenticationPrincipal CurrentUser me)
    {
        return userService.getMe(me.id());
    }

    /** The participant's own coin history, newest first. */
    @GetMapping("/history")
    public List<LedgerEntryResponse> history(@AuthenticationPrincipal CurrentUser me)
    {
        return walletService.history(me.id()).stream().map(LedgerEntryResponse::from).toList();
    }

    /** The participant's own bids in the current round, with results (and the HackerRank link for won bids) after settlement. */
    @GetMapping("/bids")
    public List<BidResponse> bids(@AuthenticationPrincipal CurrentUser me)
    {
        return bidService.myBids(me.id());
    }

    /**
     * Notifications for the signed-in user (personal events plus broadcasts), oldest first.
     * Without "after" it returns the latest few to fill the notification list; with "after" only newer events.
     */
    @GetMapping("/events")
    public List<EventResponse> events(@AuthenticationPrincipal CurrentUser me,
                                      @RequestParam(required = false) Long after,
                                      @RequestParam(required = false) Integer limit)
    {
        return eventService.feed(me.id(), after, limit);
    }

    @PutMapping("/profile")
    public MeResponse updateProfile(@AuthenticationPrincipal CurrentUser me,
                                    @Valid @RequestBody ProfileRequest request)
    {
        return userService.updateProfile(me.id(), request);
    }
}

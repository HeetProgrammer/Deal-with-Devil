package org.example.deal_with_devil_csa.web.dto;

import java.time.Instant;

import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;

/**
 * The current round as the frontend sees it (participant app and admin panel).
 * biddingDeadline is null when no deadline is set. serverTime is "now" on the server, so the frontend can
 * correct for a wrong clock on the participant's device when it counts down to the deadline.
 */
public record RoundResponse(Long id, int number, RoundPhase phase, boolean paused, Instant phaseChangedAt,
                            Instant biddingDeadline, Instant serverTime)
{
    public static RoundResponse from(Round round)
    {
        return new RoundResponse(round.getId(), round.getNumber(), round.getPhase(), round.isPaused(),
                round.getPhaseChangedAt(), round.getBiddingDeadline(), Instant.now());
    }
}

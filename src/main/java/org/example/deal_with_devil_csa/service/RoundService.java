package org.example.deal_with_devil_csa.service;

import java.time.Instant;
import java.util.Map;

import org.example.deal_with_devil_csa.domain.EventType;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.web.dto.RoundResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The round state machine. The current round is always the latest one. Phases only move forward
 * (PREVIEW, BIDDING, SETTLEMENT, SOLVING, RESULTS) and only through the admin; after RESULTS the admin
 * starts a new round. Every change is saved with a flush so a concurrent change by another admin
 * (Round has an optimistic-lock @Version) fails right here as CONCURRENT_UPDATE.
 *
 * advance() covers every step except SETTLEMENT to SOLVING: that step happens only by running settlement
 * (SettlementService), so bids can never be skipped over. Starting a round and every phase change also
 * publish a broadcast notification.
 */
@Service
@RequiredArgsConstructor
public class RoundService
{
    private final RoundRepository roundRepository;
    private final ProblemRepository problemRepository;
    private final EventService eventService;

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public RoundResponse getCurrent()
    {
        return RoundResponse.from(latest());
    }

    /** Creates round 1, or round N+1 once the latest round has reached RESULTS. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RoundResponse start()
    {
        Round latest = roundRepository.findTopByOrderByNumberDesc().orElse(null);
        int number = 1;
        if (latest != null)
        {
            if (latest.getPhase() != RoundPhase.RESULTS)
            {
                throw new ApiException(ErrorCode.INVALID_PHASE,
                        "Round " + latest.getNumber() + " is still in progress. Finish it before starting a new round.");
            }
            number = latest.getNumber() + 1;
        }

        Round round = new Round();
        round.setNumber(number);
        Round saved = roundRepository.saveAndFlush(round);
        eventService.broadcast(EventType.ROUND_STARTED, "Round " + number + " has started.");
        return RoundResponse.from(saved);
    }

    /** Moves the current round to its next phase. Blocked while paused, and blocked in SETTLEMENT (run settlement instead). */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RoundResponse advance()
    {
        Round round = latest();
        if (round.isPaused())
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "The round is paused. Resume it before advancing.");
        }

        RoundPhase next = round.getPhase().next();
        if (next == null)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "The round is finished. Start a new round instead.");
        }
        if (round.getPhase() == RoundPhase.SETTLEMENT)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "Run settlement to move on from the settlement phase.");
        }
        if (round.getPhase() == RoundPhase.PREVIEW && problemRepository.countByRoundId(round.getId()) == 0)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "Add at least one problem before opening bidding.");
        }

        round.setPhase(next);
        round.setPhaseChangedAt(Instant.now());
        Round saved = roundRepository.saveAndFlush(round);
        eventService.broadcast(EventType.PHASE_CHANGED,
                "Round " + round.getNumber() + ": " + next.getLabel() + " has started.");
        return RoundResponse.from(saved);
    }

    /** Freezes the current round. Pausing an already paused round changes nothing. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RoundResponse pause()
    {
        return setPaused(true);
    }

    /** Unfreezes the current round. Resuming a round that is not paused changes nothing. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RoundResponse resume()
    {
        return setPaused(false);
    }

    /**
     * Sets or clears (null) the bidding deadline. Only possible before bidding closes. A deadline must lie in the
     * future; to stop bidding right now the admin advances the phase instead.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public RoundResponse setBiddingDeadline(Instant deadline)
    {
        Round round = latest();
        if (round.getPhase() != RoundPhase.PREVIEW && round.getPhase() != RoundPhase.BIDDING)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "The deadline can only be changed before bidding closes.");
        }
        if (deadline != null && !deadline.isAfter(Instant.now()))
        {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, Map.of("deadline", "Must be in the future."));
        }

        round.setBiddingDeadline(deadline);
        return RoundResponse.from(roundRepository.saveAndFlush(round));
    }

    // ---- Internals ----

    private RoundResponse setPaused(boolean paused)
    {
        Round round = latest();
        if (round.isPaused() != paused)
        {
            round.setPaused(paused);
            roundRepository.saveAndFlush(round);
        }
        return RoundResponse.from(round);
    }

    private Round latest()
    {
        return roundRepository.findTopByOrderByNumberDesc()
                .orElseThrow(() -> new ApiException(ErrorCode.ROUND_NOT_FOUND, "No round has been started yet."));
    }
}

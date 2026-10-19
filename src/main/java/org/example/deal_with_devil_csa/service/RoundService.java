package org.example.deal_with_devil_csa.service;

import java.time.Instant;

import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.web.dto.RoundResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The round state machine. The current round is always the latest one. Phases only move forward
 * (PREVIEW, BIDDING, SETTLEMENT, SOLVING, RESULTS) and only through advance(); after RESULTS the admin
 * starts a new round. Every change is saved with a flush so a concurrent change by another admin
 * (Round has an optimistic-lock @Version) fails right here as CONCURRENT_UPDATE.
 *
 * From Phase 5 the BIDDING to SETTLEMENT and SETTLEMENT to SOLVING steps will be driven by "close bidding"
 * and "run settlement"; plain advance() is the manual override until then.
 */
@Service
@RequiredArgsConstructor
public class RoundService
{
    private final RoundRepository roundRepository;
    private final ProblemRepository problemRepository;

    @Transactional(readOnly = true)
    public RoundResponse getCurrent()
    {
        return RoundResponse.from(latest());
    }

    /** Creates round 1, or round N+1 once the latest round has reached RESULTS. */
    @Transactional
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
        return RoundResponse.from(roundRepository.saveAndFlush(round));
    }

    /** Moves the current round to its next phase. Blocked while paused. */
    @Transactional
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
        if (round.getPhase() == RoundPhase.PREVIEW && problemRepository.countByRoundId(round.getId()) == 0)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "Add at least one problem before opening bidding.");
        }

        round.setPhase(next);
        round.setPhaseChangedAt(Instant.now());
        return RoundResponse.from(roundRepository.saveAndFlush(round));
    }

    /** Freezes the current round. Pausing an already paused round changes nothing. */
    @Transactional
    public RoundResponse pause()
    {
        return setPaused(true);
    }

    /** Unfreezes the current round. Resuming a round that is not paused changes nothing. */
    @Transactional
    public RoundResponse resume()
    {
        return setPaused(false);
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

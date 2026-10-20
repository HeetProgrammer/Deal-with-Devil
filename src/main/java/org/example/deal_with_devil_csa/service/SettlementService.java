package org.example.deal_with_devil_csa.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.BidStatus;
import org.example.deal_with_devil_csa.domain.EventType;
import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.LedgerType;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.BidRepository;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.service.VickreyCalculator.BidEntry;
import org.example.deal_with_devil_csa.service.VickreyCalculator.Outcome;
import org.example.deal_with_devil_csa.web.dto.SettlementSummaryResponse;
import org.example.deal_with_devil_csa.web.dto.SettlementSummaryResponse.ProblemResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Runs the Vickrey settlement for the current round, once, in one transaction: if anything fails, no coin
 * moves and no bid changes status.
 *
 * Per problem (in id order): bids below the reserve price are not eligible; the top "slots" eligible bids win
 * and each pays the clearing price (see VickreyCalculator). A winner whose balance no longer covers the price
 * (an admin may have deducted coins since they bid) is marked UNAFFORDABLE and the slot is not reassigned.
 * Every other bid is LOST. Each bidder gets a personal notification, the round moves to SOLVING and everyone
 * gets a broadcast.
 *
 * The summary is always recomputed from the stored bids, so it can be read again later and always matches.
 */
@Service
@RequiredArgsConstructor
public class SettlementService
{
    private final RoundRepository roundRepository;
    private final ProblemRepository problemRepository;
    private final BidRepository bidRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final EventService eventService;

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public SettlementSummaryResponse settle()
    {
        Round round = latest();
        if (round.getPhase() == RoundPhase.SOLVING || round.getPhase() == RoundPhase.RESULTS)
        {
            throw new ApiException(ErrorCode.ALREADY_SETTLED);
        }
        if (round.getPhase() != RoundPhase.SETTLEMENT)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "Close bidding before running settlement.");
        }
        if (round.isPaused())
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "The round is paused. Resume it before running settlement.");
        }

        List<Problem> problems = problemRepository.findByRoundIdOrderByIdAsc(round.getId());
        Map<Long, List<Bid>> pendingByProblem = bidRepository.findByRoundAndStatus(round.getId(), BidStatus.PENDING)
                .stream()
                .collect(Collectors.groupingBy(b -> b.getProblem().getId()));

        for (Problem problem : problems)
        {
            settleProblem(problem, pendingByProblem.getOrDefault(problem.getId(), List.of()));
        }

        round.setPhase(RoundPhase.SOLVING);
        round.setPhaseChangedAt(Instant.now());
        roundRepository.saveAndFlush(round);
        eventService.broadcast(EventType.PHASE_CHANGED,
                "Round " + round.getNumber() + ": " + RoundPhase.SOLVING.getLabel() + " has started.");

        return buildSummary(round);
    }

    /** The settlement summary of the current round. Only available once the round has been settled. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public SettlementSummaryResponse currentSummary()
    {
        Round round = latest();
        if (round.getPhase() != RoundPhase.SOLVING && round.getPhase() != RoundPhase.RESULTS)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "This round has not been settled yet.");
        }
        return buildSummary(round);
    }

    // ---- Settlement of one problem ----

    private void settleProblem(Problem problem, List<Bid> pendingBids)
    {
        List<Bid> eligible = pendingBids.stream()
                .filter(b -> b.getAmount() >= problem.getBasePrice())
                .toList();
        Map<Long, Bid> bidsById = eligible.stream().collect(Collectors.toMap(Bid::getId, Function.identity()));

        Outcome outcome = VickreyCalculator.resolve(toEntries(eligible), problem.getSlots(), problem.getBasePrice());

        Set<Long> handled = new HashSet<>();
        for (BidEntry winner : outcome.winners())
        {
            Bid bid = bidsById.get(winner.bidId());
            chargeWinner(problem, bid, outcome.price());
            handled.add(bid.getId());
        }

        for (Bid bid : pendingBids)
        {
            if (!handled.contains(bid.getId()))
            {
                bid.setStatus(BidStatus.LOST);
                eventService.personal(bid.getUser().getId(), EventType.BID_LOST,
                        "You did not win \"" + problem.getTitle() + "\".", null, null, null, problem.getId());
            }
        }
    }

    /** Takes the clearing price from a winner, or marks them UNAFFORDABLE if their balance no longer covers it. */
    private void chargeWinner(Problem problem, Bid bid, long price)
    {
        Long userId = bid.getUser().getId();
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        // Checked up front (rather than catching INSUFFICIENT_BALANCE) because an exception passing through
        // WalletService would mark this whole settlement transaction rollback-only.
        if (wallet.getBalance() < price)
        {
            bid.setStatus(BidStatus.UNAFFORDABLE);
            eventService.personal(userId, EventType.BID_UNAFFORDABLE,
                    "You would have won \"" + problem.getTitle() + "\" for " + price
                            + " coins, but you no longer had enough coins.",
                    null, wallet.getBalance(), null, problem.getId());
            return;
        }

        long balanceAfter = wallet.getBalance();
        if (price > 0)
        {
            LedgerEntry entry = walletService.deduct(userId, price, LedgerType.BID_SETTLEMENT,
                    "Won \"" + problem.getTitle() + "\" at " + price + " coins", null);
            balanceAfter = entry.getBalanceAfter();
        }

        bid.setStatus(BidStatus.WON);
        bid.setPricePaid(price);
        eventService.personal(userId, EventType.BID_WON,
                "You won \"" + problem.getTitle() + "\" and paid " + price + " coins.",
                -price, balanceAfter, null, problem.getId());
    }

    // ---- Summary ----

    private SettlementSummaryResponse buildSummary(Round round)
    {
        List<Problem> problems = problemRepository.findByRoundIdOrderByIdAsc(round.getId());
        Map<Long, List<Bid>> bidsByProblem = bidRepository.findAllByRound(round.getId()).stream()
                .collect(Collectors.groupingBy(b -> b.getProblem().getId()));

        long totalCollected = 0;
        List<ProblemResult> results = new ArrayList<>();
        for (Problem problem : problems)
        {
            List<Bid> bids = bidsByProblem.getOrDefault(problem.getId(), List.of());
            int winners = 0;
            int unaffordable = 0;
            for (Bid bid : bids)
            {
                if (bid.getStatus() == BidStatus.WON)
                {
                    winners++;
                    totalCollected += bid.getPricePaid() == null ? 0L : bid.getPricePaid();
                }
                else if (bid.getStatus() == BidStatus.UNAFFORDABLE)
                {
                    unaffordable++;
                }
            }

            List<Bid> eligible = bids.stream().filter(b -> b.getAmount() >= problem.getBasePrice()).toList();
            long clearingPrice = VickreyCalculator
                    .resolve(toEntries(eligible), problem.getSlots(), problem.getBasePrice()).price();

            results.add(new ProblemResult(problem.getId(), problem.getTitle(), bids.size(), winners, unaffordable,
                    clearingPrice));
        }
        return new SettlementSummaryResponse(round.getId(), round.getNumber(), totalCollected, results);
    }

    // ---- Internals ----

    private static List<BidEntry> toEntries(List<Bid> bids)
    {
        return bids.stream()
                .map(b -> new BidEntry(b.getId(), b.getUser().getId(), b.getAmount(), b.getSubmittedAt()))
                .toList();
    }

    private Round latest()
    {
        return roundRepository.findTopByOrderByNumberDesc()
                .orElseThrow(() -> new ApiException(ErrorCode.ROUND_NOT_FOUND, "No round has been started yet."));
    }
}

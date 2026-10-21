package org.example.deal_with_devil_csa.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.BidStatus;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.BidRepository;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.web.dto.BidResponse;
import org.example.deal_with_devil_csa.web.dto.WinnerResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Sealed bids. A participant has at most one bid per problem of the current round and can change it while
 * bidding is open. Rules enforced here: the round must be in BIDDING, not paused and before its deadline; the
 * problem must belong to the current round; the bid must reach the problem's reserve price; and the
 * participant's highest bids (as many as they can win per round, game.wins-per-round) must together not exceed
 * their coins (coins are only taken at settlement,
 * but a participant cannot promise more than they have).
 *
 * Nothing here ever returns another participant's bid.
 */
@Service
@RequiredArgsConstructor
public class BidService
{
    private final RoundRepository roundRepository;
    private final ProblemRepository problemRepository;
    private final BidRepository bidRepository;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final GameProperties gameProperties;

    /** Places a new bid or replaces the amount of an existing one. */
    @Transactional
    @PreAuthorize("hasRole('PARTICIPANT') and @guard.isSelf(#userId)")
    public BidResponse placeBid(Long userId, Long problemId, long amount)
    {
        // Lock this participant's wallet first: it serialises their own requests, so two quick bids cannot
        // both pass the balance check below.
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        Round round = roundRepository.findTopByOrderByNumberDesc()
                .orElseThrow(() -> new ApiException(ErrorCode.ROUND_NOT_FOUND, "No round has been started yet."));
        requireBiddingOpen(round);

        Problem problem = problemRepository.findById(problemId)
                .filter(p -> p.getRound() != null && p.getRound().getId().equals(round.getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.PROBLEM_NOT_FOUND));

        if (amount < problem.getBasePrice())
        {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                    Map.of("amount", "Must be at least the reserve price of " + problem.getBasePrice() + " coins."));
        }

        List<Bid> mine = bidRepository.findByUserAndRound(userId, round.getId());
        Bid existing = mine.stream()
                .filter(b -> b.getProblem().getId().equals(problemId))
                .findFirst()
                .orElse(null);

        // A participant wins at most game.wins-per-round problems, so at most their highest bids can ever be
        // charged. Only those have to fit in the balance; lower bids are free to exceed it.
        List<Long> promised = new ArrayList<>();
        promised.add(amount);
        for (Bid other : mine)
        {
            if (other != existing)
            {
                promised.add(other.getAmount());
            }
        }
        int wins = gameProperties.winsPerRound();
        long mostYouCouldOwe = promised.stream()
                .sorted(Comparator.reverseOrder())
                .limit(wins)
                .mapToLong(Long::longValue)
                .sum();
        if (mostYouCouldOwe > wallet.getBalance())
        {
            throw new ApiException(ErrorCode.BID_EXCEEDS_BALANCE,
                    "Your " + wins + " highest bids would add up to " + mostYouCouldOwe
                            + " coins, but you only have " + wallet.getBalance() + ".");
        }

        Bid bid;
        if (existing == null)
        {
            bid = new Bid();
            bid.setUser(userRepository.getReferenceById(userId));
            bid.setProblem(problem);
            bid.setAmount(amount);
        }
        else
        {
            bid = existing;
            if (bid.getAmount() != amount)
            {
                bid.setAmount(amount);
                bid.setSubmittedAt(Instant.now());
            }
        }
        return BidResponse.from(bidRepository.saveAndFlush(bid));
    }

    /** The participant's own bids in the current round. Empty before the first round starts. */
    @Transactional(readOnly = true)
    @PreAuthorize("@guard.isSelf(#userId)")
    public List<BidResponse> myBids(Long userId)
    {
        return roundRepository.findTopByOrderByNumberDesc()
                .map(round -> bidRepository.findByUserAndRound(userId, round.getId()).stream()
                        .map(BidResponse::from)
                        .toList())
                .orElse(List.of());
    }

    /** Admin: who won a problem and what they paid. Empty until the round has been settled. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<WinnerResponse> winners(Long problemId)
    {
        if (!problemRepository.existsById(problemId))
        {
            throw new ApiException(ErrorCode.PROBLEM_NOT_FOUND);
        }
        return bidRepository.findByProblemAndStatus(problemId, BidStatus.WON).stream()
                .map(WinnerResponse::from)
                .toList();
    }

    private void requireBiddingOpen(Round round)
    {
        if (round.getPhase() != RoundPhase.BIDDING)
        {
            throw new ApiException(ErrorCode.BIDDING_CLOSED, "Bidding is not open right now.");
        }
        if (round.isPaused())
        {
            throw new ApiException(ErrorCode.BIDDING_CLOSED, "The round is paused. Bidding is on hold.");
        }
        Instant deadline = round.getBiddingDeadline();
        if (deadline != null && !Instant.now().isBefore(deadline))
        {
            throw new ApiException(ErrorCode.BIDDING_CLOSED, "The bidding deadline has passed.");
        }
    }
}

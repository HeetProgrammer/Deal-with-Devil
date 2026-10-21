package org.example.deal_with_devil_csa.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.BidStatus;
import org.example.deal_with_devil_csa.domain.EventType;
import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.LedgerType;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.BidRepository;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.web.dto.AllocationViewResponse;
import org.example.deal_with_devil_csa.web.dto.AllocationViewResponse.ParticipantAllocation;
import org.example.deal_with_devil_csa.web.dto.AllocationViewResponse.ProblemAllocation;
import org.example.deal_with_devil_csa.web.dto.AutoFillResponse;
import org.example.deal_with_devil_csa.web.dto.BidCoverageResponse;
import org.example.deal_with_devil_csa.web.dto.BidCoverageResponse.ParticipantBids;
import org.example.deal_with_devil_csa.web.dto.ManualAssignmentRequest;
import org.example.deal_with_devil_csa.web.dto.ManualAssignmentResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Everything the admin needs to get every participant to exactly game.wins-per-round problems in a round (Phase 9),
 * around the settlement itself:
 * <ul>
 *   <li>bidCoverage: before bidding closes, who has bid on how many problems.</li>
 *   <li>allocationView: after settlement, every participant's number of problems (anyone not at the target is
 *       flagged) and the unsold slots of every problem.</li>
 *   <li>autoFill: tops up everybody who is short from the unsold slots at the reserve price (the rule lives in
 *       AutoFillPlanner), with a dry run that shows the plan without saving it.</li>
 *   <li>assign: the admin gives one specific problem to one participant at the reserve price, for whoever auto fill
 *       could not finish. It may go over a problem's slots on purpose.</li>
 * </ul>
 * A problem handed out this way is stored as a WON bid with the reserve price as pricePaid (a new bid row, or the
 * participant's existing non-winning bid on that problem, because there is only one bid row per participant and
 * problem). The coins move through WalletService with a ledger entry naming the admin, and the participant gets a
 * pop-up. It then shows up in "my problems" exactly like a normal win. The ledger type is BID_SETTLEMENT and the
 * event type BID_WON, with the difference in the reason and the message, so no new enum value is needed.
 *
 * Auto fill and manual assignment take a row lock on the round first, so a double click or two admins at once cannot
 * hand out the same slot twice: the second request waits and then sees the result of the first.
 */
@Service
@RequiredArgsConstructor
public class RoundAllocationService
{
    private final RoundRepository roundRepository;
    private final ProblemRepository problemRepository;
    private final BidRepository bidRepository;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final WalletService walletService;
    private final EventService eventService;
    private final GameProperties gameProperties;

    // ---- Before bidding closes ----

    /** Every participant with a completed profile and how many problems of the current round they have bid on. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public BidCoverageResponse bidCoverage()
    {
        Round round = latest();
        int problemCount = problemRepository.findByRoundIdOrderByIdAsc(round.getId()).size();
        Map<Long, Long> bidsPerUser = bidRepository.findAllByRound(round.getId()).stream()
                .collect(Collectors.groupingBy(bid -> bid.getUser().getId(), Collectors.counting()));

        List<ParticipantBids> rows = completeParticipants().stream()
                .map(user ->
                {
                    int count = bidsPerUser.getOrDefault(user.getId(), 0L).intValue();
                    return new ParticipantBids(user.getId(), user.getName(), user.getHackerRankId(), count,
                            Math.max(0, problemCount - count));
                })
                .sorted(Comparator.comparingInt(ParticipantBids::bidCount)
                        .thenComparing(row -> lower(row.name()))
                        .thenComparing(ParticipantBids::userId))
                .toList();
        return new BidCoverageResponse(round.getId(), round.getNumber(), problemCount,
                gameProperties.winsPerRound(), rows);
    }

    // ---- After settlement ----

    /** The allocation view. Only once the round has been settled (SOLVING or RESULTS). */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public AllocationViewResponse allocationView()
    {
        Round round = latest();
        if (round.getPhase() != RoundPhase.SOLVING && round.getPhase() != RoundPhase.RESULTS)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE, "This round has not been settled yet.");
        }
        int target = gameProperties.winsPerRound();

        List<Problem> problems = problemRepository.findByRoundIdOrderByIdAsc(round.getId());
        Map<Long, Integer> winsByUser = new HashMap<>();
        Map<Long, Integer> unaffordableByUser = new HashMap<>();
        Map<Long, Integer> soldByProblem = new HashMap<>();
        Map<Long, Integer> unaffordableByProblem = new HashMap<>();
        for (Bid bid : bidRepository.findAllByRound(round.getId()))
        {
            Long userId = bid.getUser().getId();
            Long problemId = bid.getProblem().getId();
            if (bid.getStatus() == BidStatus.WON)
            {
                winsByUser.merge(userId, 1, Integer::sum);
                soldByProblem.merge(problemId, 1, Integer::sum);
            }
            else if (bid.getStatus() == BidStatus.UNAFFORDABLE)
            {
                unaffordableByUser.merge(userId, 1, Integer::sum);
                unaffordableByProblem.merge(problemId, 1, Integer::sum);
            }
        }

        List<ParticipantAllocation> people = completeParticipantWallets().stream()
                .map(wallet ->
                {
                    User user = wallet.getUser();
                    int wins = winsByUser.getOrDefault(user.getId(), 0);
                    return new ParticipantAllocation(user.getId(), user.getName(), user.getHackerRankId(),
                            wallet.getBalance(), wins, unaffordableByUser.getOrDefault(user.getId(), 0),
                            Math.max(0, target - wins), Math.max(0, wins - target), wins == target);
                })
                .sorted(Comparator.comparing(ParticipantAllocation::atTarget)   // false first: the flagged ones on top
                        .thenComparing(row -> lower(row.name()))
                        .thenComparing(ParticipantAllocation::userId))
                .toList();

        List<ProblemAllocation> problemRows = problems.stream()
                .map(problem ->
                {
                    int sold = soldByProblem.getOrDefault(problem.getId(), 0);
                    return new ProblemAllocation(problem.getId(), problem.getTitle(), problem.getSlots(), sold,
                            unaffordableByProblem.getOrDefault(problem.getId(), 0),
                            Math.max(0, problem.getSlots() - sold), Math.max(0, sold - problem.getSlots()));
                })
                .toList();

        int atTarget = (int) people.stream().filter(ParticipantAllocation::atTarget).count();
        return new AllocationViewResponse(round.getId(), round.getNumber(), target, people.size(), atTarget, people,
                problemRows);
    }

    /**
     * Tops up everybody who has fewer than the target number of problems (see AutoFillPlanner for the order). Only
     * while the round is in SOLVING. Pressing it again is safe: it only ever gives out what is still missing. With
     * dryRun nothing is saved and the response shows what the button would do.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') and @guard.isSelf(#adminId)")
    public AutoFillResponse autoFill(Long adminId, boolean dryRun)
    {
        Round round = lockedLatest();
        requireSolving(round);
        int target = gameProperties.winsPerRound();

        List<Problem> problems = problemRepository.findByRoundIdOrderByIdAsc(round.getId());
        List<Bid> bids = bidRepository.findAllByRound(round.getId());
        List<Wallet> wallets = completeParticipantWallets();

        // Only WON bids count as holding a problem or as a sold slot: a slot whose winner turned out to be
        // unaffordable was never paid for, so it is still unsold.
        Map<Long, Set<Long>> held = new HashMap<>();
        Map<Long, Integer> sold = new HashMap<>();
        Map<Long, Map<Long, Bid>> bidIndex = new HashMap<>();
        for (Bid bid : bids)
        {
            Long userId = bid.getUser().getId();
            Long problemId = bid.getProblem().getId();
            bidIndex.computeIfAbsent(userId, id -> new HashMap<>()).put(problemId, bid);
            if (bid.getStatus() == BidStatus.WON)
            {
                held.computeIfAbsent(userId, id -> new HashSet<>()).add(problemId);
                sold.merge(problemId, 1, Integer::sum);
            }
        }

        List<AutoFillPlanner.Participant> people = wallets.stream()
                .map(wallet -> new AutoFillPlanner.Participant(wallet.getUser().getId(), wallet.getBalance(),
                        held.getOrDefault(wallet.getUser().getId(), Set.of())))
                .toList();
        List<AutoFillPlanner.ProblemState> states = problems.stream()
                .map(problem -> new AutoFillPlanner.ProblemState(problem.getId(),
                        Math.max(0, problem.getSlots() - sold.getOrDefault(problem.getId(), 0)),
                        problem.getBasePrice()))
                .toList();
        AutoFillPlanner.Plan plan = AutoFillPlanner.plan(people, states, target);

        Map<Long, User> usersById = wallets.stream().map(Wallet::getUser)
                .collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, Problem> problemsById = problems.stream()
                .collect(Collectors.toMap(Problem::getId, Function.identity()));

        List<AutoFillResponse.Assigned> assigned = new ArrayList<>();
        for (AutoFillPlanner.Assignment assignment : plan.assignments())
        {
            User user = usersById.get(assignment.userId());
            Problem problem = problemsById.get(assignment.problemId());
            if (!dryRun)
            {
                Bid existing = bidIndex.getOrDefault(user.getId(), Map.of()).get(problem.getId());
                give(adminId, user, problem, assignment.price(), existing, "automatically");
            }
            assigned.add(new AutoFillResponse.Assigned(user.getId(), user.getName(), user.getHackerRankId(),
                    problem.getId(), problem.getTitle(), assignment.price()));
        }

        List<AutoFillResponse.StillShort> stillShort = new ArrayList<>();
        for (Wallet wallet : wallets)
        {
            User user = wallet.getUser();
            int wins = plan.winsByUser().get(user.getId());
            if (wins < target)
            {
                stillShort.add(new AutoFillResponse.StillShort(user.getId(), user.getName(), user.getHackerRankId(),
                        wins, target - wins));
            }
        }
        stillShort.sort(Comparator.comparing((AutoFillResponse.StillShort row) -> lower(row.name()))
                .thenComparing(AutoFillResponse.StillShort::userId));

        return new AutoFillResponse(dryRun, target, assigned, stillShort);
    }

    /**
     * Gives one problem to one participant at the problem's reserve price. For the people auto fill could not finish.
     * Only while the round is in SOLVING. Refused when the participant already has the target number of problems or
     * already holds this problem. The problem may go over its slots (overCapacity in the response), because the
     * leftover slots can be on problems the person already holds. Without enough coins the call fails with
     * INSUFFICIENT_BALANCE and nothing changes.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN') and @guard.isSelf(#adminId)")
    public ManualAssignmentResponse assign(Long adminId, ManualAssignmentRequest request)
    {
        Round round = lockedLatest();
        requireSolving(round);
        int target = gameProperties.winsPerRound();

        Problem problem = problemRepository.findById(request.problemId())
                .filter(p -> p.getRound() != null && p.getRound().getId().equals(round.getId()))
                .orElseThrow(() -> new ApiException(ErrorCode.PROBLEM_NOT_FOUND));
        User user = userRepository.findById(request.userId())
                .filter(u -> u.getRole() == Role.PARTICIPANT)
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        List<Bid> mine = bidRepository.findByUserAndRound(user.getId(), round.getId());
        int wins = (int) mine.stream().filter(bid -> bid.getStatus() == BidStatus.WON).count();
        boolean alreadyHolds = mine.stream().anyMatch(bid -> bid.getStatus() == BidStatus.WON
                && bid.getProblem().getId().equals(problem.getId()));
        if (alreadyHolds)
        {
            throw new ApiException(ErrorCode.CONFLICT, "This participant already has that problem.");
        }
        if (wins >= target)
        {
            throw new ApiException(ErrorCode.CONFLICT,
                    "This participant already has " + wins + " problems in this round.");
        }
        Bid existing = mine.stream()
                .filter(bid -> bid.getProblem().getId().equals(problem.getId()))
                .findFirst()
                .orElse(null);

        int soldBefore = bidRepository.findByProblemAndStatus(problem.getId(), BidStatus.WON).size();
        long balanceAfter = give(adminId, user, problem, problem.getBasePrice(), existing, "by an admin");
        return new ManualAssignmentResponse(user.getId(), problem.getId(), problem.getTitle(),
                problem.getBasePrice(), balanceAfter, wins + 1, soldBefore >= problem.getSlots());
    }

    // ---- Internals ----

    /**
     * Hands one problem to one participant: takes the price (ledger entry naming the admin), stores the bid as WON
     * with that price and tells the participant. Returns the participant's balance afterwards. A price of zero
     * writes no ledger entry (WalletService refuses zero amounts), like settlement.
     */
    private long give(Long adminId, User user, Problem problem, long price, Bid existing, String how)
    {
        long balanceAfter;
        if (price > 0)
        {
            LedgerEntry entry = walletService.deduct(user.getId(), price, LedgerType.BID_SETTLEMENT,
                    "Given \"" + problem.getTitle() + "\" " + how + " at the reserve price of " + price + " coins",
                    adminId);
            balanceAfter = entry.getBalanceAfter();
        }
        else
        {
            balanceAfter = walletRepository.findByUserId(user.getId()).map(Wallet::getBalance).orElse(0L);
        }

        Bid bid = existing;
        if (bid == null)
        {
            bid = new Bid();
            bid.setUser(user);
            bid.setProblem(problem);
            bid.setAmount(price);
        }
        bid.setStatus(BidStatus.WON);
        bid.setPricePaid(price);
        bidRepository.save(bid);

        eventService.personal(user.getId(), EventType.BID_WON,
                "You were given \"" + problem.getTitle() + "\" " + how + " at the reserve price of " + price
                        + " coins.",
                -price, balanceAfter, null, problem.getId());
        return balanceAfter;
    }

    private List<User> completeParticipants()
    {
        return userRepository.findByRole(Role.PARTICIPANT).stream().filter(User::isProfileComplete).toList();
    }

    private List<Wallet> completeParticipantWallets()
    {
        return walletRepository.findAllByUserRole(Role.PARTICIPANT).stream()
                .filter(wallet -> wallet.getUser().isProfileComplete())
                .toList();
    }

    private void requireSolving(Round round)
    {
        if (round.getPhase() != RoundPhase.SOLVING)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE,
                    "Problems can only be handed out while the round is in the solving phase, after settlement.");
        }
    }

    private Round latest()
    {
        return roundRepository.findTopByOrderByNumberDesc()
                .orElseThrow(() -> new ApiException(ErrorCode.ROUND_NOT_FOUND, "No round has been started yet."));
    }

    /** The current round with its row locked until the end of the transaction. */
    private Round lockedLatest()
    {
        Round round = latest();
        return roundRepository.findByIdForUpdate(round.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.ROUND_NOT_FOUND, "No round has been started yet."));
    }

    private static String lower(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

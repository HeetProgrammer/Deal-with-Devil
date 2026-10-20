package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.BidStatus;
import org.example.deal_with_devil_csa.domain.EventType;
import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.BidRepository;
import org.example.deal_with_devil_csa.repository.LedgerEntryRepository;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.web.dto.SettlementSummaryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests of the settlement flow. Repositories are mocked, but WalletService is the real one, so the tests
 * see real balance changes and ledger writes. Database behaviour (row locks, rollback of the whole
 * transaction) belongs in integration tests.
 */
@ExtendWith(MockitoExtension.class)
class SettlementServiceTest
{
    private static final long ROUND_ID = 1L;
    private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

    @Mock
    private RoundRepository roundRepository;
    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private BidRepository bidRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EventService eventService;

    private SettlementService service;
    private Round round;

    private final Map<Long, Wallet> wallets = new HashMap<>();
    private final List<Problem> problems = new ArrayList<>();
    private final List<Bid> bids = new ArrayList<>();
    private long nextBidId = 1;

    @BeforeEach
    void setUp()
    {
        WalletService walletService = new WalletService(walletRepository, ledgerEntryRepository, userRepository);
        service = new SettlementService(roundRepository, problemRepository, bidRepository, walletRepository,
                walletService, eventService);

        round = round(RoundPhase.SETTLEMENT);
        lenient().when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));
        lenient().when(roundRepository.saveAndFlush(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(problemRepository.findByRoundIdOrderByIdAsc(ROUND_ID)).thenReturn(problems);
        lenient().when(bidRepository.findByRoundAndStatus(ROUND_ID, BidStatus.PENDING))
                .thenAnswer(inv -> bids.stream().filter(b -> b.getStatus() == BidStatus.PENDING).toList());
        lenient().when(bidRepository.findAllByRound(ROUND_ID)).thenReturn(bids);
        lenient().when(walletRepository.findByUserIdForUpdate(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(wallets.get(inv.<Long>getArgument(0))));
        lenient().when(ledgerEntryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- the normal case ----

    @Test
    void winnerPaysTheSecondHighestBidNotTheirOwn()
    {
        User a = user(1, 1000);
        User b = user(2, 1000);
        User c = user(3, 1000);
        Problem p = problem(10, 1, 50);
        Bid bidA = bid(a, p, 100, 0);
        Bid bidB = bid(b, p, 80, 1);
        Bid bidC = bid(c, p, 60, 2);

        SettlementSummaryResponse summary = service.settle();

        assertThat(bidA.getStatus()).isEqualTo(BidStatus.WON);
        assertThat(bidA.getPricePaid()).isEqualTo(80L);
        assertThat(bidB.getStatus()).isEqualTo(BidStatus.LOST);
        assertThat(bidC.getStatus()).isEqualTo(BidStatus.LOST);
        assertThat(balance(1)).isEqualTo(920L);
        assertThat(balance(2)).isEqualTo(1000L);
        assertThat(balance(3)).isEqualTo(1000L);

        assertThat(summary.totalCollected()).isEqualTo(80L);
        assertThat(summary.problems()).hasSize(1);
        SettlementSummaryResponse.ProblemResult result = summary.problems().get(0);
        assertThat(result.bidCount()).isEqualTo(3);
        assertThat(result.winnerCount()).isEqualTo(1);
        assertThat(result.unaffordableCount()).isZero();
        assertThat(result.clearingPrice()).isEqualTo(80L);
    }

    @Test
    void everyWinnerPaysTheSamePriceWhenThereAreSeveralSlots()
    {
        // The worked example: k = 2, bids 90, 70, 50, 30. Both winners pay 50.
        User a = user(1, 1000);
        User b = user(2, 1000);
        User c = user(3, 1000);
        User d = user(4, 1000);
        Problem p = problem(10, 2, 20);
        Bid bidA = bid(a, p, 90, 0);
        Bid bidB = bid(b, p, 70, 1);
        Bid bidC = bid(c, p, 50, 2);
        Bid bidD = bid(d, p, 30, 3);

        SettlementSummaryResponse summary = service.settle();

        assertThat(bidA.getPricePaid()).isEqualTo(50L);
        assertThat(bidB.getPricePaid()).isEqualTo(50L);
        assertThat(bidC.getStatus()).isEqualTo(BidStatus.LOST);
        assertThat(bidD.getStatus()).isEqualTo(BidStatus.LOST);
        assertThat(balance(1)).isEqualTo(950L);
        assertThat(balance(2)).isEqualTo(950L);
        assertThat(summary.totalCollected()).isEqualTo(100L);
    }

    @Test
    void settlementWritesALedgerEntryForEachWinnerWithNoActor()
    {
        User a = user(1, 1000);
        User b = user(2, 1000);
        Problem p = problem(10, 1, 50);
        bid(a, p, 100, 0);
        bid(b, p, 80, 1);

        service.settle();

        org.mockito.ArgumentCaptor<LedgerEntry> captor = org.mockito.ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        LedgerEntry entry = captor.getValue();
        assertThat(entry.getType()).isEqualTo(org.example.deal_with_devil_csa.domain.LedgerType.BID_SETTLEMENT);
        assertThat(entry.getAmount()).isEqualTo(-80L);
        assertThat(entry.getBalanceAfter()).isEqualTo(920L);
        assertThat(entry.getActor()).isNull();
        assertThat(entry.getUser()).isSameAs(a);
    }

    @Test
    void roundMovesToSolvingAndEveryoneIsToldAboutThePhaseChange()
    {
        User a = user(1, 1000);
        Problem p = problem(10, 1, 50);
        bid(a, p, 100, 0);

        service.settle();

        assertThat(round.getPhase()).isEqualTo(RoundPhase.SOLVING);
        verify(roundRepository).saveAndFlush(round);
        verify(eventService).broadcast(eq(EventType.PHASE_CHANGED), anyString());
    }

    @Test
    void winnersAndLosersGetTheirOwnNotifications()
    {
        User a = user(1, 1000);
        User b = user(2, 1000);
        Problem p = problem(10, 1, 50);
        bid(a, p, 100, 0);
        bid(b, p, 80, 1);

        service.settle();

        verify(eventService).personal(eq(1L), eq(EventType.BID_WON), anyString(), eq(-80L), eq(920L),
                isNull(), eq(10L));
        verify(eventService).personal(eq(2L), eq(EventType.BID_LOST), anyString(), isNull(), isNull(),
                isNull(), eq(10L));
    }

    // ---- reserve price ----

    @Test
    void aSingleBidderPaysTheReservePrice()
    {
        User a = user(1, 1000);
        Problem p = problem(10, 1, 50);
        Bid bidA = bid(a, p, 300, 0);

        service.settle();

        assertThat(bidA.getStatus()).isEqualTo(BidStatus.WON);
        assertThat(bidA.getPricePaid()).isEqualTo(50L);
        assertThat(balance(1)).isEqualTo(950L);
    }

    @Test
    void tooFewBidsForAllSlotsMeansEveryBidderWinsAtTheReserve()
    {
        User a = user(1, 1000);
        User b = user(2, 1000);
        Problem p = problem(10, 2, 40);
        Bid bidA = bid(a, p, 90, 0);
        Bid bidB = bid(b, p, 70, 1);

        service.settle();

        assertThat(bidA.getPricePaid()).isEqualTo(40L);
        assertThat(bidB.getPricePaid()).isEqualTo(40L);
    }

    @Test
    void aBidBelowTheReserveIsNeverEligibleAndDoesNotSetThePrice()
    {
        User a = user(1, 1000);
        User b = user(2, 1000);
        Problem p = problem(10, 1, 50);
        Bid bidA = bid(a, p, 100, 0);
        Bid bidB = bid(b, p, 30, 1); // below the reserve of 50

        service.settle();

        assertThat(bidA.getPricePaid()).isEqualTo(50L); // not 30
        assertThat(bidB.getStatus()).isEqualTo(BidStatus.LOST);
    }

    @Test
    void aProblemWithNoBidsSettlesCleanlyAtTheReservePrice()
    {
        problem(10, 1, 50);

        SettlementSummaryResponse summary = service.settle();

        assertThat(summary.totalCollected()).isZero();
        assertThat(summary.problems().get(0).bidCount()).isZero();
        assertThat(summary.problems().get(0).winnerCount()).isZero();
        assertThat(summary.problems().get(0).clearingPrice()).isEqualTo(50L);
        assertThat(round.getPhase()).isEqualTo(RoundPhase.SOLVING);
    }

    @Test
    void aFreeProblemChargesNothingAndWritesNoLedgerEntry()
    {
        User a = user(1, 1000);
        Problem p = problem(10, 1, 0);
        Bid bidA = bid(a, p, 5, 0);

        service.settle();

        assertThat(bidA.getStatus()).isEqualTo(BidStatus.WON);
        assertThat(bidA.getPricePaid()).isZero();
        assertThat(balance(1)).isEqualTo(1000L);
        verify(ledgerEntryRepository, never()).save(any());
    }

    // ---- ties ----

    @Test
    void equalBidsGoToTheEarlierSubmissionAndTheLoserSetsThePrice()
    {
        User a = user(1, 1000);
        User b = user(2, 1000);
        Problem p = problem(10, 1, 50);
        Bid late = bid(a, p, 80, 10);
        Bid early = bid(b, p, 80, 2);

        service.settle();

        assertThat(early.getStatus()).isEqualTo(BidStatus.WON);
        assertThat(early.getPricePaid()).isEqualTo(80L);
        assertThat(late.getStatus()).isEqualTo(BidStatus.LOST);
    }

    // ---- insufficient balance at settlement ----

    @Test
    void aWinnerWhoCanNoLongerAffordThePriceIsMarkedUnaffordableAndNotCharged()
    {
        // An admin deducted coins after the bid, so 100 is no longer covered by the balance of 50.
        User a = user(1, 50);
        User b = user(2, 1000);
        User c = user(3, 1000);
        Problem p = problem(10, 1, 50);
        Bid bidA = bid(a, p, 100, 0);
        Bid bidB = bid(b, p, 80, 1);
        Bid bidC = bid(c, p, 60, 2);

        SettlementSummaryResponse summary = service.settle();

        assertThat(bidA.getStatus()).isEqualTo(BidStatus.UNAFFORDABLE);
        assertThat(bidA.getPricePaid()).isNull();
        assertThat(balance(1)).isEqualTo(50L);
        verify(ledgerEntryRepository, never()).save(any());

        // The slot is not handed to the next bidder.
        assertThat(bidB.getStatus()).isEqualTo(BidStatus.LOST);
        assertThat(bidC.getStatus()).isEqualTo(BidStatus.LOST);

        assertThat(summary.totalCollected()).isZero();
        assertThat(summary.problems().get(0).winnerCount()).isZero();
        assertThat(summary.problems().get(0).unaffordableCount()).isEqualTo(1);
        verify(eventService).personal(eq(1L), eq(EventType.BID_UNAFFORDABLE), anyString(), isNull(), eq(50L),
                isNull(), eq(10L));
    }

    @Test
    void aBalanceExactlyEqualToThePriceIsEnough()
    {
        User a = user(1, 80);
        User b = user(2, 1000);
        Problem p = problem(10, 1, 50);
        Bid bidA = bid(a, p, 100, 0);
        bid(b, p, 80, 1);

        service.settle();

        assertThat(bidA.getStatus()).isEqualTo(BidStatus.WON);
        assertThat(balance(1)).isZero();
    }

    @Test
    void winningSeveralProblemsCanExhaustTheBalanceHalfwayThrough()
    {
        // 100 coins, two problems at 60 each: the first is paid, the second no longer fits.
        User a = user(1, 100);
        User b = user(2, 1000);
        Problem p1 = problem(10, 1, 10);
        Problem p2 = problem(20, 1, 10);
        Bid a1 = bid(a, p1, 70, 0);
        Bid a2 = bid(a, p2, 70, 1);
        bid(b, p1, 60, 2);
        bid(b, p2, 60, 3);

        SettlementSummaryResponse summary = service.settle();

        assertThat(a1.getStatus()).isEqualTo(BidStatus.WON);
        assertThat(a2.getStatus()).isEqualTo(BidStatus.UNAFFORDABLE);
        assertThat(balance(1)).isEqualTo(40L);
        assertThat(summary.totalCollected()).isEqualTo(60L);
        assertThat(summary.problems().get(1).unaffordableCount()).isEqualTo(1);
        verify(eventService).personal(eq(1L), eq(EventType.BID_WON), anyString(), eq(-60L), eq(40L),
                isNull(), eq(10L));
        verify(eventService).personal(eq(1L), eq(EventType.BID_UNAFFORDABLE), anyString(), isNull(), eq(40L),
                isNull(), eq(20L));
    }

    // ---- guards ----

    @Test
    void settlingTwiceIsRejectedAndMovesNoCoins()
    {
        User a = user(1, 1000);
        Problem p = problem(10, 1, 50);
        bid(a, p, 100, 0);
        service.settle();
        long balanceAfterFirstRun = balance(1);

        assertApiError(() -> service.settle(), ErrorCode.ALREADY_SETTLED);

        assertThat(balance(1)).isEqualTo(balanceAfterFirstRun);
    }

    @Test
    void settlingAFinishedRoundIsReportedAsAlreadySettled()
    {
        round.setPhase(RoundPhase.RESULTS);

        assertApiError(() -> service.settle(), ErrorCode.ALREADY_SETTLED);
    }

    @Test
    void settlementOnlyRunsInTheSettlementPhase()
    {
        round.setPhase(RoundPhase.BIDDING);
        User a = user(1, 1000);
        Problem p = problem(10, 1, 50);
        Bid bidA = bid(a, p, 100, 0);

        assertApiError(() -> service.settle(), ErrorCode.INVALID_PHASE);

        assertThat(bidA.getStatus()).isEqualTo(BidStatus.PENDING);
        assertThat(balance(1)).isEqualTo(1000L);
        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void settlementIsBlockedWhilePaused()
    {
        round.setPaused(true);
        User a = user(1, 1000);
        Problem p = problem(10, 1, 50);
        bid(a, p, 100, 0);

        assertApiError(() -> service.settle(), ErrorCode.INVALID_PHASE);

        assertThat(round.getPhase()).isEqualTo(RoundPhase.SETTLEMENT);
        assertThat(balance(1)).isEqualTo(1000L);
    }

    @Test
    void settlementWithNoRoundGivesRoundNotFound()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.empty());

        assertApiError(() -> service.settle(), ErrorCode.ROUND_NOT_FOUND);
    }

    // ---- summary ----

    @Test
    void summaryCanBeReadAgainAfterSettlementAndMatches()
    {
        User a = user(1, 1000);
        User b = user(2, 1000);
        Problem p = problem(10, 1, 50);
        bid(a, p, 100, 0);
        bid(b, p, 80, 1);

        SettlementSummaryResponse first = service.settle();

        assertThat(service.currentSummary()).isEqualTo(first);
    }

    @Test
    void summaryIsNotAvailableBeforeSettlement()
    {
        assertApiError(() -> service.currentSummary(), ErrorCode.INVALID_PHASE);
    }

    // ---- helpers ----

    private User user(long id, long balance)
    {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@example.com");
        Wallet wallet = new Wallet(user);
        wallet.setBalance(balance);
        wallets.put(id, wallet);
        return user;
    }

    private Problem problem(long id, int slots, long basePrice)
    {
        Problem problem = new Problem();
        problem.setId(id);
        problem.setTitle("Problem " + id);
        problem.setSlots(slots);
        problem.setBasePrice(basePrice);
        problems.add(problem);
        return problem;
    }

    private Bid bid(User user, Problem problem, long amount, long secondsAfterStart)
    {
        Bid bid = new Bid();
        bid.setId(nextBidId++);
        bid.setUser(user);
        bid.setProblem(problem);
        bid.setAmount(amount);
        bid.setSubmittedAt(T0.plusSeconds(secondsAfterStart));
        bids.add(bid);
        return bid;
    }

    private long balance(long userId)
    {
        return wallets.get(userId).getBalance();
    }

    private static Round round(RoundPhase phase)
    {
        Round round = new Round();
        round.setId(ROUND_ID);
        round.setNumber(1);
        round.setPhase(phase);
        round.setPhaseChangedAt(T0);
        return round;
    }

    private static void assertApiError(ThrowingCallable call, ErrorCode expected)
    {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}

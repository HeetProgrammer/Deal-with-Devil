package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.BidStatus;
import org.example.deal_with_devil_csa.domain.Problem;
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
import org.example.deal_with_devil_csa.web.dto.BidResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests of the sealed-bid rules. Repositories are mocked; there is no database or Spring. */
@ExtendWith(MockitoExtension.class)
class BidServiceTest
{
    private static final long USER_ID = 1L;
    private static final long ROUND_ID = 1L;
    private static final long P1 = 10L;
    private static final long P2 = 20L;
    private static final long P3 = 30L;
    private static final long P4 = 40L;
    /** An instant safely in the past, so "now" is always later than it. */
    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private RoundRepository roundRepository;
    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private BidRepository bidRepository;
    @Mock
    private WalletRepository walletRepository;
    @Mock
    private UserRepository userRepository;

    private BidService service;
    private User user;
    private Wallet wallet;
    private Round round;
    private Problem problem1;
    private Problem problem2;
    private Problem problem3;
    private Problem problem4;
    private final List<Bid> myBids = new ArrayList<>();

    @BeforeEach
    void setUp()
    {
        service = new BidService(roundRepository, problemRepository, bidRepository, walletRepository,
                userRepository, gameProperties());

        user = new User();
        user.setId(USER_ID);
        wallet = new Wallet(user);
        wallet.setBalance(1000L);

        round = new Round();
        round.setId(ROUND_ID);
        round.setNumber(1);
        round.setPhase(RoundPhase.BIDDING);

        problem1 = problem(P1, 50, round);
        problem2 = problem(P2, 50, round);
        problem3 = problem(P3, 50, round);
        problem4 = problem(P4, 50, round);

        lenient().when(walletRepository.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(wallet));
        lenient().when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));
        lenient().when(problemRepository.findById(P1)).thenReturn(Optional.of(problem1));
        lenient().when(problemRepository.findById(P2)).thenReturn(Optional.of(problem2));
        lenient().when(problemRepository.findById(P3)).thenReturn(Optional.of(problem3));
        lenient().when(problemRepository.findById(P4)).thenReturn(Optional.of(problem4));
        lenient().when(bidRepository.findByUserAndRound(USER_ID, ROUND_ID)).thenReturn(myBids);
        lenient().when(bidRepository.saveAndFlush(any(Bid.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(userRepository.getReferenceById(USER_ID)).thenReturn(user);
    }

    // ---- placing and changing bids ----

    @Test
    void newBidIsStoredAsPendingForTheRightUserAndProblem()
    {
        BidResponse response = service.placeBid(USER_ID, P1, 120);

        assertThat(response.problemId()).isEqualTo(P1);
        assertThat(response.amount()).isEqualTo(120L);
        assertThat(response.status()).isEqualTo(BidStatus.PENDING);
        assertThat(response.pricePaid()).isNull();
        assertThat(response.hackerRankLink()).isNull(); // never revealed before winning

        org.mockito.ArgumentCaptor<Bid> captor = org.mockito.ArgumentCaptor.forClass(Bid.class);
        verify(bidRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
        assertThat(captor.getValue().getProblem()).isSameAs(problem1);
    }

    @Test
    void biddingAgainOnTheSameProblemEditsTheExistingBid()
    {
        Bid existing = existingBid(5L, problem1, 100);

        BidResponse response = service.placeBid(USER_ID, P1, 150);

        assertThat(response.amount()).isEqualTo(150L);
        assertThat(existing.getAmount()).isEqualTo(150L);
        assertThat(existing.getSubmittedAt()).isAfter(OLD); // the tie-break clock moves when the amount changes
        verify(bidRepository).saveAndFlush(existing);
    }

    @Test
    void resubmittingTheSameAmountDoesNotMoveTheTieBreakClock()
    {
        Bid existing = existingBid(5L, problem1, 100);

        service.placeBid(USER_ID, P1, 100);

        assertThat(existing.getSubmittedAt()).isEqualTo(OLD);
    }

    @Test
    void lowerBidsAreAllowedWhileBiddingIsOpen()
    {
        Bid existing = existingBid(5L, problem1, 400);

        service.placeBid(USER_ID, P1, 60);

        assertThat(existing.getAmount()).isEqualTo(60L);
    }

    // ---- reserve price ----

    @Test
    void bidBelowTheReservePriceIsRejectedWithAFieldError()
    {
        assertThatThrownBy(() -> service.placeBid(USER_ID, P1, 49))
                .isInstanceOfSatisfying(ApiException.class, e ->
                {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.getDetails()).containsKey("amount");
                });

        verify(bidRepository, never()).saveAndFlush(any());
    }

    @Test
    void bidExactlyAtTheReservePriceIsAccepted()
    {
        assertThat(service.placeBid(USER_ID, P1, 50).amount()).isEqualTo(50L);
    }

    // ---- balance cap ----

    @Test
    void aSingleBidAboveTheBalanceIsRejected()
    {
        assertApiError(() -> service.placeBid(USER_ID, P1, 1001), ErrorCode.BID_EXCEEDS_BALANCE);

        verify(bidRepository, never()).saveAndFlush(any());
    }

    @Test
    void aBidEqualToTheWholeBalanceIsAccepted()
    {
        assertThat(service.placeBid(USER_ID, P1, 1000).amount()).isEqualTo(1000L);
    }

    @Test
    void allBidsInTheRoundTogetherCannotExceedTheBalance()
    {
        existingBid(5L, problem1, 700);

        // 700 already promised on P1, so 301 more on P2 would total 1001 against a balance of 1000.
        assertApiError(() -> service.placeBid(USER_ID, P2, 301), ErrorCode.BID_EXCEEDS_BALANCE);

        verify(bidRepository, never()).saveAndFlush(any());
    }

    @Test
    void totalsExactlyEqualToTheBalanceAreAccepted()
    {
        existingBid(5L, problem1, 700);

        assertThat(service.placeBid(USER_ID, P2, 300).amount()).isEqualTo(300L);
    }

    @Test
    void editingABidDoesNotCountItsOldAmountTwice()
    {
        // 600 on P1 and 300 on P2. Raising P1 to 700 gives a total of 1000, which fits.
        Bid onP1 = existingBid(5L, problem1, 600);
        existingBid(6L, problem2, 300);

        service.placeBid(USER_ID, P1, 700);

        assertThat(onP1.getAmount()).isEqualTo(700L);
    }

    @Test
    void raisingABidPastWhatIsLeftAfterTheOtherBidsIsRejected()
    {
        Bid onP1 = existingBid(5L, problem1, 600);
        existingBid(6L, problem2, 300);

        assertApiError(() -> service.placeBid(USER_ID, P1, 701), ErrorCode.BID_EXCEEDS_BALANCE);

        assertThat(onP1.getAmount()).isEqualTo(600L);
    }

    @Test
    void onlyTheThreeHighestBidsHaveToFitInTheBalance()
    {
        existingBid(5L, problem1, 400);
        existingBid(6L, problem2, 400);
        existingBid(7L, problem3, 200);

        // All four bids add up to 1100, but a person wins at most three problems, so only the top three count: 1000.
        assertThat(service.placeBid(USER_ID, P4, 100).amount()).isEqualTo(100L);
    }

    @Test
    void aFourthBidThatBecomesOneOfTheTopThreeAndPushesThemPastTheBalanceIsRejected()
    {
        existingBid(5L, problem1, 400);
        existingBid(6L, problem2, 400);
        existingBid(7L, problem3, 200);

        // The top three would now be 400 + 400 + 300 = 1100 against a balance of 1000.
        assertApiError(() -> service.placeBid(USER_ID, P4, 300), ErrorCode.BID_EXCEEDS_BALANCE);

        verify(bidRepository, never()).saveAndFlush(any());
    }

    @Test
    void raisingALowBidIntoTheTopThreeIsCheckedAgainstTheBalance()
    {
        existingBid(5L, problem1, 400);
        existingBid(6L, problem2, 400);
        existingBid(7L, problem3, 200);
        Bid low = existingBid(8L, problem4, 60);

        // 400 + 400 + 250 = 1050 is too much; 400 + 400 + 200 = 1000 is exactly the balance.
        assertApiError(() -> service.placeBid(USER_ID, P4, 250), ErrorCode.BID_EXCEEDS_BALANCE);
        assertThat(low.getAmount()).isEqualTo(60L);

        service.placeBid(USER_ID, P4, 200);
        assertThat(low.getAmount()).isEqualTo(200L);
    }

    // ---- when bidding is open ----

    @Test
    void biddingIsClosedOutsideTheBiddingPhase()
    {
        for (RoundPhase phase : List.of(RoundPhase.PREVIEW, RoundPhase.SETTLEMENT, RoundPhase.SOLVING,
                RoundPhase.RESULTS))
        {
            round.setPhase(phase);

            assertApiError(() -> service.placeBid(USER_ID, P1, 100), ErrorCode.BIDDING_CLOSED);
        }

        verify(bidRepository, never()).saveAndFlush(any());
    }

    @Test
    void biddingIsClosedWhileThePhaseIsPaused()
    {
        round.setPaused(true);

        assertApiError(() -> service.placeBid(USER_ID, P1, 100), ErrorCode.BIDDING_CLOSED);
    }

    @Test
    void biddingIsClosedOnceTheDeadlineHasPassed()
    {
        round.setBiddingDeadline(Instant.now().minusSeconds(60));

        assertApiError(() -> service.placeBid(USER_ID, P1, 100), ErrorCode.BIDDING_CLOSED);

        verify(bidRepository, never()).saveAndFlush(any());
    }

    @Test
    void biddingStaysOpenBeforeTheDeadline()
    {
        round.setBiddingDeadline(Instant.now().plusSeconds(3600));

        assertThat(service.placeBid(USER_ID, P1, 100).status()).isEqualTo(BidStatus.PENDING);
    }

    // ---- which problems can be bid on ----

    @Test
    void aProblemFromAnotherRoundCannotBeBidOn()
    {
        Round other = new Round();
        other.setId(99L);
        problem1.setRound(other);

        assertApiError(() -> service.placeBid(USER_ID, P1, 100), ErrorCode.PROBLEM_NOT_FOUND);
    }

    @Test
    void aBankOnlyProblemCannotBeBidOn()
    {
        problem1.setRound(null);

        assertApiError(() -> service.placeBid(USER_ID, P1, 100), ErrorCode.PROBLEM_NOT_FOUND);
    }

    @Test
    void anUnknownProblemGivesProblemNotFound()
    {
        assertApiError(() -> service.placeBid(USER_ID, 404L, 100), ErrorCode.PROBLEM_NOT_FOUND);
    }

    @Test
    void noRoundYetGivesRoundNotFound()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.empty());

        assertApiError(() -> service.placeBid(USER_ID, P1, 100), ErrorCode.ROUND_NOT_FOUND);
    }

    @Test
    void unknownWalletGivesUserNotFound()
    {
        assertApiError(() -> service.placeBid(2L, P1, 100), ErrorCode.USER_NOT_FOUND);
    }

    // ---- reading your own bids ----

    @Test
    void myBidsIsEmptyBeforeTheFirstRound()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.empty());

        assertThat(service.myBids(USER_ID)).isEmpty();
    }

    @Test
    void myBidsShowsTheLinkOnlyForWonBids()
    {
        problem1.setHackerRankLink("https://www.hackerrank.com/p1");
        problem2.setHackerRankLink("https://www.hackerrank.com/p2");
        Bid won = existingBid(5L, problem1, 100);
        won.setStatus(BidStatus.WON);
        won.setPricePaid(80L);
        existingBid(6L, problem2, 60); // still PENDING

        List<BidResponse> responses = service.myBids(USER_ID);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).hackerRankLink()).isEqualTo("https://www.hackerrank.com/p1");
        assertThat(responses.get(0).pricePaid()).isEqualTo(80L);
        assertThat(responses.get(1).hackerRankLink()).isNull();
    }

    @Test
    void winnersOfAnUnknownProblemGiveProblemNotFound()
    {
        assertApiError(() -> service.winners(404L), ErrorCode.PROBLEM_NOT_FOUND);
    }

    // ---- helpers ----

    private static GameProperties gameProperties()
    {
        return new GameProperties(
                new GameProperties.Tiers(50, 100, 200),
                new GameProperties.Tiers(100, 200, 400),
                30,
                new GameProperties.Devil(100, 150),
                3);
    }

    private Bid existingBid(Long id, Problem problem, long amount)
    {
        Bid bid = new Bid();
        bid.setId(id);
        bid.setUser(user);
        bid.setProblem(problem);
        bid.setAmount(amount);
        bid.setSubmittedAt(OLD);
        myBids.add(bid);
        return bid;
    }

    private static Problem problem(long id, long basePrice, Round round)
    {
        Problem problem = new Problem();
        problem.setId(id);
        problem.setTitle("Problem " + id);
        problem.setBasePrice(basePrice);
        problem.setRound(round);
        return problem;
    }

    private static void assertApiError(ThrowingCallable call, ErrorCode expected)
    {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}

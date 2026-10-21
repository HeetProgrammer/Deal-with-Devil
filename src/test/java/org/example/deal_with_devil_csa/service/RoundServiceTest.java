package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.web.dto.RoundResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Pure unit tests of the round state machine: repositories are mocked, no database or Spring. */
@ExtendWith(MockitoExtension.class)
class RoundServiceTest
{
    @Mock
    private RoundRepository roundRepository;
    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private EventService eventService;
    @Mock
    private UserRepository userRepository;

    private RoundService service;

    @BeforeEach
    void setUp()
    {
        service = new RoundService(roundRepository, problemRepository, eventService, userRepository,
                gameProperties());
        lenient().when(roundRepository.saveAndFlush(any(Round.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---- start ----

    @Test
    void firstRoundIsNumberOneInPreview()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.empty());

        RoundResponse response = service.start();

        assertThat(response.number()).isEqualTo(1);
        assertThat(response.phase()).isEqualTo(RoundPhase.PREVIEW);
        assertThat(response.paused()).isFalse();
    }

    @Test
    void nextRoundStartsOnceTheLatestIsInResults()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round(1L, 3, RoundPhase.RESULTS)));

        RoundResponse response = service.start();

        assertThat(response.number()).isEqualTo(4);
        assertThat(response.phase()).isEqualTo(RoundPhase.PREVIEW);
    }

    @Test
    void cannotStartARoundWhileTheLatestIsStillRunning()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round(1L, 1, RoundPhase.SOLVING)));

        assertApiError(() -> service.start(), ErrorCode.INVALID_PHASE);

        verify(roundRepository, never()).saveAndFlush(any());
    }

    // ---- advance ----

    @Test
    void advanceMovesThroughEveryPhaseUpToSettlement()
    {
        Round round = round(1L, 1, RoundPhase.PREVIEW);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));
        // Three problems with three slots each = 9 slots, exactly 3 wins x 3 participants.
        stubProblems(1L, 3, 3);
        when(userRepository.countCompleteParticipants()).thenReturn(3L);

        assertThat(service.advance().phase()).isEqualTo(RoundPhase.BIDDING);
        assertThat(service.advance().phase()).isEqualTo(RoundPhase.SETTLEMENT);
    }

    @Test
    void advanceCannotLeaveSettlement()
    {
        // Only running settlement moves a round from SETTLEMENT to SOLVING, so bids can never be skipped.
        Round round = round(1L, 1, RoundPhase.SETTLEMENT);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));

        assertApiError(() -> service.advance(), ErrorCode.INVALID_PHASE);

        assertThat(round.getPhase()).isEqualTo(RoundPhase.SETTLEMENT);
        verify(roundRepository, never()).saveAndFlush(any());
    }

    @Test
    void advanceMovesFromSolvingToResults()
    {
        Round round = round(1L, 1, RoundPhase.SOLVING);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));

        assertThat(service.advance().phase()).isEqualTo(RoundPhase.RESULTS);
    }

    @Test
    void advanceFromResultsIsRejected()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round(1L, 1, RoundPhase.RESULTS)));

        assertApiError(() -> service.advance(), ErrorCode.INVALID_PHASE);

        verify(roundRepository, never()).saveAndFlush(any());
    }

    @Test
    void biddingCannotOpenWithoutProblems()
    {
        Round round = round(1L, 1, RoundPhase.PREVIEW);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));
        when(problemRepository.countByRoundId(1L)).thenReturn(0L);

        assertApiError(() -> service.advance(), ErrorCode.INVALID_PHASE);

        assertThat(round.getPhase()).isEqualTo(RoundPhase.PREVIEW);
        verify(roundRepository, never()).saveAndFlush(any());
    }

    // ---- the block on opening bidding (exactly 3 problems per person) ----

    @Test
    void biddingCannotOpenWithFewerProblemsThanTheWinsPerPerson()
    {
        Round round = round(1L, 1, RoundPhase.PREVIEW);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));
        // Two problems cannot give anyone three different problems, however many slots they have.
        stubProblems(1L, 2, 50);

        assertThatThrownBy(() -> service.advance()).isInstanceOfSatisfying(ApiException.class, e ->
        {
            assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_PHASE);
            assertThat(e.getMessage()).contains("at least 3 problems");
        });

        assertThat(round.getPhase()).isEqualTo(RoundPhase.PREVIEW);
        verify(roundRepository, never()).saveAndFlush(any());
    }

    @Test
    void biddingCannotOpenWhenTheSlotsAreBelowThreeTimesTheParticipants()
    {
        Round round = round(1L, 1, RoundPhase.PREVIEW);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));
        stubProblems(1L, 3, 2);   // 6 slots
        when(userRepository.countCompleteParticipants()).thenReturn(3L);   // needs 9

        assertThatThrownBy(() -> service.advance()).isInstanceOfSatisfying(ApiException.class, e ->
        {
            assertThat(e.getCode()).isEqualTo(ErrorCode.INVALID_PHASE);
            assertThat(e.getMessage()).contains("6 slots").contains("at least 9");
        });

        assertThat(round.getPhase()).isEqualTo(RoundPhase.PREVIEW);
        verify(roundRepository, never()).saveAndFlush(any());
    }

    @Test
    void biddingOpensWhenThereAreMoreSlotsThanNeeded()
    {
        Round round = round(1L, 1, RoundPhase.PREVIEW);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));
        stubProblems(1L, 4, 4);   // 16 slots against 9 needed
        when(userRepository.countCompleteParticipants()).thenReturn(3L);

        assertThat(service.advance().phase()).isEqualTo(RoundPhase.BIDDING);
    }

    @Test
    void aNewSignUpAfterTheSlotsWereSetBlocksOpeningUntilTheyArePressedAgain()
    {
        Round round = round(1L, 1, RoundPhase.PREVIEW);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));
        stubProblems(1L, 3, 3);   // 9 slots: enough for 3 participants
        when(userRepository.countCompleteParticipants()).thenReturn(4L);   // a fourth person signed up: needs 12

        assertApiError(() -> service.advance(), ErrorCode.INVALID_PHASE);
        assertThat(round.getPhase()).isEqualTo(RoundPhase.PREVIEW);
    }

    @Test
    void advanceIsBlockedWhilePaused()
    {
        Round round = round(1L, 1, RoundPhase.BIDDING);
        round.setPaused(true);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));

        assertApiError(() -> service.advance(), ErrorCode.INVALID_PHASE);

        assertThat(round.getPhase()).isEqualTo(RoundPhase.BIDDING);
    }

    @Test
    void advanceWithNoRoundGivesRoundNotFound()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.empty());

        assertApiError(() -> service.advance(), ErrorCode.ROUND_NOT_FOUND);
    }

    // ---- pause / resume ----

    @Test
    void pauseThenResume()
    {
        Round round = round(1L, 1, RoundPhase.BIDDING);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));

        assertThat(service.pause().paused()).isTrue();
        assertThat(service.resume().paused()).isFalse();
    }

    @Test
    void pausingAnAlreadyPausedRoundChangesNothing()
    {
        Round round = round(1L, 1, RoundPhase.BIDDING);
        round.setPaused(true);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round));

        assertThat(service.pause().paused()).isTrue();

        verify(roundRepository, never()).saveAndFlush(any());
    }

    // ---- getCurrent ----

    @Test
    void getCurrentReturnsTheLatestRound()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(round(5L, 2, RoundPhase.BIDDING)));

        RoundResponse response = service.getCurrent();

        assertThat(response.id()).isEqualTo(5L);
        assertThat(response.number()).isEqualTo(2);
        assertThat(response.phase()).isEqualTo(RoundPhase.BIDDING);
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

    /** The round has this many problems with this many slots each (what advance() reads before opening bidding). */
    private void stubProblems(long roundId, int count, int slotsEach)
    {
        List<Problem> problems = new ArrayList<>();
        for (int i = 1; i <= count; i++)
        {
            Problem problem = new Problem();
            problem.setId((long) i);
            problem.setSlots(slotsEach);
            problems.add(problem);
        }
        when(problemRepository.countByRoundId(roundId)).thenReturn((long) count);
        when(problemRepository.findByRoundIdOrderByIdAsc(roundId)).thenReturn(problems);
    }

    private static Round round(Long id, int number, RoundPhase phase)
    {
        Round round = new Round();
        round.setId(id);
        round.setNumber(number);
        round.setPhase(phase);
        round.setPhaseChangedAt(java.time.Instant.now());
        return round;
    }

    private static void assertApiError(ThrowingCallable call, ErrorCode expected)
    {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}

package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.ProblemHint;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.Tier;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.ProblemHintRepository;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.web.dto.AdminProblemResponse;
import org.example.deal_with_devil_csa.web.dto.AutoSlotsResponse;
import org.example.deal_with_devil_csa.web.dto.HintRequest;
import org.example.deal_with_devil_csa.web.dto.HintResponse;
import org.example.deal_with_devil_csa.web.dto.ProblemPreviewRequest;
import org.example.deal_with_devil_csa.web.dto.ProblemPreviewResponse;
import org.example.deal_with_devil_csa.web.dto.ProblemRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Pure unit tests (no database, no Spring): repositories are mocked. */
@ExtendWith(MockitoExtension.class)
class ProblemServiceTest
{
    @Mock
    private ProblemRepository problemRepository;
    @Mock
    private ProblemHintRepository hintRepository;
    @Mock
    private RoundRepository roundRepository;
    @Mock
    private UserRepository userRepository;

    private ProblemService service;
    private Round previewRound;
    private Round biddingRound;

    @BeforeEach
    void setUp()
    {
        GameProperties props = new GameProperties(
                new GameProperties.Tiers(50, 100, 200),
                new GameProperties.Tiers(100, 200, 400),
                30,
                new GameProperties.Devil(100, 150),
                3);
        service = new ProblemService(problemRepository, hintRepository, roundRepository, props, userRepository);

        previewRound = round(1L, RoundPhase.PREVIEW);
        biddingRound = round(2L, RoundPhase.BIDDING);

        lenient().when(problemRepository.save(any(Problem.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(hintRepository.save(any(ProblemHint.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(hintRepository.findByProblemIdOrderByCorruptionLevelAsc(any())).thenReturn(List.of());
        lenient().when(roundRepository.findById(1L)).thenReturn(Optional.of(previewRound));
        lenient().when(roundRepository.findById(2L)).thenReturn(Optional.of(biddingRound));
    }

    // ---- create ----

    @Test
    void createStoresTrimmedFieldsAndLinksTheRound()
    {
        AdminProblemResponse response = service.create(
                request(Tier.SILVER, 2, 75L, "  Find the bug  ", 1L));

        assertThat(response.title()).isEqualTo("Find the bug".equals("x") ? "" : "Title");
        assertThat(response.tier()).isEqualTo(Tier.SILVER);
        assertThat(response.slots()).isEqualTo(2);
        assertThat(response.basePrice()).isEqualTo(75L);
        assertThat(response.teaser()).isEqualTo("Find the bug");
        assertThat(response.roundId()).isEqualTo(1L);
    }

    @Test
    void missingBasePriceFallsBackToTheTierDefault()
    {
        assertThat(service.create(request(Tier.BRONZE, 1, null, null, null)).basePrice()).isEqualTo(50L);
        assertThat(service.create(request(Tier.SILVER, 1, null, null, null)).basePrice()).isEqualTo(100L);
        assertThat(service.create(request(Tier.GOLD, 1, null, null, null)).basePrice()).isEqualTo(200L);
    }

    @Test
    void blankTeaserIsStoredAsNull()
    {
        assertThat(service.create(request(Tier.BRONZE, 1, null, "   ", null)).teaser()).isNull();
    }

    @Test
    void problemWithoutARoundStaysInTheBank()
    {
        assertThat(service.create(request(Tier.BRONZE, 1, null, null, null)).roundId()).isNull();
    }

    @Test
    void cannotAddAProblemToARoundPastPreview()
    {
        assertApiError(() -> service.create(request(Tier.BRONZE, 1, null, null, 2L)), ErrorCode.INVALID_PHASE);

        verify(problemRepository, never()).save(any());
    }

    @Test
    void addingToAnUnknownRoundGivesRoundNotFound()
    {
        assertApiError(() -> service.create(request(Tier.BRONZE, 1, null, null, 99L)), ErrorCode.ROUND_NOT_FOUND);
    }

    // ---- update / delete locking ----

    @Test
    void problemInAPreviewRoundCanBeUpdated()
    {
        Problem problem = problem(10L, previewRound);
        when(problemRepository.findById(10L)).thenReturn(Optional.of(problem));

        AdminProblemResponse response = service.update(10L, request(Tier.GOLD, 3, 500L, null, 1L));

        assertThat(response.tier()).isEqualTo(Tier.GOLD);
        assertThat(response.slots()).isEqualTo(3);
        assertThat(response.basePrice()).isEqualTo(500L);
    }

    @Test
    void problemInARoundPastPreviewIsLocked()
    {
        Problem problem = problem(10L, biddingRound);
        when(problemRepository.findById(10L)).thenReturn(Optional.of(problem));

        assertApiError(() -> service.update(10L, request(Tier.GOLD, 3, 500L, null, null)), ErrorCode.INVALID_PHASE);
        assertApiError(() -> service.delete(10L), ErrorCode.INVALID_PHASE);

        verify(problemRepository, never()).delete(any());
    }

    @Test
    void bankOnlyProblemCanAlwaysBeDeletedAndItsHintsGoWithIt()
    {
        Problem problem = problem(11L, null);
        when(problemRepository.findById(11L)).thenReturn(Optional.of(problem));

        service.delete(11L);

        verify(hintRepository).deleteByProblemId(11L);
        verify(problemRepository).delete(problem);
    }

    @Test
    void unknownProblemGivesProblemNotFound()
    {
        assertApiError(() -> service.get(404L), ErrorCode.PROBLEM_NOT_FOUND);
        assertApiError(() -> service.delete(404L), ErrorCode.PROBLEM_NOT_FOUND);
    }

    // ---- question preview ----

    @Test
    void previewIsStoredTrimmed_andBlankTextRemovesIt()
    {
        when(problemRepository.findById(10L)).thenReturn(Optional.of(problem(10L, previewRound)));

        assertThat(service.setPreview(10L, new ProblemPreviewRequest("  Given an array, find the pair  ")).preview())
                .isEqualTo("Given an array, find the pair");
        assertThat(service.setPreview(10L, new ProblemPreviewRequest("   ")).preview()).isNull();
        assertThat(service.setPreview(10L, new ProblemPreviewRequest("text")).preview()).isEqualTo("text");
        assertThat(service.setPreview(10L, new ProblemPreviewRequest(null)).preview()).isNull();
    }

    @Test
    void previewIsLockedOnceTheRoundMovedPastPreview()
    {
        when(problemRepository.findById(10L)).thenReturn(Optional.of(problem(10L, biddingRound)));

        assertApiError(() -> service.setPreview(10L, new ProblemPreviewRequest("new text")), ErrorCode.INVALID_PHASE);

        verify(problemRepository, never()).save(any());
    }

    @Test
    void previewOfAnUnknownProblemGivesProblemNotFound()
    {
        assertApiError(() -> service.setPreview(404L, new ProblemPreviewRequest("text")), ErrorCode.PROBLEM_NOT_FOUND);
    }

    @Test
    void participantsSeeThePreviewText()
    {
        Problem problem = problem(10L, previewRound);
        problem.setPreview("Given an array, find the pair");
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(previewRound));
        when(problemRepository.findByRoundIdOrderByIdAsc(1L)).thenReturn(List.of(problem));

        assertThat(service.currentRoundPreview().get(0).preview()).isEqualTo("Given an array, find the pair");
    }

    // ---- auto slots ----

    @Test
    void autoSlotsGivesEveryProblemItsShareOfThreeTimesTheParticipants()
    {
        // 32 participants and 10 problems: 96 slots, six problems with 10 and four with 9 (the first ones get the extra).
        List<Problem> problems = previewProblems(10);
        stubAutoSlots(problems, 32);

        AutoSlotsResponse response = service.autoSlots(false);

        assertThat(response.dryRun()).isFalse();
        assertThat(response.participants()).isEqualTo(32);
        assertThat(response.problemCount()).isEqualTo(10);
        assertThat(response.slotsNeeded()).isEqualTo(96);
        assertThat(response.slotsAssigned()).isEqualTo(96);
        assertThat(problems).extracting(Problem::getSlots).containsExactly(10, 10, 10, 10, 10, 10, 9, 9, 9, 9);
        assertThat(response.slotsPerProblem()).extracting(AutoSlotsResponse.ProblemSlots::slots)
                .containsExactly(10, 10, 10, 10, 10, 10, 9, 9, 9, 9);
        verify(problemRepository).saveAll(problems);
    }

    @Test
    void autoSlotsDryRunShowsThePlanAndSavesNothing()
    {
        // 50 participants and 7 problems: 150 slots = three problems with 22 and four with 21.
        List<Problem> problems = previewProblems(7);
        stubAutoSlots(problems, 50);

        AutoSlotsResponse response = service.autoSlots(true);

        assertThat(response.dryRun()).isTrue();
        assertThat(response.slotsAssigned()).isEqualTo(150);
        assertThat(response.slotsPerProblem()).extracting(AutoSlotsResponse.ProblemSlots::slots)
                .containsExactly(22, 22, 22, 21, 21, 21, 21);
        assertThat(problems).extracting(Problem::getSlots).containsOnly(1);   // untouched
        verify(problemRepository, never()).saveAll(any());
    }

    @Test
    void autoSlotsCanBePressedAgainAfterANewSignUp()
    {
        List<Problem> problems = previewProblems(7);
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(previewRound));
        when(problemRepository.findByRoundIdOrderByIdAsc(1L)).thenReturn(problems);
        when(userRepository.countCompleteParticipants()).thenReturn(5L, 6L);

        service.autoSlots(false);
        assertThat(problems).extracting(Problem::getSlots).containsExactly(3, 2, 2, 2, 2, 2, 2);   // 15 slots

        service.autoSlots(false);
        assertThat(problems).extracting(Problem::getSlots).containsExactly(3, 3, 3, 3, 2, 2, 2);   // 18 slots
    }

    @Test
    void autoSlotsOnlyWorksWhileTheRoundIsInPreview()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(biddingRound));

        assertApiError(() -> service.autoSlots(false), ErrorCode.INVALID_PHASE);

        verify(problemRepository, never()).saveAll(any());
    }

    @Test
    void autoSlotsWithNoRoundGivesRoundNotFound()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.empty());

        assertApiError(() -> service.autoSlots(false), ErrorCode.ROUND_NOT_FOUND);
    }

    @Test
    void autoSlotsNeedsAtLeastOneParticipant()
    {
        stubAutoSlots(previewProblems(3), 0);

        assertThatThrownBy(() -> service.autoSlots(false)).isInstanceOfSatisfying(ApiException.class, e ->
        {
            assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getDetails()).containsKey("participants");
        });

        verify(problemRepository, never()).saveAll(any());
    }

    @Test
    void autoSlotsNeedsAtLeastThreeProblems()
    {
        stubAutoSlots(previewProblems(2), 5);

        assertThatThrownBy(() -> service.autoSlots(false)).isInstanceOfSatisfying(ApiException.class, e ->
        {
            assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.getDetails()).containsKey("problems");
        });

        verify(problemRepository, never()).saveAll(any());
    }

    // ---- hints ----

    @Test
    void newHintIsCreatedForTheLevel()
    {
        when(problemRepository.findById(10L)).thenReturn(Optional.of(problem(10L, previewRound)));
        when(hintRepository.findByProblemIdAndCorruptionLevel(10L, 2)).thenReturn(Optional.empty());

        HintResponse response = service.upsertHint(10L, 2, new HintRequest("  Try a stack  ", " pw123 "));

        assertThat(response.corruptionLevel()).isEqualTo(2);
        assertThat(response.hintText()).isEqualTo("Try a stack");
        assertThat(response.password()).isEqualTo("pw123");
    }

    @Test
    void existingHintForTheLevelIsReplaced()
    {
        Problem problem = problem(10L, previewRound);
        ProblemHint existing = new ProblemHint(problem, 1);
        existing.setHintText("old");
        existing.setPassword("old");
        when(problemRepository.findById(10L)).thenReturn(Optional.of(problem));
        when(hintRepository.findByProblemIdAndCorruptionLevel(10L, 1)).thenReturn(Optional.of(existing));

        service.upsertHint(10L, 1, new HintRequest("new text", "new pw"));

        assertThat(existing.getHintText()).isEqualTo("new text");
        assertThat(existing.getPassword()).isEqualTo("new pw");
    }

    @Test
    void hintLevelMustBeBetweenOneAndThree()
    {
        assertApiError(() -> service.upsertHint(10L, 0, new HintRequest("t", "p")), ErrorCode.VALIDATION_FAILED);
        assertApiError(() -> service.upsertHint(10L, 4, new HintRequest("t", "p")), ErrorCode.VALIDATION_FAILED);

        verify(hintRepository, never()).save(any());
    }

    @Test
    void hintsCanStillBeEditedAfterTheRoundMovedOn()
    {
        Problem problem = problem(10L, biddingRound);
        when(problemRepository.findById(10L)).thenReturn(Optional.of(problem));
        when(hintRepository.findByProblemIdAndCorruptionLevel(10L, 3)).thenReturn(Optional.empty());

        assertThat(service.upsertHint(10L, 3, new HintRequest("fixed typo", "pw")).hintText())
                .isEqualTo("fixed typo");
    }

    // ---- participant preview ----

    @Test
    void previewListsTheCurrentRoundsProblemsWithoutSecrets()
    {
        Problem problem = problem(10L, previewRound);
        problem.setTeaser("A sneaky off-by-one");
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(previewRound));
        when(problemRepository.findByRoundIdOrderByIdAsc(1L)).thenReturn(List.of(problem));

        List<ProblemPreviewResponse> preview = service.currentRoundPreview();

        assertThat(preview).hasSize(1);
        assertThat(preview.get(0).title()).isEqualTo("Title");
        assertThat(preview.get(0).teaser()).isEqualTo("A sneaky off-by-one");
        assertThat(ProblemPreviewResponse.class.getRecordComponents())
                .extracting(c -> c.getName())
                .doesNotContain("hackerRankLink", "hints", "password");
    }

    @Test
    void previewWithNoRoundGivesRoundNotFound()
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.empty());

        assertApiError(() -> service.currentRoundPreview(), ErrorCode.ROUND_NOT_FOUND);
    }

    // ---- helpers ----

    private List<Problem> previewProblems(int count)
    {
        List<Problem> problems = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++)
        {
            problems.add(problem((long) i, previewRound));
        }
        return problems;
    }

    private void stubAutoSlots(List<Problem> problems, long participants)
    {
        when(roundRepository.findTopByOrderByNumberDesc()).thenReturn(Optional.of(previewRound));
        when(problemRepository.findByRoundIdOrderByIdAsc(1L)).thenReturn(problems);
        when(userRepository.countCompleteParticipants()).thenReturn(participants);
    }

    private static ProblemRequest request(Tier tier, int slots, Long basePrice, String teaser, Long roundId)
    {
        return new ProblemRequest("  Title  ", " Debugging ", tier, slots, "https://www.hackerrank.com/x",
                basePrice, teaser, roundId);
    }

    private static Problem problem(Long id, Round round)
    {
        Problem problem = new Problem();
        problem.setId(id);
        problem.setTitle("Title");
        problem.setCategory("Debugging");
        problem.setTier(Tier.BRONZE);
        problem.setSlots(1);
        problem.setHackerRankLink("https://www.hackerrank.com/x");
        problem.setBasePrice(50L);
        problem.setRound(round);
        return problem;
    }

    private static Round round(Long id, RoundPhase phase)
    {
        Round round = new Round();
        round.setId(id);
        round.setNumber(id.intValue());
        round.setPhase(phase);
        return round;
    }

    private static void assertApiError(ThrowingCallable call, ErrorCode expected)
    {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(expected));
    }
}

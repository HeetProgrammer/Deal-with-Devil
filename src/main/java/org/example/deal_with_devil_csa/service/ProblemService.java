package org.example.deal_with_devil_csa.service;

import java.util.List;
import java.util.Map;

import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.ProblemHint;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.Tier;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.ProblemHintRepository;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.RoundRepository;
import org.example.deal_with_devil_csa.web.dto.AdminProblemResponse;
import org.example.deal_with_devil_csa.web.dto.HintRequest;
import org.example.deal_with_devil_csa.web.dto.HintResponse;
import org.example.deal_with_devil_csa.web.dto.ProblemPreviewResponse;
import org.example.deal_with_devil_csa.web.dto.ProblemRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The problem bank and the three hint versions per problem (admin), plus the read-only round preview
 * for participants.
 *
 * Rule: a problem that belongs to a round can only be changed, moved or deleted while that round is
 * still in PREVIEW, and problems can only be added to a round that is in PREVIEW. Once bidding opens,
 * the problem set and its prices are frozen. Hints and passwords can be corrected at any time.
 */
@Service
@RequiredArgsConstructor
public class ProblemService
{
    private final ProblemRepository problemRepository;
    private final ProblemHintRepository hintRepository;
    private final RoundRepository roundRepository;
    private final GameProperties gameProperties;

    // ---- Admin: problem bank ----

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public AdminProblemResponse create(ProblemRequest request)
    {
        Problem problem = new Problem();
        apply(problem, request, resolveTargetRound(request.roundId()));
        problem = problemRepository.save(problem);
        return toAdminResponse(problem);
    }

    /** Full replace. A missing basePrice falls back to the configured price for the tier. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public AdminProblemResponse update(Long problemId, ProblemRequest request)
    {
        Problem problem = load(problemId);
        requireEditable(problem);
        apply(problem, request, resolveTargetRound(request.roundId()));
        return toAdminResponse(problemRepository.save(problem));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long problemId)
    {
        Problem problem = load(problemId);
        requireEditable(problem);
        hintRepository.deleteByProblemId(problemId);
        problemRepository.delete(problem);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public AdminProblemResponse get(Long problemId)
    {
        return toAdminResponse(load(problemId));
    }

    /** The whole bank, or only one round's problems. Small data set, so one hint query per problem is fine. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<AdminProblemResponse> list(Long roundId)
    {
        List<Problem> problems = roundId == null
                ? problemRepository.findAllByOrderByIdAsc()
                : problemRepository.findByRoundIdOrderByIdAsc(roundId);
        return problems.stream().map(this::toAdminResponse).toList();
    }

    // ---- Admin: hints ----

    /** Creates or replaces the hint version for one corruption level (1 to 3). */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public HintResponse upsertHint(Long problemId, int corruptionLevel, HintRequest request)
    {
        if (corruptionLevel < User.MIN_CORRUPTION_LEVEL || corruptionLevel > User.MAX_CORRUPTION_LEVEL)
        {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                    Map.of("level", "Must be between " + User.MIN_CORRUPTION_LEVEL + " and "
                            + User.MAX_CORRUPTION_LEVEL + "."));
        }

        Problem problem = load(problemId);
        ProblemHint hint = hintRepository.findByProblemIdAndCorruptionLevel(problemId, corruptionLevel)
                .orElseGet(() -> new ProblemHint(problem, corruptionLevel));
        hint.setHintText(request.hintText().trim());
        hint.setPassword(request.password().trim());
        return HintResponse.from(hintRepository.save(hint));
    }

    // ---- Participant: round preview ----

    /** Preview (no link, no hints) of the current round's problems. */
    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<ProblemPreviewResponse> currentRoundPreview()
    {
        Round round = roundRepository.findTopByOrderByNumberDesc()
                .orElseThrow(() -> new ApiException(ErrorCode.ROUND_NOT_FOUND, "No round has been started yet."));
        return problemRepository.findByRoundIdOrderByIdAsc(round.getId()).stream()
                .map(ProblemPreviewResponse::from)
                .toList();
    }

    // ---- Internals ----

    private Problem load(Long problemId)
    {
        return problemRepository.findById(problemId)
                .orElseThrow(() -> new ApiException(ErrorCode.PROBLEM_NOT_FOUND));
    }

    /** Null roundId means "bank only". Otherwise the round must exist and still be in PREVIEW. */
    private Round resolveTargetRound(Long roundId)
    {
        if (roundId == null)
        {
            return null;
        }
        Round round = roundRepository.findById(roundId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROUND_NOT_FOUND));
        if (round.getPhase() != RoundPhase.PREVIEW)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE,
                    "Problems can only be added to a round that is still in preview.");
        }
        return round;
    }

    private void requireEditable(Problem problem)
    {
        Round round = problem.getRound();
        if (round != null && round.getPhase() != RoundPhase.PREVIEW)
        {
            throw new ApiException(ErrorCode.INVALID_PHASE,
                    "This problem is locked because its round has moved past preview.");
        }
    }

    private void apply(Problem problem, ProblemRequest request, Round round)
    {
        problem.setTitle(request.title().trim());
        problem.setCategory(request.category().trim());
        problem.setTier(request.tier());
        problem.setSlots(request.slots());
        problem.setHackerRankLink(request.hackerRankLink().trim());
        problem.setBasePrice(request.basePrice() != null ? request.basePrice() : defaultBasePrice(request.tier()));
        String teaser = request.teaser() == null ? null : request.teaser().trim();
        problem.setTeaser(teaser == null || teaser.isEmpty() ? null : teaser);
        problem.setRound(round);
    }

    private long defaultBasePrice(Tier tier)
    {
        return switch (tier)
        {
            case BRONZE -> gameProperties.basePrice().bronze();
            case SILVER -> gameProperties.basePrice().silver();
            case GOLD -> gameProperties.basePrice().gold();
        };
    }

    private AdminProblemResponse toAdminResponse(Problem problem)
    {
        return AdminProblemResponse.from(problem,
                hintRepository.findByProblemIdOrderByCorruptionLevelAsc(problem.getId()));
    }
}

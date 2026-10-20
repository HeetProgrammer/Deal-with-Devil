package org.example.deal_with_devil_csa.service;

import java.util.Comparator;
import java.util.List;

import org.example.deal_with_devil_csa.domain.ProblemHint;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.ProblemHintRepository;
import org.example.deal_with_devil_csa.repository.ProblemRepository;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.web.dto.HintResponse;
import org.example.deal_with_devil_csa.web.dto.MeResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The admin's view of the participants: the list, the hint that matches a participant's corruption level,
 * and manual edits of the corruption level.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService
{
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final ProblemRepository problemRepository;
    private final ProblemHintRepository hintRepository;

    /** Every participant (not admins) with their coins and corruption level, sorted by name (unnamed last). */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<MeResponse> listParticipants()
    {
        return walletRepository.findAllByUserRole(Role.PARTICIPANT).stream()
                .sorted(Comparator
                        .comparing((Wallet w) -> w.getUser().getName() == null || w.getUser().getName().isBlank())
                        .thenComparing(w -> lower(w.getUser().getName()))
                        .thenComparing(w -> w.getUser().getId()))
                .map(w -> toMe(w.getUser(), w.getBalance()))
                .toList();
    }

    /** The hint version a participant is entitled to for a problem, going by their current corruption level. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public HintResponse hintFor(Long userId, Long problemId)
    {
        User user = loadUser(userId);
        if (!problemRepository.existsById(problemId))
        {
            throw new ApiException(ErrorCode.PROBLEM_NOT_FOUND);
        }
        ProblemHint hint = hintRepository.findByProblemIdAndCorruptionLevel(problemId, user.getCorruptionLevel())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "No hint has been written for corruption level " + user.getCorruptionLevel()
                                + " on this problem."));
        return HintResponse.from(hint);
    }

    /** Sets the corruption level directly. No coins move. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public MeResponse setCorruptionLevel(Long userId, int level)
    {
        if (level < User.MIN_CORRUPTION_LEVEL || level > User.MAX_CORRUPTION_LEVEL)
        {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                    java.util.Map.of("level", "Must be between " + User.MIN_CORRUPTION_LEVEL + " and "
                            + User.MAX_CORRUPTION_LEVEL + "."));
        }
        User user = loadUser(userId);
        user.setCorruptionLevel(level);
        long coins = walletRepository.findByUserId(userId).map(Wallet::getBalance).orElse(0L);
        return toMe(user, coins);
    }

    /**
     * Signs a participant out everywhere: bumps their token version, so every JWT issued earlier is rejected
     * (TOKEN_INVALID) the next time it is used. They can sign in again normally and get a token with the new version.
     * One atomic update, so a concurrent edit of the same user cannot overwrite it.
     */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void revokeSessions(Long userId)
    {
        if (userRepository.bumpTokenVersion(userId) == 0)
        {
            throw new ApiException(ErrorCode.USER_NOT_FOUND);
        }
    }

    // ---- Internals ----

    private User loadUser(Long userId)
    {
        return userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    private static MeResponse toMe(User user, long coins)
    {
        return new MeResponse(user.getId(), user.getEmail(), user.getName(), user.getHackerRankId(),
                user.getRole(), user.getCorruptionLevel(), coins, user.isProfileComplete());
    }

    private static String lower(String value)
    {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }
}

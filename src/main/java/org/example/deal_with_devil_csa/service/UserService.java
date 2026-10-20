package org.example.deal_with_devil_csa.service;

import java.util.Map;

import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.repository.UserRepository;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.web.dto.MeResponse;
import org.example.deal_with_devil_csa.web.dto.ProfileRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService
{
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;

    @Transactional(readOnly = true)
    @PreAuthorize("@guard.isSelf(#userId)")
    public MeResponse getMe(Long userId)
    {
        return toMe(loadUser(userId));
    }

    /** First fill of the mandatory form, and any later correction. */
    @Transactional
    @PreAuthorize("@guard.isSelf(#userId)")
    public MeResponse updateProfile(Long userId, ProfileRequest request)
    {
        User user = loadUser(userId);
        String name = request.name().trim();
        String hackerRankId = request.hackerRankId().trim();

        if (userRepository.existsByHackerRankIdIgnoreCaseAndIdNot(hackerRankId, userId))
        {
            throw new ApiException(ErrorCode.CONFLICT, "That HackerRank ID is already registered.",
                    Map.of("hackerRankId", "Already taken."));
        }

        user.setName(name);
        user.setHackerRankId(hackerRankId);
        return toMe(user);
    }

    private User loadUser(Long userId)
    {
        return userRepository.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
    }

    private MeResponse toMe(User user)
    {
        long coins = walletRepository.findByUserId(user.getId()).map(Wallet::getBalance).orElse(0L);
        return new MeResponse(user.getId(), user.getEmail(), user.getName(), user.getHackerRankId(),
                user.getRole(), user.getCorruptionLevel(), coins, user.isProfileComplete());
    }
}

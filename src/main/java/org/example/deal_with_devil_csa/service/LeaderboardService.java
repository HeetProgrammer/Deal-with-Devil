package org.example.deal_with_devil_csa.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.example.deal_with_devil_csa.repository.WalletRepository;
import org.example.deal_with_devil_csa.web.dto.LeaderboardEntry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/** Participants ranked by coins. Admins and people who have not finished their profile are not listed. */
@Service
@RequiredArgsConstructor
public class LeaderboardService
{
    private final WalletRepository walletRepository;

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<LeaderboardEntry> get()
    {
        List<Wallet> ranked = walletRepository.findAllByUserRole(Role.PARTICIPANT).stream()
                .filter(w -> w.getUser().isProfileComplete())
                .sorted(Comparator.comparingLong(Wallet::getBalance).reversed()
                        .thenComparing((Wallet w) -> w.getUser().getName().toLowerCase(java.util.Locale.ROOT))
                        .thenComparing((Wallet w) -> w.getUser().getId()))
                .toList();

        // Equal coins share a rank, and the next rank skips ahead (1, 2, 2, 4).
        List<LeaderboardEntry> entries = new ArrayList<>();
        int rank = 0;
        long previousCoins = 0;
        for (int i = 0; i < ranked.size(); i++)
        {
            Wallet wallet = ranked.get(i);
            if (i == 0 || wallet.getBalance() != previousCoins)
            {
                rank = i + 1;
            }
            previousCoins = wallet.getBalance();
            User user = wallet.getUser();
            entries.add(new LeaderboardEntry(rank, user.getId(), user.getName(), user.getHackerRankId(),
                    wallet.getBalance()));
        }
        return entries;
    }
}

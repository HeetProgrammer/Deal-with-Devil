package org.example.deal_with_devil_csa.web.dto;

/** One row of the leaderboard. Equal coin totals share a rank. */
public record LeaderboardEntry(int rank, Long userId, String name, String hackerRankId, long coins)
{
}

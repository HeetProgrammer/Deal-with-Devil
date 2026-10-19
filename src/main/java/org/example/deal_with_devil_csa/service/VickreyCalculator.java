package org.example.deal_with_devil_csa.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The Vickrey (second-price) rule for ONE problem, as a pure function with no database or Spring, so it can be
 * tested exhaustively.
 *
 * With k slots: the top k bids win, and every winner pays the (k+1)th highest bid, not their own. If there are
 * fewer than k+1 bids there is no (k+1)th bid, so winners pay the reserve price. The price is never below the
 * reserve. Ranking: higher amount first; equal amounts go to the earlier submission; if even the time is equal,
 * the lower bid id (so the result is always deterministic).
 */
public final class VickreyCalculator
{
    private VickreyCalculator()
    {
    }

    /** What the rule needs to know about one bid. */
    public record BidEntry(Long bidId, Long userId, long amount, Instant submittedAt)
    {
    }

    /** winners are in ranking order (best first). Every winner pays the same price. */
    public record Outcome(List<BidEntry> winners, long price)
    {
    }

    private static final Comparator<BidEntry> RANKING = Comparator
            .comparingLong(BidEntry::amount).reversed()
            .thenComparing(BidEntry::submittedAt)
            .thenComparing(BidEntry::bidId);

    public static Outcome resolve(List<BidEntry> bids, int slots, long reservePrice)
    {
        if (slots < 1)
        {
            throw new IllegalArgumentException("A problem needs at least one slot.");
        }

        List<BidEntry> ranked = new ArrayList<>(bids);
        ranked.sort(RANKING);

        List<BidEntry> winners = List.copyOf(ranked.subList(0, Math.min(slots, ranked.size())));
        long price = ranked.size() > slots
                ? Math.max(ranked.get(slots).amount(), reservePrice)
                : reservePrice;
        return new Outcome(winners, price);
    }
}

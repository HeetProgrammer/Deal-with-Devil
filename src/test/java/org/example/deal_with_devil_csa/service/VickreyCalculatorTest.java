package org.example.deal_with_devil_csa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.example.deal_with_devil_csa.service.VickreyCalculator.BidEntry;
import org.example.deal_with_devil_csa.service.VickreyCalculator.Outcome;
import org.junit.jupiter.api.Test;

/** The Vickrey rule is a pure function, so these tests need no database, no Spring and no mocks. */
class VickreyCalculatorTest
{
    private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");
    private static final long RESERVE = 40L;

    // ---- the worked example from the project context document ----

    @Test
    void topKBiddersWinAndAllPayTheKPlusOneHighestBid()
    {
        // k = 2, bids 90, 70, 50, 30: the top two win and both pay 50.
        List<BidEntry> bids = List.of(bid(1, 1, 90, 0), bid(2, 2, 70, 1), bid(3, 3, 50, 2), bid(4, 4, 30, 3));

        Outcome outcome = VickreyCalculator.resolve(bids, 2, RESERVE);

        assertThat(winnerBidIds(outcome)).containsExactly(1L, 2L);
        assertThat(outcome.price()).isEqualTo(50L);
    }

    @Test
    void winnersAreReturnedBestFirstWhateverTheInputOrder()
    {
        List<BidEntry> bids = List.of(bid(1, 1, 30, 0), bid(2, 2, 90, 1), bid(3, 3, 60, 2));

        Outcome outcome = VickreyCalculator.resolve(bids, 2, RESERVE);

        assertThat(winnerBidIds(outcome)).containsExactly(2L, 3L);
    }

    @Test
    void singleSlotIsAClassicSecondPriceAuction()
    {
        List<BidEntry> bids = List.of(bid(1, 1, 100, 0), bid(2, 2, 80, 1), bid(3, 3, 60, 2));

        Outcome outcome = VickreyCalculator.resolve(bids, 1, RESERVE);

        assertThat(winnerBidIds(outcome)).containsExactly(1L);
        assertThat(outcome.price()).isEqualTo(80L);
    }

    // ---- reserve price ----

    @Test
    void fewerThanKPlusOneBidsMeansWinnersPayTheReserve()
    {
        // k = 2 and only two bids: there is no third bid to set the price.
        List<BidEntry> bids = List.of(bid(1, 1, 90, 0), bid(2, 2, 70, 1));

        Outcome outcome = VickreyCalculator.resolve(bids, 2, RESERVE);

        assertThat(winnerBidIds(outcome)).containsExactly(1L, 2L);
        assertThat(outcome.price()).isEqualTo(RESERVE);
    }

    @Test
    void fewerBidsThanSlotsLeavesSlotsEmptyAndEveryBidderWins()
    {
        List<BidEntry> bids = List.of(bid(1, 1, 90, 0), bid(2, 2, 70, 1));

        Outcome outcome = VickreyCalculator.resolve(bids, 5, RESERVE);

        assertThat(outcome.winners()).hasSize(2);
        assertThat(outcome.price()).isEqualTo(RESERVE);
    }

    @Test
    void noBidsMeansNoWinnersAndTheReservePrice()
    {
        Outcome outcome = VickreyCalculator.resolve(List.of(), 3, RESERVE);

        assertThat(outcome.winners()).isEmpty();
        assertThat(outcome.price()).isEqualTo(RESERVE);
    }

    @Test
    void priceIsNeverBelowTheReserveEvenIfTheKPlusOneBidIsLower()
    {
        // The calculator is handed eligible bids only, but the floor must hold on its own.
        List<BidEntry> bids = List.of(bid(1, 1, 100, 0), bid(2, 2, 10, 1));

        Outcome outcome = VickreyCalculator.resolve(bids, 1, RESERVE);

        assertThat(outcome.price()).isEqualTo(RESERVE);
    }

    @Test
    void priceCanEqualTheReserveExactly()
    {
        List<BidEntry> bids = List.of(bid(1, 1, 100, 0), bid(2, 2, RESERVE, 1));

        assertThat(VickreyCalculator.resolve(bids, 1, RESERVE).price()).isEqualTo(RESERVE);
    }

    @Test
    void zeroReserveIsAllowedAndWinnersWithNoRivalPayNothing()
    {
        Outcome outcome = VickreyCalculator.resolve(List.of(bid(1, 1, 5, 0)), 1, 0L);

        assertThat(winnerBidIds(outcome)).containsExactly(1L);
        assertThat(outcome.price()).isZero();
    }

    // ---- ties ----

    @Test
    void equalBidsGoToTheEarlierSubmission()
    {
        List<BidEntry> bids = List.of(bid(1, 1, 80, 5), bid(2, 2, 80, 2));

        Outcome outcome = VickreyCalculator.resolve(bids, 1, RESERVE);

        assertThat(winnerBidIds(outcome)).containsExactly(2L);
        assertThat(outcome.price()).isEqualTo(80L); // the loser's equal bid sets the price
    }

    @Test
    void ifEvenTheTimeIsEqualTheLowerBidIdWins()
    {
        List<BidEntry> bids = List.of(bid(7, 1, 80, 3), bid(4, 2, 80, 3));

        Outcome outcome = VickreyCalculator.resolve(bids, 1, RESERVE);

        assertThat(winnerBidIds(outcome)).containsExactly(4L);
    }

    @Test
    void tieAcrossTheCutoffKeepsTheEarliestBiddersAndPricesAtTheTiedAmount()
    {
        // k = 2: three people bid 70. The two earliest win, the latest loses, everyone pays 70.
        List<BidEntry> bids = List.of(bid(1, 1, 70, 9), bid(2, 2, 70, 1), bid(3, 3, 70, 4), bid(4, 4, 20, 0));

        Outcome outcome = VickreyCalculator.resolve(bids, 2, RESERVE);

        assertThat(winnerBidIds(outcome)).containsExactly(2L, 3L);
        assertThat(outcome.price()).isEqualTo(70L);
    }

    @Test
    void resultDoesNotDependOnTheOrderTheBidsArriveIn()
    {
        BidEntry a = bid(1, 1, 90, 0);
        BidEntry b = bid(2, 2, 70, 1);
        BidEntry c = bid(3, 3, 70, 2);
        BidEntry d = bid(4, 4, 10, 3);

        Outcome forward = VickreyCalculator.resolve(List.of(a, b, c, d), 2, RESERVE);
        Outcome backward = VickreyCalculator.resolve(List.of(d, c, b, a), 2, RESERVE);

        assertThat(winnerBidIds(backward)).isEqualTo(winnerBidIds(forward));
        assertThat(backward.price()).isEqualTo(forward.price());
    }

    // ---- misc ----

    @Test
    void callersListIsNotModified()
    {
        List<BidEntry> immutable = List.of(bid(1, 1, 10, 0), bid(2, 2, 90, 1));

        // Would throw UnsupportedOperationException if the calculator sorted the caller's list in place.
        VickreyCalculator.resolve(immutable, 1, RESERVE);

        assertThat(immutable).extracting(BidEntry::bidId).containsExactly(1L, 2L);
    }

    @Test
    void aProblemNeedsAtLeastOneSlot()
    {
        assertThatThrownBy(() -> VickreyCalculator.resolve(List.of(bid(1, 1, 50, 0)), 0, RESERVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers ----

    private static BidEntry bid(long bidId, long userId, long amount, long secondsAfterStart)
    {
        return new BidEntry(bidId, userId, amount, T0.plusSeconds(secondsAfterStart));
    }

    private static List<Long> winnerBidIds(Outcome outcome)
    {
        return outcome.winners().stream().map(BidEntry::bidId).toList();
    }
}

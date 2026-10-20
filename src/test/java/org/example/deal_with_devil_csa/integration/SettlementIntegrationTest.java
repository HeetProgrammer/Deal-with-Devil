package org.example.deal_with_devil_csa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.example.deal_with_devil_csa.domain.LedgerType;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.Tier;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.domain.Wallet;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Settlement against the real database, with bids written straight into the tables so ties, several slots and
 * unaffordable winners can be set up exactly (the bid endpoint would refuse some of these situations, which is
 * why they are checked here and not in RoundFlowIntegrationTest). Also two "double click" races over HTTP.
 *
 * Same separate test database as the other integration tests; every test starts with empty tables.
 */
@SpringBootTest(properties = {IntegrationTestSupport.DB_URL_PROPERTY, IntegrationTestSupport.DDL_PROPERTY})
class SettlementIntegrationTest extends IntegrationTestSupport
{
    private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

    // ================= The Vickrey rules on real data =================

    @Test
    void tie_goesToTheEarlierBid_andTheWinnerPaysTheSecondPrice() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User early = saveUser(Role.PARTICIPANT, 500);
        User late = saveUser(Role.PARTICIPANT, 500);
        User third = saveUser(Role.PARTICIPANT, 500);
        Problem problem = saveProblem(saveRound(RoundPhase.SETTLEMENT), "Tie", Tier.BRONZE, 1, 50);

        // The late bid is stored first (lower id), so only the submission time can decide the tie.
        saveBid(late, problem, 100, T0.plusSeconds(10));
        saveBid(early, problem, 100, T0);
        saveBid(third, problem, 60, T0.plusSeconds(5));

        settle(admin)
                .andExpect(jsonPath("$.totalCollected").value(100))
                .andExpect(jsonPath("$.problems[0].bidCount").value(3))
                .andExpect(jsonPath("$.problems[0].winnerCount").value(1))
                .andExpect(jsonPath("$.problems[0].clearingPrice").value(100));

        assertThat(balanceOf(early)).isEqualTo(400);
        assertThat(balanceOf(late)).isEqualTo(500);
        assertThat(balanceOf(third)).isEqualTo(500);
        expectBidStatus(early, "WON");
        expectBidStatus(late, "LOST");
        expectBidStatus(third, "LOST");
    }

    @Test
    void severalSlots_everyWinnerPaysTheSameKPlusOneThPrice() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User first = saveUser(Role.PARTICIPANT, 500);
        User second = saveUser(Role.PARTICIPANT, 500);
        User third = saveUser(Role.PARTICIPANT, 500);
        User fourth = saveUser(Role.PARTICIPANT, 500);
        Problem problem = saveProblem(saveRound(RoundPhase.SETTLEMENT), "Two slots", Tier.SILVER, 2, 20);

        // The example from the project context: bids 90, 70, 50, 30 with k = 2. The top two win and both pay 50.
        saveBid(first, problem, 90, T0);
        saveBid(second, problem, 70, T0.plusSeconds(1));
        saveBid(third, problem, 50, T0.plusSeconds(2));
        saveBid(fourth, problem, 30, T0.plusSeconds(3));

        settle(admin)
                .andExpect(jsonPath("$.totalCollected").value(100))
                .andExpect(jsonPath("$.problems[0].winnerCount").value(2))
                .andExpect(jsonPath("$.problems[0].clearingPrice").value(50));

        assertThat(balanceOf(first)).isEqualTo(450);
        assertThat(balanceOf(second)).isEqualTo(450);
        assertThat(balanceOf(third)).isEqualTo(500);
        assertThat(balanceOf(fourth)).isEqualTo(500);

        String winners = bodyOf(send(admin, get("/api/admin/problems/" + problem.getId() + "/winners"), null)
                .andExpect(status().isOk()));
        assertThat(numbersAt(winners, "$[*].userId")).containsExactly(first.getId(), second.getId());
        assertThat(numbersAt(winners, "$[*].pricePaid")).containsExactly(50L, 50L);
    }

    @Test
    void reservePrice_isTheFloor_andBidsBelowItNeverWin() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User winner = saveUser(Role.PARTICIPANT, 500);
        User belowReserve = saveUser(Role.PARTICIPANT, 500);
        Problem problem = saveProblem(saveRound(RoundPhase.SETTLEMENT), "Reserve", Tier.BRONZE, 1, 50);

        // The bid endpoint refuses a bid below 50; written straight into the table it must still be ignored.
        saveBid(winner, problem, 80, T0);
        saveBid(belowReserve, problem, 40, T0.plusSeconds(1));

        // Only one eligible bid, so there is no second price: the winner pays the reserve, not 40 and not 80.
        settle(admin)
                .andExpect(jsonPath("$.totalCollected").value(50))
                .andExpect(jsonPath("$.problems[0].bidCount").value(2))
                .andExpect(jsonPath("$.problems[0].winnerCount").value(1))
                .andExpect(jsonPath("$.problems[0].clearingPrice").value(50));

        assertThat(balanceOf(winner)).isEqualTo(450);
        assertThat(balanceOf(belowReserve)).isEqualTo(500);
        expectBidStatus(winner, "WON");
        expectBidStatus(belowReserve, "LOST");
    }

    @Test
    void winnerWhoCanNoLongerPay_isMarkedUnaffordable_andTheSlotIsNotReassigned() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User broke = saveUser(Role.PARTICIPANT, 500);
        User runnerUp = saveUser(Role.PARTICIPANT, 500);
        Problem problem = saveProblem(saveRound(RoundPhase.SETTLEMENT), "Unaffordable", Tier.GOLD, 1, 50);

        saveBid(broke, problem, 100, T0);
        saveBid(runnerUp, problem, 70, T0.plusSeconds(1));

        // An admin deducts coins after the bid was placed: the winner would owe 70 but only has 10.
        Wallet wallet = walletRepository.findByUserId(broke.getId()).orElseThrow();
        wallet.setBalance(10);
        walletRepository.save(wallet);

        settle(admin)
                .andExpect(jsonPath("$.totalCollected").value(0))
                .andExpect(jsonPath("$.problems[0].winnerCount").value(0))
                .andExpect(jsonPath("$.problems[0].unaffordableCount").value(1));

        assertThat(balanceOf(broke)).isEqualTo(10);        // nothing was taken
        assertThat(balanceOf(runnerUp)).isEqualTo(500);    // and the runner-up did not inherit the slot
        expectBidStatus(broke, "UNAFFORDABLE");
        expectBidStatus(runnerUp, "LOST");
        assertThat(bodyOf(send(broke, get("/api/me/events"), null).andExpect(status().isOk())))
                .contains("BID_UNAFFORDABLE");
    }

    // ================= Double clicks and simultaneous requests over HTTP =================

    @RepeatedTest(5)
    void settleClickedTwiceAtOnce_chargesTheWinnerOnlyOnce() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User winner = saveUser(Role.PARTICIPANT, 500);
        User loser = saveUser(Role.PARTICIPANT, 500);
        Problem problem = saveProblem(saveRound(RoundPhase.SETTLEMENT), "Double click", Tier.BRONZE, 1, 50);
        saveBid(winner, problem, 100, T0);
        saveBid(loser, problem, 60, T0.plusSeconds(1));

        List<Integer> statuses = callInParallel(admin, 2, () -> post("/api/admin/rounds/current/settle"));

        // Exactly one request settles; the other is refused (already settled, or a concurrent-update conflict),
        // never a server error, and in particular the winner is charged once (60), not twice.
        assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
        assertThat(statuses).allMatch(s -> s < 500);
        assertThat(balanceOf(winner)).isEqualTo(440);
        long charges = ledgerEntryRepository.findByUserIdOrderByCreatedAtDesc(winner.getId()).stream()
                .filter(e -> e.getType() == LedgerType.BID_SETTLEMENT)
                .count();
        assertThat(charges).isEqualTo(1);
    }

    @RepeatedTest(5)
    void twoBidsSentAtTheSameMoment_cannotPromiseMoreThanTheBalance() throws Exception
    {
        User participant = saveUser(Role.PARTICIPANT, 100);
        Round round = saveRound(RoundPhase.BIDDING);
        Problem[] problems = {saveProblem(round, "A"), saveProblem(round, "B")};
        AtomicInteger next = new AtomicInteger();

        // 60 + 60 = 120 coins from a 100 coin wallet: one bid goes through, the other is refused.
        List<Integer> statuses = callInParallel(participant, 2, () -> put(
                "/api/rounds/current/problems/" + problems[next.getAndIncrement() % 2].getId() + "/bid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":60}"));

        Collections.sort(statuses);
        assertThat(statuses).containsExactly(200, 409);
        assertThat(bidRepository.findByUserAndRound(participant.getId(), round.getId())).hasSize(1);
    }

    // ================= Helpers =================

    private ResultActions settle(User admin) throws Exception
    {
        return send(admin, post("/api/admin/rounds/current/settle"), null).andExpect(status().isOk());
    }

    private void expectBidStatus(User participant, String expected) throws Exception
    {
        send(participant, get("/api/me/bids"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value(expected));
    }
}

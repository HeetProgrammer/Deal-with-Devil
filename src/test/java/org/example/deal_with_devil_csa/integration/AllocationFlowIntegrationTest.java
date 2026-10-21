package org.example.deal_with_devil_csa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.BidStatus;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.Tier;
import org.example.deal_with_devil_csa.domain.User;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.ResultActions;

/**
 * "Exactly 3 problems per person" (Phase 9) against the real database: the auto slots button, the block on opening
 * bidding, the cap of 3 wins in settlement, auto fill after settlement and manual assignment. Bids are written
 * straight into the tables (as in SettlementIntegrationTest) so every scenario is exact; the numbers in the comments
 * were worked out by hand from the rules in AllocationCalculator and AutoFillPlanner.
 *
 * Same separate test database as the other integration tests; every test starts with empty tables.
 */
@SpringBootTest(properties = {IntegrationTestSupport.DB_URL_PROPERTY, IntegrationTestSupport.DDL_PROPERTY})
class AllocationFlowIntegrationTest extends IntegrationTestSupport
{
    private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

    // ================= Auto slots =================

    @Test
    void autoSlots_countsOnlyCompleteProfiles_andCanBePressedAgainAfterANewSignUp() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        for (int i = 0; i < 5; i++)
        {
            saveUser(Role.PARTICIPANT, 100);
        }
        saveIncompleteUser();   // signed in but has not filled the profile form: not counted
        Round round = saveRound(RoundPhase.PREVIEW);
        for (int i = 1; i <= 7; i++)
        {
            saveProblem(round, "Problem " + i);   // one slot each to start with
        }

        // 5 participants x 3 = 15 slots over 7 problems: the first problem gets the extra one. A dry run saves nothing.
        autoSlots(admin, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.participants").value(5))
                .andExpect(jsonPath("$.problemCount").value(7))
                .andExpect(jsonPath("$.slotsNeeded").value(15))
                .andExpect(jsonPath("$.slotsAssigned").value(15))
                .andExpect(jsonPath("$.slotsPerProblem[0].slots").value(3))
                .andExpect(jsonPath("$.slotsPerProblem[1].slots").value(2));
        assertThat(slotsOf(round)).containsExactly(1, 1, 1, 1, 1, 1, 1);

        autoSlots(admin, false).andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(false));
        assertThat(slotsOf(round)).containsExactly(3, 2, 2, 2, 2, 2, 2);

        // A sixth participant signs up: 18 slots. Pressing again is safe and gives the new total, nothing hand-edited.
        saveUser(Role.PARTICIPANT, 100);
        autoSlots(admin, false).andExpect(status().isOk()).andExpect(jsonPath("$.slotsAssigned").value(18));
        assertThat(slotsOf(round)).containsExactly(3, 3, 3, 3, 2, 2, 2);
    }

    @Test
    void autoSlots_refusesBadSetups_andNeedsTheDryRunParameter() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        Round round = saveRound(RoundPhase.PREVIEW);
        saveProblem(round, "One");
        saveProblem(round, "Two");

        // Nobody has completed a profile yet.
        autoSlots(admin, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.participants").exists());

        // A participant, but only two problems: nobody can get three different problems out of two.
        User participant = saveUser(Role.PARTICIPANT, 100);
        autoSlots(admin, false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.problems").exists());

        // Participants cannot press the button.
        send(participant, post("/api/admin/rounds/current/slots/auto").param("dryRun", "false"), null)
                .andExpect(status().isForbidden());

        // A third problem and it works: 3 slots in total, one per problem.
        saveProblem(round, "Three");
        autoSlots(admin, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotsAssigned").value(3));
        assertThat(slotsOf(round)).containsExactly(1, 1, 1);

        // dryRun is required, so a forgotten parameter can never silently mean "apply".
        send(admin, post("/api/admin/rounds/current/slots/auto"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void autoSlots_onlyWorksInPreview() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        saveUser(Role.PARTICIPANT, 100);
        Round round = saveRound(RoundPhase.BIDDING);
        saveProblem(round, "One", Tier.BRONZE, 4, 50);

        autoSlots(admin, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));
        assertThat(slotsOf(round)).containsExactly(4);
    }

    @Test
    void openingBidding_isBlockedUntilThereAreEnoughSlots_thenWorks() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        saveUser(Role.PARTICIPANT, 100);
        saveUser(Role.PARTICIPANT, 100);
        Round round = saveRound(RoundPhase.PREVIEW);
        for (int i = 1; i <= 3; i++)
        {
            saveProblem(round, "Problem " + i);   // 3 slots in total, but 2 participants x 3 = 6 are needed
        }

        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));

        autoSlots(admin, false).andExpect(status().isOk());   // 6 slots over 3 problems = 2 each
        assertThat(slotsOf(round)).containsExactly(2, 2, 2);

        send(admin, post("/api/admin/rounds/current/advance"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("BIDDING"));
    }

    // ================= The cap of 3 wins in settlement =================

    @Test
    void settlement_capsEveryoneAtThreeWins_andCappedBidsNeverSetAPrice() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User a = saveUser(Role.PARTICIPANT, 1_000);
        User b = saveUser(Role.PARTICIPANT, 1_000);
        Round round = saveRound(RoundPhase.SETTLEMENT);

        // Five problems with one slot each (reserve 20). a bids 100, 90, 80, 70, 60 and b bids 50 on every problem.
        long[] aBids = {100, 90, 80, 70, 60};
        for (int i = 0; i < 5; i++)
        {
            Problem problem = saveProblem(round, "Problem " + (i + 1), Tier.BRONZE, 1, 20);
            saveBid(a, problem, aBids[i], T0.plusSeconds(i));
            saveBid(b, problem, 50, T0.plusSeconds(10 + i));
        }

        // a wins the three problems they bid highest on (price 50: b's bid was turned away at a full problem).
        // a's other two bids lose to the cap and must not set a price: b wins those two at the reserve price of 20.
        send(admin, post("/api/admin/rounds/current/settle"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCollected").value(190))
                .andExpect(jsonPath("$.problems[0].clearingPrice").value(50))
                .andExpect(jsonPath("$.problems[1].clearingPrice").value(50))
                .andExpect(jsonPath("$.problems[2].clearingPrice").value(50))
                .andExpect(jsonPath("$.problems[3].clearingPrice").value(20))
                .andExpect(jsonPath("$.problems[4].clearingPrice").value(20));

        assertThat(statusesOf(a, round)).containsExactly(
                BidStatus.WON, BidStatus.WON, BidStatus.WON, BidStatus.LOST, BidStatus.LOST);
        assertThat(statusesOf(b, round)).containsExactly(
                BidStatus.LOST, BidStatus.LOST, BidStatus.LOST, BidStatus.WON, BidStatus.WON);
        assertThat(balanceOf(a)).isEqualTo(850);   // 3 x 50
        assertThat(balanceOf(b)).isEqualTo(960);   // 2 x 20
    }

    // ================= Auto fill =================

    /**
     * Three participants, four problems with three slots each (12 slots, 9 needed). p1 bids 200/190/180/170 and p2 bids
     * 100 on all four (earlier problem first); p3 never bids. Settlement gives p1 and p2 the problems A, B and C at the
     * reserve price of 50 (their bids on D are capped out), so p3 has nothing and the unsold slots are A 1, B 1, C 1, D 3.
     */
    private record Settled(User admin, User p1, User p2, User p3, Round round, Problem a, Problem b, Problem c,
                           Problem d)
    {
    }

    private Settled settledRoundWhereP3NeverBid() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p1 = saveUser(Role.PARTICIPANT, 500);
        User p2 = saveUser(Role.PARTICIPANT, 500);
        User p3 = saveUser(Role.PARTICIPANT, 500);
        Round round = saveRound(RoundPhase.SETTLEMENT);
        Problem a = saveProblem(round, "Problem A", Tier.BRONZE, 3, 50);
        Problem b = saveProblem(round, "Problem B", Tier.BRONZE, 3, 50);
        Problem c = saveProblem(round, "Problem C", Tier.BRONZE, 3, 50);
        Problem d = saveProblem(round, "Problem D", Tier.BRONZE, 3, 50);

        Problem[] problems = {a, b, c, d};
        long[] p1Bids = {200, 190, 180, 170};
        for (int i = 0; i < 4; i++)
        {
            saveBid(p1, problems[i], p1Bids[i], T0.plusSeconds(i));
            saveBid(p2, problems[i], 100, T0.plusSeconds(10 + i));
        }

        send(admin, post("/api/admin/rounds/current/settle"), null).andExpect(status().isOk());
        assertThat(balanceOf(p1)).isEqualTo(350);
        assertThat(balanceOf(p2)).isEqualTo(350);
        assertThat(balanceOf(p3)).isEqualTo(500);
        return new Settled(admin, p1, p2, p3, round, a, b, c, d);
    }

    @Test
    void autoFill_givesTheSilentParticipantTheirThreeProblems_atTheReservePrice() throws Exception
    {
        Settled s = settledRoundWhereP3NeverBid();

        // The allocation view flags p3 first (0 of 3) and shows the unsold slots.
        send(s.admin(), get("/api/admin/rounds/current/allocation"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atTargetCount").value(2))
                .andExpect(jsonPath("$.participants[0].userId").value(s.p3().getId().intValue()))
                .andExpect(jsonPath("$.participants[0].wins").value(0))
                .andExpect(jsonPath("$.participants[0].missing").value(3))
                .andExpect(jsonPath("$.problems[0].unsold").value(1))
                .andExpect(jsonPath("$.problems[3].unsold").value(3));

        // Dry run: p3 gets D first (most unsold slots), then A, then B (equal unsold slots: lower id first). Nothing is saved.
        autoFill(s.admin(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.assignments.length()").value(3))
                .andExpect(jsonPath("$.assignments[0].problemId").value(s.d().getId().intValue()))
                .andExpect(jsonPath("$.assignments[1].problemId").value(s.a().getId().intValue()))
                .andExpect(jsonPath("$.assignments[2].problemId").value(s.b().getId().intValue()))
                .andExpect(jsonPath("$.assignments[0].price").value(50))
                .andExpect(jsonPath("$.stillShort.length()").value(0));
        assertThat(balanceOf(s.p3())).isEqualTo(500);
        assertThat(bidRepository.findByUserAndRound(s.p3().getId(), s.round().getId())).isEmpty();

        // The real press does the same.
        autoFill(s.admin(), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.assignments.length()").value(3))
                .andExpect(jsonPath("$.stillShort.length()").value(0));
        assertThat(balanceOf(s.p3())).isEqualTo(350);
        List<Bid> fills = bidRepository.findByUserAndRound(s.p3().getId(), s.round().getId());
        assertThat(fills).hasSize(3);
        assertThat(fills).allMatch(bid -> bid.getStatus() == BidStatus.WON && bid.getPricePaid() == 50L);

        // Like a normal win: in "my problems" with the link, in the ledger naming the admin, and as a pop-up.
        send(s.p3(), get("/api/me/bids"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].status").value("WON"))
                .andExpect(jsonPath("$[0].pricePaid").value(50));
        send(s.admin(), get("/api/admin/ledger?userId=" + s.p3().getId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("BID_SETTLEMENT"))
                .andExpect(jsonPath("$[0].amount").value(-50))
                .andExpect(jsonPath("$[0].actorName").value(s.admin().getName()));
        assertThat(bodyOf(send(s.p3(), get("/api/me/events"), null).andExpect(status().isOk()))).contains("BID_WON");

        // Everybody is at 3 now, and pressing again changes nothing.
        send(s.admin(), get("/api/admin/rounds/current/allocation"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atTargetCount").value(3));
        autoFill(s.admin(), false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignments.length()").value(0));
        assertThat(balanceOf(s.p3())).isEqualTo(350);
    }

    @Test
    void autoFill_onlyWorksInTheSolvingPhase_andNotForParticipants() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User participant = saveUser(Role.PARTICIPANT, 100);
        saveRound(RoundPhase.SETTLEMENT);

        autoFill(admin, false)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));
        autoFill(participant, false).andExpect(status().isForbidden());
    }

    @RepeatedTest(3)
    void autoFillClickedTwiceAtOnce_fillsEveryoneOnlyOnce() throws Exception
    {
        Settled s = settledRoundWhereP3NeverBid();

        List<Integer> statuses = callInParallel(s.admin(), 2,
                () -> post("/api/admin/rounds/current/auto-fill").param("dryRun", "false"));

        // Whichever request gets the round lock first does the work; the other either sees nothing left to do (200)
        // or is turned away as a concurrent update (409). Never a server error, and p3 is topped up exactly once.
        assertThat(statuses).allMatch(code -> code == 200 || code == 409);
        assertThat(statuses).contains(200);
        assertThat(bidRepository.findByUserAndRound(s.p3().getId(), s.round().getId())).hasSize(3);
        assertThat(balanceOf(s.p3())).isEqualTo(350);
    }

    // ================= Manual assignment =================

    @Test
    void manualAssignment_followsTheRules() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p = saveUser(Role.PARTICIPANT, 100);
        User q = saveUser(Role.PARTICIPANT, 1_000);
        Round round = saveRound(RoundPhase.SOLVING);
        Problem a = saveProblem(round, "A", Tier.BRONZE, 1, 50);
        Problem b = saveProblem(round, "B", Tier.SILVER, 1, 80);
        Problem c = saveProblem(round, "C", Tier.BRONZE, 5, 10);
        Problem d = saveProblem(round, "D", Tier.BRONZE, 5, 10);
        Problem e = saveProblem(round, "E", Tier.BRONZE, 5, 10);

        // p is given A at its reserve price of 50: coins, a WON bid, a ledger entry naming the admin.
        assign(admin, p, a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePaid").value(50))
                .andExpect(jsonPath("$.balanceAfter").value(50))
                .andExpect(jsonPath("$.winsNow").value(1))
                .andExpect(jsonPath("$.overCapacity").value(false));
        assertThat(statusesOf(p, round)).containsExactly(BidStatus.WON);
        send(admin, get("/api/admin/ledger?userId=" + p.getId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("BID_SETTLEMENT"))
                .andExpect(jsonPath("$[0].amount").value(-50))
                .andExpect(jsonPath("$[0].actorName").value(admin.getName()));

        // The same problem twice is refused and costs nothing.
        assign(admin, p, a)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(balanceOf(p)).isEqualTo(50);

        // B costs 80 and p has 50: refused, and nothing changes (no bid row, no coins).
        assign(admin, p, b)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
        assertThat(balanceOf(p)).isEqualTo(50);
        assertThat(statusesOf(p, round)).hasSize(1);

        // A has one slot and p already took it: giving it to q as well goes over capacity, on purpose.
        assign(admin, q, a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(950))
                .andExpect(jsonPath("$.winsNow").value(1))
                .andExpect(jsonPath("$.overCapacity").value(true));

        // p reaches 3 problems (A, C, D); a fourth is refused.
        assign(admin, p, c)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winsNow").value(2))
                .andExpect(jsonPath("$.overCapacity").value(false));
        assign(admin, p, d)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winsNow").value(3))
                .andExpect(jsonPath("$.balanceAfter").value(30));
        assign(admin, p, e)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(balanceOf(p)).isEqualTo(30);

        // Unknown or wrong targets, a bad body, and a participant trying it.
        send(admin, post("/api/admin/rounds/current/assignments"),
                "{\"userId\":" + q.getId() + ",\"problemId\":999999}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROBLEM_NOT_FOUND"));
        send(admin, post("/api/admin/rounds/current/assignments"),
                "{\"userId\":999999,\"problemId\":" + c.getId() + "}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        assign(admin, admin, c)   // an admin is not a participant
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        send(admin, post("/api/admin/rounds/current/assignments"), "{\"problemId\":" + c.getId() + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.userId").exists());
        assign(q, q, c).andExpect(status().isForbidden());
    }

    @Test
    void manualAssignment_onlyWorksInTheSolvingPhase() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p = saveUser(Role.PARTICIPANT, 100);
        Round round = saveRound(RoundPhase.SETTLEMENT);
        Problem a = saveProblem(round, "A", Tier.BRONZE, 1, 50);

        assign(admin, p, a)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_PHASE"));
        assertThat(balanceOf(p)).isEqualTo(100);
    }

    // ================= Bid coverage =================

    @Test
    void bidCoverage_listsParticipantsFewestBidsFirst_andSkipsIncompleteProfiles() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p1 = saveUser(Role.PARTICIPANT, 500);
        User p2 = saveUser(Role.PARTICIPANT, 500);
        saveIncompleteUser();   // has not filled the profile form: not listed
        Round round = saveRound(RoundPhase.BIDDING);
        Problem a = saveProblem(round, "Problem A", Tier.BRONZE, 2, 50);
        Problem b = saveProblem(round, "Problem B", Tier.BRONZE, 2, 50);
        saveProblem(round, "Problem C", Tier.BRONZE, 2, 50);
        saveBid(p1, a, 60, T0);
        saveBid(p1, b, 70, T0.plusSeconds(1));

        send(admin, get("/api/admin/rounds/current/bid-coverage"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problemCount").value(3))
                .andExpect(jsonPath("$.winsPerRound").value(3))
                .andExpect(jsonPath("$.participants.length()").value(2))
                .andExpect(jsonPath("$.participants[0].userId").value(p2.getId().intValue()))
                .andExpect(jsonPath("$.participants[0].bidCount").value(0))
                .andExpect(jsonPath("$.participants[0].missingBids").value(3))
                .andExpect(jsonPath("$.participants[1].userId").value(p1.getId().intValue()))
                .andExpect(jsonPath("$.participants[1].bidCount").value(2))
                .andExpect(jsonPath("$.participants[1].missingBids").value(1));

        send(p1, get("/api/admin/rounds/current/bid-coverage"), null).andExpect(status().isForbidden());
    }

    // ================= Helpers =================

    private ResultActions autoSlots(User who, boolean dryRun) throws Exception
    {
        return send(who, post("/api/admin/rounds/current/slots/auto").param("dryRun", String.valueOf(dryRun)), null);
    }

    private ResultActions autoFill(User who, boolean dryRun) throws Exception
    {
        return send(who, post("/api/admin/rounds/current/auto-fill").param("dryRun", String.valueOf(dryRun)), null);
    }

    private ResultActions assign(User caller, User target, Problem problem) throws Exception
    {
        return send(caller, post("/api/admin/rounds/current/assignments"),
                "{\"userId\":" + target.getId() + ",\"problemId\":" + problem.getId() + "}");
    }

    /** The slots of the round's problems, in id order. */
    private List<Integer> slotsOf(Round round)
    {
        return problemRepository.findByRoundIdOrderByIdAsc(round.getId()).stream().map(Problem::getSlots).toList();
    }

    /** The statuses of one participant's bids in the round, in problem id order. */
    private List<BidStatus> statusesOf(User participant, Round round)
    {
        return bidRepository.findByUserAndRound(participant.getId(), round.getId()).stream()
                .map(Bid::getStatus)
                .toList();
    }
}

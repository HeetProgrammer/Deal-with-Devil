package org.example.deal_with_devil_csa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.LedgerEntry;
import org.example.deal_with_devil_csa.domain.LedgerType;
import org.example.deal_with_devil_csa.domain.Problem;
import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.Round;
import org.example.deal_with_devil_csa.domain.RoundPhase;
import org.example.deal_with_devil_csa.domain.User;
import org.example.deal_with_devil_csa.exception.ApiException;
import org.example.deal_with_devil_csa.exception.ErrorCode;
import org.example.deal_with_devil_csa.security.CurrentUser;
import org.example.deal_with_devil_csa.service.AdminLedgerService;
import org.example.deal_with_devil_csa.service.AuditService;
import org.example.deal_with_devil_csa.service.AuthService;
import org.example.deal_with_devil_csa.service.BidService;
import org.example.deal_with_devil_csa.service.EventService;
import org.example.deal_with_devil_csa.service.RoundService;
import org.example.deal_with_devil_csa.service.UserService;
import org.example.deal_with_devil_csa.service.WalletService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The few integration tests that matter for an event where coins are real to the participants. They run against a
 * real PostgreSQL, because the things they check (row locks, the unique reversal rule, the security annotations on
 * the real beans) cannot be shown with mocks.
 *
 * They use their OWN database so the dev data is never touched: create it once with
 * {@code CREATE DATABASE deal_devil_test;} on the same Docker server (port 5433), or point TEST_DB_URL elsewhere.
 * The schema is created fresh for the run and dropped afterwards, and every test starts from empty tables.
 *
 * Three groups: simultaneous requests cannot break the coin rules; the @PreAuthorize checks hold on the real services;
 * and the HTTP layer answers 401/403 as documented (including a revoked session).
 */
@SpringBootTest(properties = {IntegrationTestSupport.DB_URL_PROPERTY, IntegrationTestSupport.DDL_PROPERTY})
class CoinSafetyIntegrationTest extends IntegrationTestSupport
{
    @Autowired private BidService bidService;
    @Autowired private WalletService walletService;
    @Autowired private UserService userService;
    @Autowired private EventService eventService;
    @Autowired private AuthService authService;
    @Autowired private RoundService roundService;
    @Autowired private AdminLedgerService adminLedgerService;
    @Autowired private AuditService auditService;

    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
    }

    // ================= 1. Simultaneous requests cannot break the coin rules =================

    @RepeatedTest(10)
    void twoSimultaneousBids_cannotPromiseMoreCoinsThanTheWalletHolds() throws Exception
    {
        User participant = saveUser(Role.PARTICIPANT, 100);
        Round round = saveRoundInBidding();
        Problem a = saveProblem(round, "A");
        Problem b = saveProblem(round, "B");

        // 60 + 60 = 120 coins promised from a 100 coin wallet: exactly one of the two may go through.
        List<Throwable> results = runTogether(participant, List.of(
                () -> bidService.placeBid(participant.getId(), a.getId(), 60),
                () -> bidService.placeBid(participant.getId(), b.getId(), 60)));

        assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
        Throwable failure = results.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).getCode()).isEqualTo(ErrorCode.BID_EXCEEDS_BALANCE);

        List<Bid> stored = bidRepository.findByUserAndRound(participant.getId(), round.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.stream().mapToLong(Bid::getAmount).sum()).isLessThanOrEqualTo(100);
    }

    @RepeatedTest(5)
    void twoSimultaneousUndoClicks_reverseTheEntryOnlyOnce() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User participant = saveUser(Role.PARTICIPANT, 0);

        signIn(admin);
        walletService.credit(participant.getId(), 500, LedgerType.CUSTOM, "seed", admin.getId());
        LedgerEntry target = walletService.credit(participant.getId(), 100, LedgerType.CUSTOM, "to undo", admin.getId());
        clearSecurityContext();

        List<Throwable> results = runTogether(admin, List.of(
                () -> walletService.reverse(target.getId(), "undo one", admin.getId()),
                () -> walletService.reverse(target.getId(), "undo two", admin.getId())));

        assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
        Throwable failure = results.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        assertThat(failure).isInstanceOf(ApiException.class);
        assertThat(((ApiException) failure).getCode()).isEqualTo(ErrorCode.CONFLICT);

        long reversals = ledgerEntryRepository.findByUserIdOrderByCreatedAtDesc(participant.getId()).stream()
                .filter(e -> e.getType() == LedgerType.REVERSAL)
                .count();
        assertThat(reversals).isEqualTo(1);
        assertThat(balanceOf(participant)).isEqualTo(500);   // 600 minus the one undone entry of 100
    }

    // ================= 2. The @PreAuthorize checks hold on the real services =================

    @Test
    void participantCannotReadAnotherParticipantsData()
    {
        User me = saveUser(Role.PARTICIPANT, 100);
        User other = saveUser(Role.PARTICIPANT, 100);
        signIn(me);

        assertThatThrownBy(() -> userService.getMe(other.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> walletService.history(other.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bidService.myBids(other.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> eventService.feed(other.getId(), null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> authService.refresh(other.getId())).isInstanceOf(AccessDeniedException.class);

        // Their own data is fine.
        assertThat(userService.getMe(me.getId())).isNotNull();
        assertThat(walletService.history(me.getId())).isEmpty();
    }

    @Test
    void participantCannotUseAdminOperations()
    {
        User me = saveUser(Role.PARTICIPANT, 100);
        signIn(me);

        assertThatThrownBy(() -> walletService.credit(me.getId(), 1_000, LedgerType.CUSTOM, "free coins", me.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> roundService.start()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> adminLedgerService.recent(null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> auditService.recent(null, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> bidService.winners(1L)).isInstanceOf(AccessDeniedException.class);

        assertThat(balanceOf(me)).isEqualTo(100);
    }

    @Test
    void adminCannotPlaceBids()
    {
        User admin = saveUser(Role.ADMIN, 1_000);
        signIn(admin);

        assertThatThrownBy(() -> bidService.placeBid(admin.getId(), 1L, 60)).isInstanceOf(AccessDeniedException.class);
    }

    // ================= 3. The HTTP layer answers as documented =================

    @Test
    void withoutToken_adminRouteAnswers401() throws Exception
    {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void participantToken_isRefusedOnAdminRoutes_with403() throws Exception
    {
        User participant = saveUser(Role.PARTICIPANT, 100);

        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(participant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/api/admin/rounds/current/settle").header(HttpHeaders.AUTHORIZATION, bearer(participant)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminToken_isRefusedOnBidding_with403() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);

        mockMvc.perform(put("/api/rounds/current/problems/1/bid")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":60}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void participantToken_readsOnlyTheirOwnProfile() throws Exception
    {
        User participant = saveUser(Role.PARTICIPANT, 100);

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(participant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(participant.getEmail()));
    }

    @Test
    void revokeSessions_makesEveryEarlierTokenStopWorking() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User participant = saveUser(Role.PARTICIPANT, 100);
        String oldToken = bearer(participant);

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, oldToken)).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/users/" + participant.getId() + "/revoke-sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));

        // A token issued after the revocation works again.
        User reloaded = userRepository.findById(participant.getId()).orElseThrow();
        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(reloaded)))
                .andExpect(status().isOk());
    }

    // ================= Helpers =================

    /** Runs all actions at the same moment, each on its own thread signed in as the caller. Null = it succeeded. */
    private List<Throwable> runTogether(User caller, List<Runnable> actions) throws Exception
    {
        ExecutorService pool = Executors.newFixedThreadPool(actions.size());
        try
        {
            CountDownLatch ready = new CountDownLatch(actions.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Runnable action : actions)
            {
                futures.add(pool.submit((Callable<Throwable>) () ->
                {
                    signIn(caller);   // the security context is per thread
                    ready.countDown();
                    try
                    {
                        go.await();
                        action.run();
                        return null;
                    }
                    catch (Throwable t)
                    {
                        return t;
                    }
                    finally
                    {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            ready.await();
            go.countDown();

            List<Throwable> results = new ArrayList<>();
            for (Future<Throwable> future : futures)
            {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
        finally
        {
            pool.shutdownNow();
        }
    }

    private Round saveRoundInBidding()
    {
        return saveRound(RoundPhase.BIDDING);
    }

    private static void signIn(User user)
    {
        CurrentUser principal = new CurrentUser(user.getId(), user.getEmail(), user.getRole(), true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }
}

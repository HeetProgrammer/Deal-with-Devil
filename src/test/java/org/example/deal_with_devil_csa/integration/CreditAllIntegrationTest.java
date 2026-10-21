package org.example.deal_with_devil_csa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.example.deal_with_devil_csa.domain.Role;
import org.example.deal_with_devil_csa.domain.User;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The "credit everyone" admin button (Phase 9, step 11) against the real database: every participant is credited
 * once with their own ledger entry and pop-up, admins are left out, a repeated request id credits nobody twice, and
 * bad requests change nothing.
 *
 * Same separate test database as the other integration tests; every test starts with empty tables.
 */
@SpringBootTest(properties = {IntegrationTestSupport.DB_URL_PROPERTY, IntegrationTestSupport.DDL_PROPERTY})
class CreditAllIntegrationTest extends IntegrationTestSupport
{
    @Test
    void creditAll_creditsEveryParticipantOnce_andLeavesAdminsOut() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User otherAdmin = saveUser(Role.ADMIN, 5);
        User p1 = saveUser(Role.PARTICIPANT, 0);
        User p2 = saveUser(Role.PARTICIPANT, 40);
        User p3 = saveUser(Role.PARTICIPANT, 1_000);

        creditAll(admin, 100, "Round 2 top-up", "req-credit-0001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants").value(3))
                .andExpect(jsonPath("$.amountEach").value(100))
                .andExpect(jsonPath("$.totalCredited").value(300));

        assertThat(balanceOf(p1)).isEqualTo(100);
        assertThat(balanceOf(p2)).isEqualTo(140);
        assertThat(balanceOf(p3)).isEqualTo(1_100);
        assertThat(balanceOf(admin)).isEqualTo(0);
        assertThat(balanceOf(otherAdmin)).isEqualTo(5);

        // Each participant has their own ledger entry naming the admin and the reason, and their own pop-up.
        send(admin, get("/api/admin/ledger?userId=" + p2.getId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("CUSTOM"))
                .andExpect(jsonPath("$[0].amount").value(100))
                .andExpect(jsonPath("$[0].balanceAfter").value(140))
                .andExpect(jsonPath("$[0].reason").value("Round 2 top-up"))
                .andExpect(jsonPath("$[0].actorName").value(admin.getName()));
        send(admin, get("/api/admin/ledger?userId=" + admin.getId()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertThat(bodyOf(send(p1, get("/api/me/events"), null).andExpect(status().isOk())))
                .contains("CUSTOM_ADJUSTMENT");
        assertThat(bulkCreditRepository.count()).isEqualTo(1);
    }

    @Test
    void creditAll_sameRequestIdTwice_creditsNobodyAgain() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p1 = saveUser(Role.PARTICIPANT, 10);
        User p2 = saveUser(Role.PARTICIPANT, 20);

        creditAll(admin, 50, "Top-up", "req-credit-0002").andExpect(status().isOk());

        creditAll(admin, 50, "Top-up", "req-credit-0002")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertThat(balanceOf(p1)).isEqualTo(60);
        assertThat(balanceOf(p2)).isEqualTo(70);
        assertThat(bulkCreditRepository.count()).isEqualTo(1);

        // A new request id is a new, deliberate press.
        creditAll(admin, 50, "Top-up", "req-credit-0003").andExpect(status().isOk());
        assertThat(balanceOf(p1)).isEqualTo(110);
        assertThat(bulkCreditRepository.count()).isEqualTo(2);
    }

    @RepeatedTest(3)
    void creditAll_clickedTwiceAtOnce_creditsOnlyOnce() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User p1 = saveUser(Role.PARTICIPANT, 10);
        User p2 = saveUser(Role.PARTICIPANT, 20);

        List<Integer> statuses = callInParallel(admin, 2, () -> post("/api/admin/wallet/credit-all")
                .contentType(MediaType.APPLICATION_JSON)
                .content(creditAllBody(25, "Double click", "req-credit-0004")));

        // One request does the work; the other is refused as already applied (409). Never credited twice.
        assertThat(statuses).allMatch(code -> code == 200 || code == 409);
        assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(1);
        assertThat(balanceOf(p1)).isEqualTo(35);
        assertThat(balanceOf(p2)).isEqualTo(45);
        assertThat(bulkCreditRepository.count()).isEqualTo(1);
    }

    @Test
    void creditAll_refusesBadRequests_andChangesNothing() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);
        User participant = saveUser(Role.PARTICIPANT, 10);

        // Participants cannot press the button.
        creditAll(participant, 50, "Sneaky", "req-credit-0005").andExpect(status().isForbidden());

        // The amount must be positive.
        creditAll(admin, 0, "Nothing", "req-credit-0006")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.amount").exists());
        creditAll(admin, -5, "Negative", "req-credit-0007")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // A reason and a proper request id are required.
        creditAll(admin, 50, "  ", "req-credit-0008")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.reason").exists());
        creditAll(admin, 50, "Short id", "abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.requestId").exists());

        assertThat(balanceOf(participant)).isEqualTo(10);
        assertThat(bulkCreditRepository.count()).isEqualTo(0);
    }

    @Test
    void creditAll_withNoParticipants_isRefused() throws Exception
    {
        User admin = saveUser(Role.ADMIN, 0);

        creditAll(admin, 50, "Nobody here", "req-credit-0009")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.participants").exists());
        assertThat(bulkCreditRepository.count()).isEqualTo(0);
    }

    // ---- Helpers ----

    private ResultActions creditAll(User who, long amount, String reason, String requestId) throws Exception
    {
        return send(who, post("/api/admin/wallet/credit-all"), creditAllBody(amount, reason, requestId));
    }

    private static String creditAllBody(long amount, String reason, String requestId)
    {
        return "{\"amount\":" + amount + ",\"reason\":\"" + reason + "\",\"requestId\":\"" + requestId + "\"}";
    }
}

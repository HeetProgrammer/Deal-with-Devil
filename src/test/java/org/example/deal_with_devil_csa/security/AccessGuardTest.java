package org.example.deal_with_devil_csa.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.example.deal_with_devil_csa.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Unit tests of the ownership and role checks behind @PreAuthorize: just the security context, no Spring context. */
class AccessGuardTest
{
    private static final long ME = 5L;
    private static final long SOMEONE_ELSE = 6L;

    private final AccessGuard guard = new AccessGuard();

    @AfterEach
    void clearSecurityContext()
    {
        SecurityContextHolder.clearContext();
    }

    // ---- isSelf ----

    @Test
    void isSelf_trueForOwnId()
    {
        signInAs(ME, Role.PARTICIPANT);

        assertThat(guard.isSelf(ME)).isTrue();
    }

    @Test
    void isSelf_falseForAnotherId()
    {
        signInAs(ME, Role.PARTICIPANT);

        assertThat(guard.isSelf(SOMEONE_ELSE)).isFalse();
    }

    @Test
    void isSelf_falseForAdminLookingAtAnotherId()
    {
        signInAs(ME, Role.ADMIN);

        assertThat(guard.isSelf(SOMEONE_ELSE)).isFalse();
    }

    @Test
    void isSelf_falseForMissingId()
    {
        signInAs(ME, Role.PARTICIPANT);

        assertThat(guard.isSelf(null)).isFalse();
    }

    @Test
    void isSelf_falseWhenNobodyIsSignedIn()
    {
        assertThat(guard.isSelf(ME)).isFalse();
    }

    // ---- isAdmin ----

    @Test
    void isAdmin_trueForAdmin()
    {
        signInAs(ME, Role.ADMIN);

        assertThat(guard.isAdmin()).isTrue();
    }

    @Test
    void isAdmin_falseForParticipant()
    {
        signInAs(ME, Role.PARTICIPANT);

        assertThat(guard.isAdmin()).isFalse();
    }

    @Test
    void isAdmin_falseWhenNobodyIsSignedIn()
    {
        assertThat(guard.isAdmin()).isFalse();
    }

    // ---- isSelfOrAdmin ----

    @Test
    void isSelfOrAdmin_participantOnlyForOwnId()
    {
        signInAs(ME, Role.PARTICIPANT);

        assertThat(guard.isSelfOrAdmin(ME)).isTrue();
        assertThat(guard.isSelfOrAdmin(SOMEONE_ELSE)).isFalse();
    }

    @Test
    void isSelfOrAdmin_adminForAnyId()
    {
        signInAs(ME, Role.ADMIN);

        assertThat(guard.isSelfOrAdmin(SOMEONE_ELSE)).isTrue();
        assertThat(guard.isSelfOrAdmin(null)).isTrue();
    }

    @Test
    void isSelfOrAdmin_falseWhenNobodyIsSignedIn()
    {
        assertThat(guard.isSelfOrAdmin(ME)).isFalse();
    }

    // ---- a principal that is not our CurrentUser never grants access ----

    @Test
    void foreignPrincipalGrantsNothing()
    {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "some-string-principal", null, java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThat(guard.isSelf(ME)).isFalse();
        assertThat(guard.isAdmin()).isFalse();
        assertThat(guard.isSelfOrAdmin(ME)).isFalse();
    }

    // ---- Helper ----

    private static void signInAs(long id, Role role)
    {
        CurrentUser principal = new CurrentUser(id, "user" + id + "@example.com", role, true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))));
    }
}

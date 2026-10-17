package org.example.deal_with_devil_csa.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A person signed in with Google. Table is named "users" because "user" is reserved in PostgreSQL.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User
{
    public static final int MIN_CORRUPTION_LEVEL = 1;
    public static final int MAX_CORRUPTION_LEVEL = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** Google's stable user id (the "sub" claim). Set at first sign-in, never changes even if the email does. */
    @Column(nullable = false, unique = true)
    private String googleId;

    /** Filled in by the mandatory first-login form, so it is empty until then. */
    private String name;

    /**
     * HackerRank username, mandatory for taking part. Nullable in the database only because the row is created
     * at Google sign-in, before the form is submitted; the backend blocks everything until it is set.
     */
    @Column(unique = true)
    private String hackerRankId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.PARTICIPANT;

    /** 1 to 3. Decides which hint version the participant gets. */
    @Column(nullable = false)
    private int corruptionLevel = MIN_CORRUPTION_LEVEL;

    /** Bump this to invalidate every JWT issued earlier for this user (instant revocation). */
    @Column(nullable = false)
    private int tokenVersion = 0;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** True once the mandatory first-login form is done. Everything except the profile endpoints needs this. */
    public boolean isProfileComplete()
    {
        return name != null && !name.isBlank() && hackerRankId != null && !hackerRankId.isBlank();
    }

    @PrePersist
    void onCreate()
    {
        if (createdAt == null)
        {
            createdAt = Instant.now();
        }
    }
}

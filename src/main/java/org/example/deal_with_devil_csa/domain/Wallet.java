package org.example.deal_with_devil_csa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One wallet per user. Balance is whole coins in a long. The @Version column makes two
 * simultaneous updates fail with an optimistic-lock error (mapped to CONCURRENT_UPDATE)
 * instead of silently losing one of them.
 */
@Entity
@Table(name = "wallets")
@Getter
@Setter
@NoArgsConstructor
public class Wallet
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false)
    private long balance = 0L;

    @Version
    private long version;

    public Wallet(User user)
    {
        this.user = user;
    }
}

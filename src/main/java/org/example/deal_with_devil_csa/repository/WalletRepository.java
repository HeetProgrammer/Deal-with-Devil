package org.example.deal_with_devil_csa.repository;

import java.util.Optional;

import org.example.deal_with_devil_csa.domain.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface WalletRepository extends JpaRepository<Wallet, Long>
{
    Optional<Wallet> findByUserId(Long userId);

    /** Row-locked read, for flows that must not interleave (for example settlement). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.user.id = :userId")
    Optional<Wallet> findByUserIdForUpdate(@Param("userId") Long userId);
}

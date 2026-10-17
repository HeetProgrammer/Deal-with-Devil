package org.example.deal_with_devil_csa.repository;

import java.util.Optional;

import org.example.deal_with_devil_csa.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long>
{
    Optional<User> findByEmail(String email);

    Optional<User> findByGoogleId(String googleId);

    /** True if some OTHER user already has this HackerRank ID (case-insensitive). */
    boolean existsByHackerRankIdIgnoreCaseAndIdNot(String hackerRankId, Long id);
}

package org.example.deal_with_devil_csa.repository;

import java.util.List;

import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.BidStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BidRepository extends JpaRepository<Bid, Long>
{
    /** One participant's bids on the problems of one round, with the problems loaded. */
    @Query("select b from Bid b join fetch b.problem p where b.user.id = :userId and p.round.id = :roundId order by p.id")
    List<Bid> findByUserAndRound(@Param("userId") Long userId, @Param("roundId") Long roundId);

    /** Every bid of one round in a given status, with problems and bidders loaded (used by settlement). */
    @Query("select b from Bid b join fetch b.problem p join fetch b.user where p.round.id = :roundId and b.status = :status order by b.id")
    List<Bid> findByRoundAndStatus(@Param("roundId") Long roundId, @Param("status") BidStatus status);
}

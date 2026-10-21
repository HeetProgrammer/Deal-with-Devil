package org.example.deal_with_devil_csa.web.dto;

/**
 * The result of a manual assignment. winsNow is the participant's number of problems in this round afterwards.
 * overCapacity is true when the problem had no unsold slot left, so it now has more winners than slots (allowed on
 * purpose: it is how the admin finishes someone whose only free slots were on problems they already hold).
 */
public record ManualAssignmentResponse(
        Long userId,
        Long problemId,
        String problemTitle,
        long pricePaid,
        long balanceAfter,
        int winsNow,
        boolean overCapacity)
{
}

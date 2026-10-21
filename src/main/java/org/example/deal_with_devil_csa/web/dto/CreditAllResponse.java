package org.example.deal_with_devil_csa.web.dto;

/** What the bulk credit did: how many participants got coins, how many each, and the total handed out. */
public record CreditAllResponse(int participants, long amountEach, long totalCredited)
{
}

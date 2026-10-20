package org.example.deal_with_devil_csa.web.dto;

import org.example.deal_with_devil_csa.domain.Bid;
import org.example.deal_with_devil_csa.domain.User;

/** A participant who won a problem and what they paid. Needs the bid's user loaded. */
public record WinnerResponse(Long userId, String name, String hackerRankId, long pricePaid)
{
    public static WinnerResponse from(Bid bid)
    {
        User user = bid.getUser();
        return new WinnerResponse(user.getId(), user.getName(), user.getHackerRankId(),
                bid.getPricePaid() == null ? 0L : bid.getPricePaid());
    }
}

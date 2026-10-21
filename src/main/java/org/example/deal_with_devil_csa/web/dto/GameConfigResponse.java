package org.example.deal_with_devil_csa.web.dto;

import org.example.deal_with_devil_csa.config.GameProperties;
import org.example.deal_with_devil_csa.domain.User;

/**
 * The game numbers the frontend needs for confirm popups and the rules page. Mirrors GameProperties so the
 * frontend never hard-codes a price.
 */
public record GameConfigResponse(
        TierAmounts basePrice,
        TierAmounts solveReward,
        long hintPrice,
        Devil devil,
        int minCorruptionLevel,
        int maxCorruptionLevel,
        int winsPerRound)
{
    public record TierAmounts(long bronze, long silver, long gold)
    {
    }

    public record Devil(long dealPayout, long redemptionCost)
    {
    }

    public static GameConfigResponse from(GameProperties props)
    {
        return new GameConfigResponse(
                new TierAmounts(props.basePrice().bronze(), props.basePrice().silver(), props.basePrice().gold()),
                new TierAmounts(props.solveReward().bronze(), props.solveReward().silver(), props.solveReward().gold()),
                props.hintPrice(),
                new Devil(props.devil().dealPayout(), props.devil().redemptionCost()),
                User.MIN_CORRUPTION_LEVEL,
                User.MAX_CORRUPTION_LEVEL,
                props.winsPerRound());
    }
}

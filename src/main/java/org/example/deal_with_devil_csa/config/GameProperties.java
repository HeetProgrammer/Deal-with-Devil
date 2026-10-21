package org.example.deal_with_devil_csa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Every game number lives here, bound from the "game.*" keys in application.properties,
 * so prices can be changed without touching code. All amounts are whole coins (long).
 * The app refuses to start if a value is missing or negative.
 */
@Validated
@ConfigurationProperties(prefix = "game")
public record GameProperties(
        @NotNull @Valid Tiers basePrice,
        @NotNull @Valid Tiers solveReward,
        @Min(0) long hintPrice,
        @NotNull @Valid Devil devil,
        @Min(1) int winsPerRound)
{
    /** One value per problem tier. */
    public record Tiers(@Min(0) long bronze, @Min(0) long silver, @Min(0) long gold)
    {
    }

    /**
     * dealPayout: coins a participant receives for taking one corruption level.
     * redemptionCost: coins they pay to go one level back down.
     */
    public record Devil(@Min(0) long dealPayout, @Min(0) long redemptionCost)
    {
        public Devil
        {
            // Otherwise a participant could loop deal and redemption forever and farm coins.
            if (redemptionCost <= dealPayout)
            {
                throw new IllegalArgumentException(
                        "game.devil.redemption-cost must be greater than game.devil.deal-payout");
            }
        }
    }
}

package org.example.deal_with_devil_csa.domain;

/**
 * The phases of one round, in the only order they can happen:
 * Preview -> Sealed bidding -> Settlement -> Solving (HackerRank) -> Results.
 * After RESULTS the admin starts a new round instead of advancing.
 */
public enum RoundPhase
{
    PREVIEW,
    BIDDING,
    SETTLEMENT,
    SOLVING,
    RESULTS;

    /** The phase that follows this one, or null if this is the last phase. */
    public RoundPhase next()
    {
        RoundPhase[] all = values();
        return ordinal() + 1 < all.length ? all[ordinal() + 1] : null;
    }

    /** Human-readable name, used in notification messages. */
    public String getLabel()
    {
        return switch (this)
        {
            case PREVIEW -> "Preview";
            case BIDDING -> "Sealed bidding";
            case SETTLEMENT -> "Settlement";
            case SOLVING -> "Solving";
            case RESULTS -> "Results";
        };
    }
}

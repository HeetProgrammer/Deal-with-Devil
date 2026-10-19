package org.example.deal_with_devil_csa.domain;

/**
 * Problem difficulty tier. Decides the base price and the solve reward (see GameProperties).
 */
public enum Tier
{
    BRONZE("Bronze"),
    SILVER("Silver"),
    GOLD("Gold");

    private final String label;

    Tier(String label)
    {
        this.label = label;
    }

    /** Human-readable name, used in ledger reasons. */
    public String getLabel()
    {
        return label;
    }
}

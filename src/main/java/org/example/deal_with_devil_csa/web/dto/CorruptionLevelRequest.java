package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Admin edit of a participant's corruption level (1 to 3). Changes the level only, no coins move. */
public record CorruptionLevelRequest(@NotNull @Min(1) @Max(3) Integer level)
{
}

package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.NotNull;

/** Admin button "give this person this problem" (manual assignment, at the problem's reserve price). */
public record ManualAssignmentRequest(@NotNull Long userId, @NotNull Long problemId)
{
}

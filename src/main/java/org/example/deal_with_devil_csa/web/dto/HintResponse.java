package org.example.deal_with_devil_csa.web.dto;

import org.example.deal_with_devil_csa.domain.ProblemHint;

/** Admin only. Includes the password, so this must never be returned from a participant endpoint. */
public record HintResponse(int corruptionLevel, String hintText, String password)
{
    public static HintResponse from(ProblemHint hint)
    {
        return new HintResponse(hint.getCorruptionLevel(), hint.getHintText(), hint.getPassword());
    }
}

package org.example.deal_with_devil_csa.web.dto;

import jakarta.validation.constraints.Size;

/**
 * The question preview the admin attaches to a problem, shown to participants before they bid (plain text, up to
 * 4000 characters). Send null or blank text to remove the preview.
 */
public record ProblemPreviewRequest(@Size(max = 4000) String preview)
{
}

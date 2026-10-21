package org.example.deal_with_devil_csa.web.controller;

import java.util.List;

import org.example.deal_with_devil_csa.service.BidService;
import org.example.deal_with_devil_csa.service.ProblemService;
import org.example.deal_with_devil_csa.web.dto.AdminProblemResponse;
import org.example.deal_with_devil_csa.web.dto.HintRequest;
import org.example.deal_with_devil_csa.web.dto.HintResponse;
import org.example.deal_with_devil_csa.web.dto.ProblemPreviewRequest;
import org.example.deal_with_devil_csa.web.dto.ProblemRequest;
import org.example.deal_with_devil_csa.web.dto.WinnerResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Problem bank manager and hint versions. Admin only (see SecurityConfig). Hint passwords are returned here. */
@RestController
@RequestMapping("/api/admin/problems")
@RequiredArgsConstructor
public class AdminProblemController
{
    private final ProblemService problemService;
    private final BidService bidService;

    /** The whole bank, or only the problems of one round with ?roundId=. */
    @GetMapping
    public List<AdminProblemResponse> list(@RequestParam(required = false) Long roundId)
    {
        return problemService.list(roundId);
    }

    @GetMapping("/{problemId}")
    public AdminProblemResponse get(@PathVariable Long problemId)
    {
        return problemService.get(problemId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminProblemResponse create(@Valid @RequestBody ProblemRequest request)
    {
        return problemService.create(request);
    }

    @PutMapping("/{problemId}")
    public AdminProblemResponse update(@PathVariable Long problemId, @Valid @RequestBody ProblemRequest request)
    {
        return problemService.update(problemId, request);
    }

    @DeleteMapping("/{problemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long problemId)
    {
        problemService.delete(problemId);
    }

    /**
     * Adds, replaces or (with blank text) removes the question preview that participants read before bidding.
     * Only while the problem's round is in PREVIEW.
     */
    @PutMapping("/{problemId}/preview")
    public AdminProblemResponse setPreview(@PathVariable Long problemId,
                                           @Valid @RequestBody ProblemPreviewRequest request)
    {
        return problemService.setPreview(problemId, request);
    }

    /** Sets the hint version for one corruption level (1 to 3). */
    @PutMapping("/{problemId}/hints/{level}")
    public HintResponse upsertHint(@PathVariable Long problemId, @PathVariable int level,
                                   @Valid @RequestBody HintRequest request)
    {
        return problemService.upsertHint(problemId, level, request);
    }

    /** Who won this problem and what they paid. Empty until the round has been settled. */
    @GetMapping("/{problemId}/winners")
    public List<WinnerResponse> winners(@PathVariable Long problemId)
    {
        return bidService.winners(problemId);
    }
}

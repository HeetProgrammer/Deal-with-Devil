package org.example.deal_with_devil_csa.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One of the (up to) three hint versions of a problem, one per corruption level. The version a participant
 * gets depends on their corruption level. The password is what the admin gives out with the hint.
 * Admin only: nothing here is ever sent to a participant by the API.
 */
@Entity
@Table(name = "problem_hints",
        uniqueConstraints = @UniqueConstraint(name = "uk_hint_problem_level",
                columnNames = {"problem_id", "corruption_level"}))
@Getter
@Setter
@NoArgsConstructor
public class ProblemHint
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "problem_id", nullable = false, updatable = false)
    private Problem problem;

    /** 1 to 3, matching User.corruptionLevel. */
    @Column(name = "corruption_level", nullable = false, updatable = false)
    private int corruptionLevel;

    @Column(nullable = false, length = 2000)
    private String hintText;

    @Column(nullable = false, length = 100)
    private String password;

    public ProblemHint(Problem problem, int corruptionLevel)
    {
        this.problem = problem;
        this.corruptionLevel = corruptionLevel;
    }
}

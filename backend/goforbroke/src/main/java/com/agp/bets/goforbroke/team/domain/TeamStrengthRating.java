package com.agp.bets.goforbroke.team.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One team's Elo rating snapshot for one completed game - see {@code
 * etl/r/compute_team_strength_ratings.R} for how it's computed (a full recompute every run, not
 * an incremental enrichment like the other team/player tables, since Elo is inherently
 * sequential/stateful). {@code ratingBefore} is the value point-in-time-correct backtesting/live
 * prediction should read (this team's strength entering the game); {@code ratingAfter} is kept for
 * transparency/debugging, not for prediction inputs.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "team_strength_ratings",
    indexes = {
      @Index(name = "idx_team_strength_ratings_team_date", columnList = "team_id, game_date")
    })
public class TeamStrengthRating {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "team_id", nullable = false)
  private Team team;

  @Column(name = "game_date", nullable = false)
  private LocalDate gameDate;

  private Integer season;

  private Integer week;

  // nflverse's raw team abbreviation (e.g. "LA", not "LAR") - matches PlayerGameStat.opponentTeamId's
  // convention, same crosswalk needed before comparing against Team.abbreviation.
  @Column(length = 64)
  private String opponentTeamId;

  @Column(length = 32)
  private String homeAway;

  private Integer pointsScored;

  private Integer pointsAllowed;

  private Double ratingBefore;

  private Double ratingAfter;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;
}

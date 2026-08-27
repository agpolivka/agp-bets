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

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "team_defense_game_stats",
    indexes = {
      @Index(name = "idx_team_defense_game_stats_team_date", columnList = "team_id, game_date", unique = true)
    })
public class TeamDefenseGameStat {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "team_id", nullable = false)
  private Team team;

  @Column(name = "game_date")
  private LocalDate gameDate;

  private Integer season;

  private Integer seasonType;

  private Integer week;

  @Column(length = 32)
  private String homeAway;

  @Column(length = 128)
  private String opponentName;

  @Column(length = 64)
  private String opponentTeamId;

  private Integer pointsAllowed;

  private Integer totalYardsAllowed;

  private Integer passingYardsAllowed;

  private Integer receivingYardsAllowed;

  private Integer rushingYardsAllowed;

  private Integer turnoversForced;

  private Integer interceptions;

  private Integer fumbleRecoveries;

  private Integer sacks;

  // PFR advanced defense (nflreadr::load_pfr_advstats(stat_type = "def")), aggregated up from
  // per-defender rows to a team-game total/rate - that source is one row per individual defender,
  // not team-level, so etl/r/import_pfr_defense_advanced.R sums the underlying counts before
  // writing here rather than averaging each defender's own rate (which would over-weight
  // low-snap-count players). Null until that script has run for this team/game - PFR's advanced
  // charting only goes back to 2018.
  private Integer pressures;

  // Weighted rate: sum(missed tackles) / sum(missed tackles + combined tackles) across every
  // defender in the game, not a naive average of each defender's own percentage.
  private Double missedTacklePct;

  @Column(nullable = false, length = 512)
  private String sourceUrl;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String rawPayload;

  @Column(nullable = false)
  private Instant fetchedAt;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;
}

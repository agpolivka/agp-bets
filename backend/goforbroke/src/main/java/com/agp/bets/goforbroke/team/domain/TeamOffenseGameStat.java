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
 * Team offense identity/tendency per game - the counterpart {@link TeamDefenseGameStat} never had
 * (Priority 3's long-open "build a cleaner team offense model" gap, closed 2026-08-28). Built for
 * Priority 5's style-vs-style matchup work: a single Elo number can't represent "team A struggles
 * specifically against team C's style while beating team B" (Elo is transitive by construction), so
 * testing that needs each team's own offensive tendency alongside the opponent's own defensive
 * tendency ({@link TeamDefenseGameStat}'s pressures/missedTacklePct/zoneCoverageRate/etc.) - see
 * {@code TeamMatchupBacktestService#runStyleCalibrationExport} and WORKPLAN.md for the real
 * regression this feeds.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "team_offense_game_stats",
    indexes = {
      @Index(name = "idx_team_offense_game_stats_team_date", columnList = "team_id, game_date", unique = true)
    })
public class TeamOffenseGameStat {

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

  // nflreadr::load_team_stats() attempts / (attempts + carries) - a clean, unambiguous ratio, no
  // parsing risk. Backfilled back to 2018 (matching PFR advanced defense's own floor) since this
  // doesn't depend on participation charting the way shotgunRate below does.
  private Double passRate;

  // Share of offensive plays run from shotgun (nflreadr::load_participation(),
  // offense_formation == "SHOTGUN") - same source already backfilled for TeamDefenseGameStat's
  // zoneCoverageRate/avgPassRushers/avgDefendersInBox, just grouped by possession_team (the
  // offense) instead of the derived defense_team. Only reliably populated 2023 onward, same
  // caveat as the defense-side participation columns - null for earlier seasons, not zero.
  private Double shotgunRate;

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

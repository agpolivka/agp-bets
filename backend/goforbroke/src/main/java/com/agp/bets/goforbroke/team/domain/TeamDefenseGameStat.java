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

  // Per-play defensive charting (nflreadr::load_participation()), aggregated up to a team-game
  // average - that source is one row per play (with the offense's team already labeled, not the
  // defense's), so etl/r/import_participation_defense.R first derives the defending team via
  // nfl_schedules' home/away, then averages across every defensive play in the game, same
  // averaging shape as missedTacklePct above. Only reliably populated for 2023 onward - confirmed
  // live: 0% NA 2023-2025, ~62% NA 2018-2022 (not trustworthy at that rate), 100% NA 2017 and
  // earlier (the underlying charting doesn't exist yet). Null until that script has run for this
  // team/game, and null forever for games this data source doesn't cover.

  // Share of the defense's own plays run in zone coverage (vs. man) - defense_man_zone_type.
  // Real-calibrated (2026-08-26): a significant, incremental trailing predictor of
  // receivingYards/receptions even controlling for pressures/missedTacklePct above - see
  // PlayerPredictionService#ZONE_COVERAGE_RECEIVING_YARDS_COEFFICIENT's doc for the regression,
  // and its own doc for why "significant even controlling for what's already wired" is the real
  // bar this had to clear (a same-game correlation alone wasn't enough - see WORKPLAN.md).
  private Double zoneCoverageRate;

  // Average number of pass rushers sent per defensive play - number_of_pass_rushers. A strategy
  // signal (how many rushers sent), deliberately distinct from `pressures` above (a PFR-sourced
  // count of how often a rush actually got there). Tested the same way as zoneCoverageRate
  // (2026-08-26) but did NOT hold up as a real trailing predictor (p=0.10-0.97) - stored, not
  // wired into any live adjustment, a real checked negative result.
  private Double avgPassRushers;

  // Average defenders in the box per defensive play - defenders_in_box, a run-support signal.
  // Also tested (2026-08-26) and did not hold up (p=0.17, and the sign flipped from the
  // misleading same-game correlation that first found this) - stored, not wired in.
  private Double avgDefendersInBox;

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

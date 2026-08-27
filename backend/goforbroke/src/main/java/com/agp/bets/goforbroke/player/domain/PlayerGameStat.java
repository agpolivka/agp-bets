package com.agp.bets.goforbroke.player.domain;

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
import jakarta.persistence.UniqueConstraint;
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
    name = "player_game_stats",
    indexes = {
      @Index(name = "idx_player_game_stats_player_date", columnList = "player_id, game_date")
    },
    // Backs write_player_game_stats()'s real upsert (INSERT ... ON CONFLICT) in _common.R - added
    // 2026-08-20 after discovering the previous DELETE-then-INSERT approach silently wiped
    // PFR-rushing/NGS/snap-count enrichment columns (which that write path never sets) every time
    // a player's box-score data was refreshed for an already-enriched player/season/week. See
    // WORKPLAN.md for the full incident writeup.
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_player_game_stats_player_season_week",
          columnNames = {"player_id", "season", "week"})
    })
public class PlayerGameStat {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "player_id", nullable = false)
  private Player player;

  // Nullable: nflverse's own game-stat rows always populate this (it's a direct field on
  // load_player_stats(), not schedule-join-derived), but nfl_schedules isn't backfilled before
  // 2014 (see WORKPLAN.md Priority 6), so pre-2014 rows legitimately have no game_date. Unlike
  // season/week below, this is a known real gap, not orphaned data - don't add NOT NULL here.
  @Column(name = "game_date")
  private LocalDate gameDate;

  // NOT NULL: nflverse's box-score fetch always populates both directly (never schedule-join-
  // derived, unlike game_date above). Twice now, leftover rows from the deleted ESPN game-log
  // scraper had one of these null and silently won "most recent game" via Postgres sorting NULL
  // first on ORDER BY ... DESC (season = NULL: 314 rows, cleaned up 2026-08-xx; week = NULL: 1,798
  // rows across 22 players including several stars, cleaned up 2026-08-13 - see WORKPLAN.md
  // Recently Completed for both). This constraint makes that whole bug class impossible to
  // reintroduce, from any future write path, instead of relying on someone noticing a
  // suspiciously-zero adjustment again.
  @Column(nullable = false)
  private Integer season;

  @Column(nullable = false)
  private Integer week;

  @Column(length = 32)
  private String homeAway;

  @Column(length = 128)
  private String opponentName;

  @Column(length = 64)
  private String opponentTeamId;

  private Integer gamesPlayed;

  private Integer passingYards;

  private Integer rushingYards;

  private Integer totalYards;

  private Integer passingTouchdowns;

  private Integer rushingTouchdowns;

  private Integer receivingTouchdowns;

  private Integer touchdowns;

  private Integer totalTouchdowns;

  private Integer interceptions;

  private Integer fumbles;

  private Integer fumblesLost;

  private Integer turnovers;

  // Always null from the box-score fetch itself (nflverse's weekly stats file doesn't carry real
  // snap counts) - populated separately by import_snap_counts.R (load_snap_counts(), PFR-sourced,
  // joined via pfr_player_id like the PFR advanced-rushing/defense scripts) via an UPDATE against
  // rows already written here, same enrichment shape as rushingYardsAfterContact below.
  private Integer snapCount;

  // offense_snaps / team's total offensive plays that game - same source/join as snapCount above.
  // A normalized alternative to the raw count (comparable across games with different paces).
  // Wired into PlayerPredictionService#usageAdjustment (2026-08-20/22) using a real,
  // position-specific baseline - unlike WOPR, a meaningful "average" snap share varies a lot by
  // position (a workhorse RB and a rotational WR have very different normal snap percentages), so
  // this waited for that position-aware baseline and a real regression read before being wired in.
  private Double offenseSnapPct;

  private Integer carries;

  private Integer receivingTargets;

  private Integer receptions;

  private Integer receivingYards;

  private Integer drops;

  // Weather/Vegas-line context for this specific game, sourced from nflverse's nfl_schedules
  // (already ingested by import_schedules.R, just not previously joined through to this table).
  // temp/wind are null for dome/closed-roof games by nflverse's own convention - no special-casing
  // needed here, a null already means "no weather effect."
  @Column(length = 32)
  private String roof;

  @Column(length = 64)
  private String surface;

  private Double tempFahrenheit;

  private Double windMph;

  // Positive = this player's own team was favored by that many points; negative = underdog.
  private Double teamImpliedSpread;

  private Double gameTotalLine;

  // Share of the team's targets/air yards that went to this player that game, plus wopr (Weighted
  // Opportunity Rating, nflverse's own composite of the two: roughly 1.5*target_share +
  // 0.7*air_yards_share) - all three come from the same load_player_stats() call already used for
  // the rest of this row's box-score fields (2026-08-20: confirmed live that nflverse already
  // returns these, they just weren't being selected into the insert before now), not a new data
  // source. Null for non-pass-catchers (QBs/OL/etc.) and for games with zero targets.
  private Double targetShare;

  private Double airYardsShare;

  // Wired into PlayerPredictionService's targetShareAdjustment (receivingYards/receptions only) -
  // a role/opportunity signal, deliberately not stacked with target_share/air_yards_share
  // separately since wopr already is a composite of both; storing all three anyway for
  // transparency/future use, same as this file's other "store the raw inputs, wire in the
  // composite" choices.
  private Double wopr;

  // PFR advanced rushing (nflverse load_pfr_advstats), joined in separately from the box-score
  // fetch via pfr_player_id -> load_players()$pfr_id -> espn_id. Null until
  // import_pfr_advanced_rushing.R has run for this player/season/week - PFR's advanced charting
  // only goes back to 2018 and isn't published for every game.
  private Integer rushingYardsBeforeContact;

  private Integer rushingYardsAfterContact;

  private Integer rushingBrokenTackles;

  // nflverse Next Gen Stats (load_nextgen_stats), joined in via the same gsis_id -> espn_id
  // crosswalk already used for the box-score fetch (no separate identity mapping needed, unlike
  // PFR's pfr_player_id). Null until import_nextgen_stats.R has run for this player/game - NGS
  // charting only goes back to 2016 and only exists for skill positions that were tracked.
  // completion_percentage_above_expectation (CPOE) - already expressed relative to a league-wide
  // expectation baseline by nflverse's own model, so no separate baseline lookup is needed the way
  // opponentAdjustment/rushingQualityAdjustment need one.
  private Double passingCpoe;

  // avg_yac_above_expectation - wired into receivingQualityAdjustment below.
  private Double receivingYacAboveExpectation;

  // avg_separation - stored for reference but deliberately NOT wired into the live heuristic yet:
  // stacking it alongside receivingYacAboveExpectation without evidence they're independently
  // useful risks over-fitting two receiving-quality nudges onto the same metric. Revisit once
  // there's a backtest read on receivingYacAboveExpectation alone.
  private Double receivingSeparationAvg;

  // rush_yards_over_expected_per_att - stored for reference but deliberately NOT wired into the
  // live heuristic: it would compete with Phase 2's rushingQualityAdjustment (PFR after-contact
  // yards/carry) as a second, independent rushing-quality signal on the same metric, without any
  // evidence the two are additive rather than redundant. Revisit once rushingQualityAdjustment has
  // its own backtest read.
  private Double rushingYardsOverExpectedPerAtt;

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

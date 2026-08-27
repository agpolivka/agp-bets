package com.agp.bets.goforbroke.team.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Read-only mapping onto {@code nfl_schedules} (created and written directly by {@code
 * etl/r/import_schedules.R} via raw SQL, not through this entity - Java only ever reads it). Maps
 * a natural key ({@code game_id}, nflverse's own id) rather than a generated numeric one, unlike
 * every other entity in this codebase, since that's the table's real primary key.
 *
 * <p>Only maps the columns this app's Java code actually needs (not all ~45 real columns) -
 * intentional partial mapping, safe for a read-only entity since Hibernate never needs to satisfy
 * the full row shape on an insert it will never issue.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "nfl_schedules")
public class NflSchedule {

  @Id
  @Column(name = "game_id")
  private String gameId;

  private Integer season;

  @Column(name = "game_type")
  private String gameType;

  private Integer week;

  private LocalDate gameday;

  // nflverse's raw team code (e.g. "LA", "OAK") - crosswalk via NflverseTeamAbbreviations before
  // looking up against Team.abbreviation.
  @Column(name = "home_team")
  private String homeTeam;

  @Column(name = "away_team")
  private String awayTeam;

  @Column(name = "home_score")
  private Integer homeScore;

  @Column(name = "away_score")
  private Integer awayScore;

  @Column(name = "spread_line")
  private Double spreadLine;

  // Real posted Vegas total, when available - see UpcomingTeamMatchupService's doc for why this is
  // preferred over our own computed total (2026-08-20 finding: even the real market's own posted
  // total only explains ~9% of real variance in game totals, but that's still meaningfully more
  // than our own recent-scoring-based estimate's ~4%). Null for games far enough out that a line
  // hasn't been posted yet - callers must fall back to the computed estimate in that case.
  @Column(name = "total_line")
  private Double totalLine;
}

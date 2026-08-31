package com.agp.bets.goforbroke.picks.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One user's straight-up winner pick for one real game - deliberately independent of this app's
 * own prediction model (no {@code TeamMatchupPredictionService} dependency anywhere in this
 * package). The point of this feature (2026-08-31, user direction) is comparing a real, tracked
 * human pick record against the real Vegas-favorite/Elo baselines already discussed, not another
 * view onto the model's own picks. No user/auth concept exists anywhere in this app yet, so this
 * is a single, un-scoped pick set - "your" picks, not any particular login's.
 *
 * <p>{@code gameId}/{@code homeTeam}/{@code awayTeam} conventions intentionally mirror {@code
 * NflSchedule} exactly (nflverse's own raw team codes, e.g. "LA" not "LAR") rather than crossing
 * over to the ESPN-sourced {@code Team} entity's abbreviations - this table only ever needs to
 * compare itself against {@code NflSchedule}'s own {@code home_team}/{@code away_team}/{@code
 * home_score}/{@code away_score} columns to grade a pick, so there's no reason to introduce the
 * ESPN crosswalk here at all (the web layer applies it only for display).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_picks", indexes = {@Index(name = "idx_user_picks_game_id", columnList = "game_id", unique = true)})
public class UserPick {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "game_id", nullable = false, length = 64)
  private String gameId;

  private Integer season;

  @Column(name = "game_type", length = 8)
  private String gameType;

  private Integer week;

  // nflverse's raw team code (matches NflSchedule.homeTeam/awayTeam), whichever of the two this
  // pick is for.
  @Column(name = "picked_team", nullable = false, length = 8)
  private String pickedTeam;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;
}

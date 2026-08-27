package com.agp.bets.goforbroke.player.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "players",
    indexes = {
      @Index(name = "idx_players_espn_athlete_id", columnList = "espnAthleteId", unique = true)
    })
public class Player {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 64)
  private String espnAthleteId;

  @Column(nullable = false)
  private String displayName;

  private String firstName;

  private String lastName;

  private String position;

  private String jerseyNumber;

  private String teamName;

  private String teamId;

  private Boolean active;

  // ESPN's own game-availability designation for this player (e.g. "Active", "Questionable",
  // "Out", "Injured Reserve") - null means ESPN didn't report one (most players, most of the
  // time). Distinct from `active` above, which is a roster-active flag, not a weekly
  // availability status.
  private String injuryStatus;

  // Weekly game-status designation (e.g. "Questionable", "Doubtful", "Out") from ESPN's
  // league-wide injuries feed (see InjuryStatusRefreshWorker) - genuinely different from
  // injuryStatus above. Confirmed directly (2026-08-17) that the two can diverge: a player showed
  // "Active" via injuryStatus/athlete.status while this feed reported them "Out" for that week's
  // game. Null means either healthy or simply absent from ESPN's current injuries report.
  private String gameStatus;

  // ESPN's own explanation for gameStatus above (its longComment, falling back to shortComment) -
  // e.g. "questionable - hamstring". Null whenever gameStatus is null. TEXT, not the default
  // varchar(255) - confirmed live (2026-08-20) that some longComment values exceed 255 chars and
  // blew up every single save until this was widened.
  @Column(columnDefinition = "TEXT")
  private String gameStatusDetail;

  // When ESPN itself last updated this specific status entry (not when this app polled for it).
  // Null whenever gameStatus is null.
  private Instant gameStatusUpdatedAt;

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

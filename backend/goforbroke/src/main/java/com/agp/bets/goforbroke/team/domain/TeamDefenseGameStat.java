package com.agp.bets.goforbroke.team.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
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

  @Column(nullable = false, length = 512)
  private String sourceUrl;

  @Lob
  @Column(nullable = false, columnDefinition = "TEXT")
  private String rawPayload;

  @Column(nullable = false)
  private Instant fetchedAt;

  @Column(nullable = false)
  private Instant createdAt;

  @Column(nullable = false)
  private Instant updatedAt;
}

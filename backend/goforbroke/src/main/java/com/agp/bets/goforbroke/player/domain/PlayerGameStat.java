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
    })
public class PlayerGameStat {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "player_id", nullable = false)
  private Player player;

  @Column(name = "game_date")
  private LocalDate gameDate;

  private Integer season;

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

  private Integer snapCount;

  private Integer carries;

  private Integer receivingTargets;

  private Integer receptions;

  private Integer receivingYards;

  private Integer drops;

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

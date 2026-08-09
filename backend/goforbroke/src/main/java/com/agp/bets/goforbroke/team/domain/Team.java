package com.agp.bets.goforbroke.team.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
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
    name = "teams",
    indexes = {
      @Index(name = "idx_teams_espn_team_id", columnList = "espnTeamId", unique = true)
    })
public class Team {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 64)
  private String espnTeamId;

  @Column(nullable = false)
  private String displayName;

  private String location;

  private String name;

  private String abbreviation;

  private String slug;

  @Column(length = 1024)
  private String logoUrl;

  private String primaryColor;

  private String alternateColor;

  private String recordSummary;

  private String standingSummary;

  private String upcomingOpponentTeamId;

  private String upcomingOpponentName;

  private LocalDate upcomingGameDate;

  private String venueId;

  private String venueName;

  private String venueCity;

  private String venueState;

  private Boolean venueIndoor;

  private Boolean venueGrass;

  private String baseDefensiveScheme;

  private Boolean active;

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

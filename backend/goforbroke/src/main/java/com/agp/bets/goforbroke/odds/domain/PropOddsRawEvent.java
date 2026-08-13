package com.agp.bets.goforbroke.odds.domain;

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
 * One raw, source-of-truth row per (provider, external event, bookmaker) odds response. Player-
 * level lines are derived from this into {@link PlayerPropLine}; this table exists so the full
 * response is never lost even if derivation logic changes later.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "prop_odds_raw_events",
    indexes = {
      @Index(
          name = "idx_prop_odds_raw_events_provider_event_bookmaker",
          columnList = "provider, externalEventId, bookmaker",
          unique = true)
    })
public class PropOddsRawEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String provider;

  @Column(nullable = false, length = 64)
  private String externalEventId;

  @Column(nullable = false, length = 64)
  private String bookmaker;

  private Instant eventDate;

  @Column(length = 128)
  private String homeTeamRaw;

  @Column(length = 128)
  private String awayTeamRaw;

  @Column(nullable = false, length = 512)
  private String sourceUrl;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String rawPayload;

  @Column(nullable = false)
  private Instant fetchedAt;

  @Column(nullable = false)
  private Instant createdAt;
}

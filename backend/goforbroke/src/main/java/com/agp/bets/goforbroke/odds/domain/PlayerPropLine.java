package com.agp.bets.goforbroke.odds.domain;

import com.agp.bets.goforbroke.player.domain.Player;
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
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One derived row per player/market/line extracted from a {@link PropOddsRawEvent}. {@code
 * player} is nullable: the odds provider only gives a display-name string, so low-confidence or
 * unresolved name matches are still stored (with {@code playerNameRaw} and {@code matchScore})
 * rather than dropped.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
    name = "player_prop_lines",
    indexes = {
      @Index(name = "idx_player_prop_lines_raw_event", columnList = "raw_event_id"),
      @Index(name = "idx_player_prop_lines_player", columnList = "player_id")
    })
public class PlayerPropLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "raw_event_id", nullable = false)
  private PropOddsRawEvent rawEvent;

  @Column(nullable = false, length = 128)
  private String marketRaw;

  @Column(nullable = false, length = 128)
  private String playerNameRaw;

  @ManyToOne(fetch = FetchType.LAZY, optional = true)
  @JoinColumn(name = "player_id")
  private Player player;

  private Double matchScore;

  private Double line;

  private Double overPrice;

  private Double underPrice;

  private Instant sourceUpdatedAt;

  @Column(nullable = false)
  private Instant createdAt;
}

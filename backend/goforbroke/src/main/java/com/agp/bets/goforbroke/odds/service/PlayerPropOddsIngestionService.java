package com.agp.bets.goforbroke.odds.service;

import com.agp.bets.goforbroke.common.text.NameSimilarity;
import com.agp.bets.goforbroke.odds.domain.PlayerPropLine;
import com.agp.bets.goforbroke.odds.domain.PropOddsRawEvent;
import com.agp.bets.goforbroke.odds.provider.PropOddsProvider;
import com.agp.bets.goforbroke.odds.provider.RawEventOdds;
import com.agp.bets.goforbroke.odds.provider.RawMarket;
import com.agp.bets.goforbroke.odds.provider.RawMarketEntry;
import com.agp.bets.goforbroke.odds.repository.PlayerPropLineRepository;
import com.agp.bets.goforbroke.odds.repository.PropOddsRawEventRepository;
import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ingests one (event, bookmaker) worth of odds - fetch, store raw, derive player prop lines. */
@Service
public class PlayerPropOddsIngestionService {

  // Auto-link only above this score; lower-confidence matches are still stored (playerNameRaw +
  // matchScore) but left unlinked, since a wrong crosswalk would silently corrupt backtest data.
  private static final double AUTO_LINK_THRESHOLD = 0.85d;

  private final PropOddsProvider provider;
  private final PropOddsRawEventRepository rawEventRepository;
  private final PlayerPropLineRepository propLineRepository;
  private final PlayerRepository playerRepository;

  public PlayerPropOddsIngestionService(
      PropOddsProvider provider,
      PropOddsRawEventRepository rawEventRepository,
      PlayerPropLineRepository propLineRepository,
      PlayerRepository playerRepository) {
    this.provider = provider;
    this.rawEventRepository = rawEventRepository;
    this.propLineRepository = propLineRepository;
    this.playerRepository = playerRepository;
  }

  public enum IngestOutcome {
    INGESTED,
    SKIPPED_ALREADY_STORED
  }

  public record RecrosswalkResult(int checked, int newlyLinked) {}

  public boolean alreadyStored(String externalEventId, String bookmaker) {
    return rawEventRepository.existsByProviderAndExternalEventIdAndBookmaker(
        provider.providerName(), externalEventId, bookmaker);
  }

  @Transactional
  public IngestOutcome ingest(String externalEventId, String bookmaker) {
    if (alreadyStored(externalEventId, bookmaker)) {
      return IngestOutcome.SKIPPED_ALREADY_STORED;
    }

    RawEventOdds odds = provider.fetchEventOdds(externalEventId, bookmaker);
    Instant now = Instant.now();

    PropOddsRawEvent rawEvent = new PropOddsRawEvent();
    rawEvent.setProvider(provider.providerName());
    rawEvent.setExternalEventId(externalEventId);
    rawEvent.setBookmaker(bookmaker);
    rawEvent.setEventDate(odds.eventDate());
    rawEvent.setHomeTeamRaw(odds.homeTeam());
    rawEvent.setAwayTeamRaw(odds.awayTeam());
    rawEvent.setSourceUrl(odds.sourceUrl());
    rawEvent.setRawPayload(odds.rawJson());
    rawEvent.setFetchedAt(now);
    rawEvent.setCreatedAt(now);
    rawEventRepository.save(rawEvent);

    List<Player> knownPlayers = playerRepository.findAllByOrderByDisplayNameAsc();

    for (RawMarket market : odds.markets()) {
      for (RawMarketEntry entry : market.entries()) {
        propLineRepository.save(buildPropLine(rawEvent, market, entry, knownPlayers, now));
      }
    }

    return IngestOutcome.INGESTED;
  }

  private PlayerPropLine buildPropLine(
      PropOddsRawEvent rawEvent,
      RawMarket market,
      RawMarketEntry entry,
      List<Player> knownPlayers,
      Instant now) {
    PlayerMatch match = findBestMatch(entry.playerNameRaw(), knownPlayers);

    PlayerPropLine line = new PlayerPropLine();
    line.setRawEvent(rawEvent);
    line.setMarketRaw(market.name());
    line.setPlayerNameRaw(entry.playerNameRaw());
    line.setMatchScore(match.player() == null ? null : match.score());
    line.setPlayer(match.isAutoLinkable() ? match.player() : null);
    line.setLine(entry.line());
    line.setOverPrice(entry.overPrice());
    line.setUnderPrice(entry.underPrice());
    line.setSourceUpdatedAt(entry.sourceUpdatedAt());
    line.setCreatedAt(now);
    return line;
  }

  /**
   * Re-scores every currently-unlinked prop line against the full player catalog. The crosswalk
   * only runs once, at ingestion time - if the catalog grows afterward (e.g. a bulk player-catalog
   * refresh, or someone searching new players into existence), previously-unmatched rows stay
   * unmatched until this is explicitly re-run. Safe to call repeatedly - only touches rows that are
   * still unlinked and newly clear the auto-link threshold.
   */
  @Transactional
  public RecrosswalkResult recrosswalkUnmatchedLines() {
    List<PlayerPropLine> unmatched = propLineRepository.findByPlayerIsNull();
    List<Player> knownPlayers = playerRepository.findAllByOrderByDisplayNameAsc();

    int newlyLinked = 0;
    for (PlayerPropLine line : unmatched) {
      PlayerMatch match = findBestMatch(line.getPlayerNameRaw(), knownPlayers);
      if (match.player() != null) {
        line.setMatchScore(match.score());
      }
      if (match.isAutoLinkable()) {
        line.setPlayer(match.player());
        newlyLinked++;
      }
    }
    propLineRepository.saveAll(unmatched);

    return new RecrosswalkResult(unmatched.size(), newlyLinked);
  }

  private PlayerMatch findBestMatch(String rawName, List<Player> knownPlayers) {
    Player bestMatch = null;
    double bestScore = 0.0d;
    for (Player candidate : knownPlayers) {
      double score = NameSimilarity.similarity(rawName, candidate.getDisplayName());
      if (score > bestScore) {
        bestScore = score;
        bestMatch = candidate;
      }
    }
    return new PlayerMatch(bestMatch, bestScore);
  }

  private record PlayerMatch(Player player, double score) {
    boolean isAutoLinkable() {
      return player != null && score >= AUTO_LINK_THRESHOLD;
    }
  }
}

package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Syncs every stored player's weekly game-status designation (Questionable/Doubtful/Out) from
 * ESPN's league-wide injuries feed. One HTTP call covers every team, so unlike {@code
 * PlayerCatalogRefreshWorker} this doesn't need its own per-run due-check against {@code
 * etl_import_runs} - {@link InjuryStatusRefreshScheduler}'s poll interval is the only cadence
 * control, and re-running this "too often" is harmless.
 *
 * <p>The feed only lists players it currently has a real entry for - anyone stored locally but
 * missing from it needs their status actively cleared (they're no longer questionable/doubtful/
 * out), not just left alone, or a recovered player would stay stuck showing a stale designation
 * indefinitely.
 */
@Service
public class InjuryStatusRefreshWorker {

  private static final Logger log = LoggerFactory.getLogger(InjuryStatusRefreshWorker.class);

  private final EspnInjuryClient espnInjuryClient;
  private final PlayerRepository playerRepository;
  private final AtomicBoolean refreshInFlight = new AtomicBoolean(false);

  public InjuryStatusRefreshWorker(EspnInjuryClient espnInjuryClient, PlayerRepository playerRepository) {
    this.espnInjuryClient = espnInjuryClient;
    this.playerRepository = playerRepository;
  }

  // @Async methods returning CompletableFuture propagate exceptions onto the returned future
  // rather than through the calling thread; InjuryStatusRefreshScheduler fires this and discards
  // the future, so a failure in here must be caught and logged explicitly here or it vanishes
  // completely (same lesson as TeamRefreshWorker/PlayerRefreshService - see WORKPLAN.md).
  @Async
  public CompletableFuture<Void> refreshAsync() {
    if (!refreshInFlight.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture(null);
    }

    try {
      refresh();
    } catch (RuntimeException exception) {
      log.warn("Failed to refresh injury statuses: {}", exception.getMessage());
    } finally {
      refreshInFlight.set(false);
    }

    return CompletableFuture.completedFuture(null);
  }

  @Transactional
  void refresh() {
    JsonNode root = espnInjuryClient.fetchLeagueInjuries();
    List<InjuryReport> reports = EspnInjuryMapper.parse(root);
    Map<String, InjuryReport> reportsByAthleteId =
        reports.stream()
            .collect(Collectors.toMap(InjuryReport::espnAthleteId, Function.identity(), (first, second) -> first));

    List<Player> changed = new ArrayList<>();
    for (Player player : playerRepository.findAll()) {
      InjuryReport report = reportsByAthleteId.get(player.getEspnAthleteId());
      boolean wasChanged = report != null ? applyReport(player, report) : clearIfNeeded(player);
      if (wasChanged) {
        changed.add(player);
      }
    }

    if (!changed.isEmpty()) {
      playerRepository.saveAll(changed);
    }
    log.info(
        "Injury status refresh: {} players in ESPN's feed, {} stored players updated.",
        reports.size(),
        changed.size());
  }

  private boolean applyReport(Player player, InjuryReport report) {
    String detail = report.longComment() != null ? report.longComment() : report.shortComment();
    if (report.status().equals(player.getGameStatus()) && Objects.equals(detail, player.getGameStatusDetail())) {
      return false;
    }

    player.setGameStatus(report.status());
    player.setGameStatusDetail(detail);
    player.setGameStatusUpdatedAt(report.reportedAt() != null ? report.reportedAt() : Instant.now());
    return true;
  }

  private boolean clearIfNeeded(Player player) {
    if (player.getGameStatus() == null) {
      return false;
    }

    player.setGameStatus(null);
    player.setGameStatusDetail(null);
    player.setGameStatusUpdatedAt(null);
    return true;
  }
}

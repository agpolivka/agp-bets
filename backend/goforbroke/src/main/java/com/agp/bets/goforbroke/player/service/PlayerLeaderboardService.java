package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.player.web.dto.PlayerPredictionResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds the real, live "top projected" player at each of QB/RB/WR - the QB projected to throw for
 * the most yards, the RB projected to rush for the most, the WR projected to receive for the most
 * - by actually computing every real candidate's live prediction and taking the max, rather than a
 * fixed/curated pick. Built 2026-08-31 to replace a homepage "featured players" section that (even
 * after an earlier pass made its 3 cards show real per-player data) still picked *which* 3 players
 * to show by a hand-picked list, not by anything about their actual projections.
 *
 * <p>Reuses {@link PlayerPredictionService#getPredictionForAthleteId} - the exact same code path
 * (and per-player cache) a user hits by visiting that player's own page - rather than a second,
 * parallel prediction implementation that could drift from it. The tradeoff: evaluating every real
 * candidate (roughly 90 QBs/150 RBs/240 WRs with a real game sample, ~480 total) is real work.
 *
 * <p><b>What's cached, and why (2026-08-31 fix)</b>: only the *identity* of each position's winner
 * ({@link #CACHE_TTL}) - not their projected value. An earlier version cached the whole computed
 * response, including the value, which meant the homepage could show a snapshot number from up to
 * 30 minutes ago while that same player's own page showed whatever {@code
 * PlayerPredictionService}'s own (shorter, 20-minute) cache currently held - a real, user-reported
 * inconsistency (one live example: homepage showed 304.0 projected passing yards for a player
 * whose own page showed 291.5 moments later). "Who's the overall leader" genuinely is stable
 * enough to cache for a while (it rarely flips minute to minute); "what's their current number"
 * should never be a stale copy - it should always be the exact same value
 * {@code getPredictionForAthleteId} would return right now, so the two pages can never disagree.
 */
@Service
public class PlayerLeaderboardService {

  private static final Logger log = LoggerFactory.getLogger(PlayerLeaderboardService.class);

  // Matches RECENT_GAME_WINDOW's default (see PlayerPredictionService) - an already-established
  // "enough games to not be pure noise" threshold in this app, not a new arbitrary number.
  private static final long MIN_GAMES_FOR_CANDIDACY = 5;
  private static final Duration CACHE_TTL = Duration.ofMinutes(30);

  private static final Map<String, String> HEADLINE_METRIC_BY_POSITION =
      Map.of("QB", "passingYards", "RB", "rushingYards", "WR", "receivingYards");

  private final PlayerRepository playerRepository;
  private final PlayerPredictionService playerPredictionService;
  private final Clock clock = Clock.systemUTC();

  private volatile CachedWinners cachedWinners;

  public PlayerLeaderboardService(PlayerRepository playerRepository, PlayerPredictionService playerPredictionService) {
    this.playerRepository = playerRepository;
    this.playerPredictionService = playerPredictionService;
  }

  public record TopProjectedPlayer(
      String athleteId, String displayName, String teamName, String position, String metric, double value) {}

  public record LeaderboardResponse(
      TopProjectedPlayer topQuarterback, TopProjectedPlayer topRusher, TopProjectedPlayer topReceiver) {}

  public LeaderboardResponse topProjectedPlayers() {
    Map<String, String> athleteIdByPosition = winningAthleteIdsByPosition();
    return new LeaderboardResponse(
        currentValueFor(athleteIdByPosition.get("QB"), "QB"),
        currentValueFor(athleteIdByPosition.get("RB"), "RB"),
        currentValueFor(athleteIdByPosition.get("WR"), "WR"));
  }

  private Map<String, String> winningAthleteIdsByPosition() {
    CachedWinners current = cachedWinners;
    if (current != null && !current.isExpired(clock.instant())) {
      return current.athleteIdByPosition();
    }

    Map<String, String> winners = new LinkedHashMap<>();
    for (String position : HEADLINE_METRIC_BY_POSITION.keySet()) {
      String athleteId = findTopAthleteId(position);
      if (athleteId != null) {
        winners.put(position, athleteId);
      }
    }
    cachedWinners = new CachedWinners(winners, clock.instant().plus(CACHE_TTL));
    return winners;
  }

  @Transactional(readOnly = true)
  String findTopAthleteId(String position) {
    String metric = HEADLINE_METRIC_BY_POSITION.get(position);
    List<Player> candidates = playerRepository.findActiveCandidatesByPosition(position, MIN_GAMES_FOR_CANDIDACY);

    String bestAthleteId = null;
    Double bestValue = null;
    for (Player candidate : candidates) {
      Double value = projectedValue(candidate.getEspnAthleteId(), metric);
      if (value == null) {
        continue;
      }
      if (bestValue == null || value > bestValue) {
        bestValue = value;
        bestAthleteId = candidate.getEspnAthleteId();
      }
    }
    return bestAthleteId;
  }

  // Always a fresh read through PlayerPredictionService's own cache - deliberately not reusing
  // whatever value the scan above saw, so a cache-hit response (the common case) reflects the
  // exact same number the player's own page would show right now, not a snapshot from whenever
  // the leaderboard identity was last computed. See the class doc for the bug this fixes.
  @Transactional(readOnly = true)
  TopProjectedPlayer currentValueFor(String athleteId, String position) {
    if (athleteId == null) {
      return null;
    }

    String metric = HEADLINE_METRIC_BY_POSITION.get(position);
    Double value = projectedValue(athleteId, metric);
    if (value == null) {
      return null;
    }

    String teamName =
        playerRepository.findByEspnAthleteId(athleteId).map(Player::getTeamName).orElse("Unknown team");
    String displayName = playerPredictionService.getPredictionForAthleteId(athleteId).displayName();
    return new TopProjectedPlayer(athleteId, displayName, teamName, position, metric, value);
  }

  private Double projectedValue(String athleteId, String metric) {
    try {
      PlayerPredictionResponse prediction = playerPredictionService.getPredictionForAthleteId(athleteId);
      return prediction.projections().stream()
          .filter(projection -> metric.equals(projection.metric()))
          .map(PlayerPredictionResponse.PredictionSummaryResponse::mean)
          .findFirst()
          .orElse(null);
    } catch (RuntimeException exception) {
      // One candidate's prediction failing (stale/unsynced metadata, etc.) shouldn't take down the
      // whole leaderboard computation - same graceful-degradation shape used throughout this app.
      log.warn("Skipping leaderboard candidate {} - prediction failed: {}", athleteId, exception.getMessage());
      return null;
    }
  }

  private record CachedWinners(Map<String, String> athleteIdByPosition, Instant expiresAt) {
    private boolean isExpired(Instant now) {
      return now.isAfter(expiresAt);
    }
  }
}

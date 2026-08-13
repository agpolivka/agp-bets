package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.player.web.dto.PlayerPredictionResponse;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces a per-stat game projection for a player - e.g. "127 rushing yards" - which the
 * frontend shows as the headline number a user would act on for a bet.
 *
 * <p><b>This is a hand-tuned heuristic (v1), not a trained model.</b> Every weight and coefficient
 * in this file was picked to be directionally reasonable, not fit against real outcomes. Treat
 * them as a starting point to calibrate once there's enough completed-game history to backtest
 * against (compare {@code projectedMean} to what actually happened that week), not as validated
 * parameters. The opponent-adjustment baseline itself (see {@link #leagueDefenseAverages}) IS
 * computed from this database's own stored team data, not hardcoded - but the coefficients that
 * decide how much a below/above-average defense should move the projection still are. The single
 * biggest lever for prediction quality right now is data completeness (full game history,
 * correct opponent context) more than these constants - see {@code WORKPLAN.md} for what's still
 * missing on that front.
 *
 * <p>Algorithm per stat (see {@link #buildProjection}):
 *
 * <ol>
 *   <li>Blend the player's last {@link #RECENT_GAME_WINDOW} games with their full stored history
 *       - recent form should matter more than a season-long average, but 5 games alone is noisy,
 *       so it's blended rather than used on its own.
 *   <li>Add a linear adjustment based on how the player's upcoming opponent has performed on
 *       defense this season (see {@link #opponentAdjustment}).
 *   <li>Floor the result at zero - a projection can't be negative.
 * </ol>
 *
 * <p>The response also carries a {@code lowerBound}/{@code upperBound} range and a
 * {@code confidenceScore} (see {@link #buildProjection} and {@link #confidenceScore}). As of the
 * 2026-08 UI pass, the player page intentionally does not display either - the product decision
 * was to show one decisive number instead of hedging it with a range or a confidence badge. They
 * stay in the API response because a future feature (e.g. surfacing "sleeper" picks where our own
 * modeled uncertainty diverges from a market line) would need exactly this kind of
 * uncertainty/variance signal - if nothing ends up consuming them, they're a reasonable thing to
 * prune later.
 */
@Service
@Transactional(readOnly = true)
public class PlayerPredictionService {

  // How many of the player's most recent games count as "recent form" in the blend below.
  // Package-private (not private): PredictionBacktestService reuses this and the other tuning
  // constants/helpers below so the backtest scores against the exact same math the live path uses,
  // instead of risking a second, drifting copy of the algorithm.
  static final int RECENT_GAME_WINDOW = 5;
  // Roughly one season (18 regular-season weeks + a playoff buffer) of an opponent's most recent
  // defensive games. Without this, opponentAdjustment blends every stored season together, so a
  // team's 2020 defense weighs exactly as much as their current one - capping to a recent window
  // keeps it reflecting the roster/scheme actually playing now.
  static final int DEFENSE_HISTORY_GAME_WINDOW = 20;
  // Same idea for the league-wide baseline, expressed as a date cutoff instead of a per-team game
  // count since it's a single query across every team, not ordered per-team.
  static final Duration LEAGUE_AVERAGES_LOOKBACK = Duration.ofDays(210);
  // Predictions do several repository reads per request; cache briefly so repeat page loads and
  // the frontend's retry-on-load-failure loop don't recompute the same projection every time.
  private static final Duration CACHE_TTL = Duration.ofMinutes(20);
  // A player whose backfill hasn't finished yet (or who is genuinely brand new) has very few
  // stored games, so the projection built from them is close to meaningless - e.g. one game of
  // passing yards standing in for a season average. Cache that result much more briefly than a
  // normal projection so the first request right after a new player loads doesn't lock in a
  // near-zero number for the full CACHE_TTL while their history is still backfilling in the
  // background.
  static final int THIN_SAMPLE_GAME_THRESHOLD = 3;
  private static final Duration THIN_SAMPLE_CACHE_TTL = Duration.ofSeconds(15);
  private static final int CACHE_MAX_ENTRIES = 100;
  // League-wide defense averages change slowly (they're a season-long aggregate across every
  // stored team), so this is cached separately and longer than a single player's prediction.
  private static final Duration LEAGUE_AVERAGES_TTL = Duration.ofHours(6);
  // Roughly the modern-NFL average Vegas game total. Used as the baseline gameConditionsAdjustment
  // compares a real total_line against - when no real total is known (the live path doesn't have
  // one yet, see gameConditionsAdjustment's doc), the "delta" against this baseline is zero, so the
  // adjustment cleanly becomes a no-op rather than needing a separate null-check branch.
  private static final double DEFAULT_GAME_TOTAL_LINE = 44.5d;
  // Wind below this (mph) isn't treated as materially affecting passing - roughly where
  // broadcasters/bettors start actually calling out wind as a real factor.
  private static final double WIND_EFFECT_THRESHOLD_MPH = 10.0d;
  // PFR-reported ballpark for league-wide yards-after-contact per carry (roughly where recent
  // seasons land); a hand-picked estimate, not computed from this database's own stored data like
  // leagueDefenseAverages is - flagged the same way the class doc flags every other constant here.
  private static final double LEAGUE_AVERAGE_AFTER_CONTACT_YARDS_PER_CARRY = 2.6d;
  // Small on purpose (see gameConditionsAdjustment/opponentAdjustment for the same pattern) - this
  // is a quality tilt on top of the yardage the blend already projects, not a second independent
  // predictor of rushing volume.
  private static final double AFTER_CONTACT_QUALITY_COEFFICIENT = 0.35d;

  private final PlayerRepository playerRepository;
  private final PlayerGameStatRepository playerGameStatRepository;
  private final TeamRepository teamRepository;
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository;
  private final WeatherForecastClient weatherForecastClient;
  private final Clock clock = Clock.systemUTC();
  // Simple TTL + LRU cache, keyed by ESPN athlete id. LinkedHashMap in access-order mode with
  // removeEldestEntry gives LRU eviction for free once the cache exceeds CACHE_MAX_ENTRIES.
  private final Map<String, CachedPrediction> predictionCache =
      java.util.Collections.synchronizedMap(
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedPrediction> eldest) {
              return size() > CACHE_MAX_ENTRIES;
            }
          });
  private volatile CachedLeagueAverages cachedLeagueAverages;

  public PlayerPredictionService(
      PlayerRepository playerRepository,
      PlayerGameStatRepository playerGameStatRepository,
      TeamRepository teamRepository,
      TeamDefenseGameStatRepository teamDefenseGameStatRepository,
      WeatherForecastClient weatherForecastClient) {
    this.playerRepository = playerRepository;
    this.playerGameStatRepository = playerGameStatRepository;
    this.teamRepository = teamRepository;
    this.teamDefenseGameStatRepository = teamDefenseGameStatRepository;
    this.weatherForecastClient = weatherForecastClient;
  }

  public PlayerPredictionResponse getPredictionForAthleteId(String athleteId) {
    CachedPrediction cachedPrediction = predictionCache.get(athleteId);
    if (cachedPrediction != null && !cachedPrediction.isExpired(clock.instant())) {
      return cachedPrediction.response();
    }

    Player player =
        playerRepository
            .findByEspnAthleteId(athleteId)
            .orElseThrow(
                () -> new PlayerNotFoundException("No stored player found for ESPN athlete " + athleteId));

    List<PlayerGameStat> stats = playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(player.getId());
    List<PlayerGameStat> recentStats = stats.stream().limit(RECENT_GAME_WINDOW).toList();
    String position = normalizePosition(player.getPosition());

    Team currentTeam =
        player.getTeamId() == null ? null : teamRepository.findByEspnTeamId(player.getTeamId()).orElse(null);

    // No upcoming opponent on file (e.g. stale/unsynced team row, or offseason) -> opponent
    // adjustment below just falls back to 0 rather than failing the whole projection.
    List<TeamDefenseGameStat> opponentDefenseHistory =
        currentTeam == null || currentTeam.getUpcomingOpponentTeamId() == null
            ? List.of()
            : teamDefenseGameStatRepository
                .findAllByTeam_IdOrderByGameDateDesc(
                    teamRepository.findByEspnTeamId(currentTeam.getUpcomingOpponentTeamId()).map(Team::getId).orElse(-1L))
                .stream()
                .limit(DEFENSE_HISTORY_GAME_WINDOW)
                .toList();

    LeagueDefenseAverages leagueAverages = leagueDefenseAverages();
    GameConditions conditions = resolveLiveGameConditions(currentTeam);
    List<String> metrics = metricsForPosition(position);
    List<PlayerPredictionResponse.PredictionSummaryResponse> projections =
        metrics.stream()
            .map(
                metric ->
                    buildProjection(
                        metric, recentStats, stats, position, opponentDefenseHistory, leagueAverages, conditions))
            .toList();

    PlayerPredictionResponse response =
        new PlayerPredictionResponse(
        player.getEspnAthleteId(),
        player.getDisplayName(),
        player.getPosition(),
        projections,
        confidenceScore(stats, recentStats),
        clock.instant());

    Duration ttl = stats.size() < THIN_SAMPLE_GAME_THRESHOLD ? THIN_SAMPLE_CACHE_TTL : CACHE_TTL;
    predictionCache.put(athleteId, new CachedPrediction(response, clock.instant().plus(ttl)));
    return response;
  }

  /**
   * Resolves what {@link #gameConditionsAdjustment} can actually know about a player's upcoming
   * game: the host stadium's roof (from {@code Team.venueIndoor}, static/known in advance) and, for
   * outdoor games within the forecast window, a live wind forecast. Falls back to {@link
   * GameConditions#UNKNOWN} whenever any piece is missing (stale/unsynced team, no upcoming game
   * on file, host team not resolvable) rather than guessing - an unknown condition is a no-op
   * adjustment, not a wrong one.
   */
  private GameConditions resolveLiveGameConditions(Team currentTeam) {
    if (currentTeam == null
        || currentTeam.getUpcomingGameIsHome() == null
        || currentTeam.getUpcomingGameTime() == null) {
      return GameConditions.UNKNOWN;
    }

    Team hostTeam =
        Boolean.TRUE.equals(currentTeam.getUpcomingGameIsHome())
            ? currentTeam
            : (currentTeam.getUpcomingOpponentTeamId() == null
                ? null
                : teamRepository.findByEspnTeamId(currentTeam.getUpcomingOpponentTeamId()).orElse(null));
    if (hostTeam == null) {
      return GameConditions.UNKNOWN;
    }

    // ESPN's venueIndoor doesn't distinguish "dome" from "retractable, currently closed" - treating
    // anything not explicitly indoor as outdoors is an approximation, same spirit as this file's
    // other hand-picked constants.
    String roof = Boolean.TRUE.equals(hostTeam.getVenueIndoor()) ? "dome" : "outdoors";
    GameConditions baseConditions = new GameConditions(roof, null, null);
    if (!baseConditions.isOutdoors() || hostTeam.getAbbreviation() == null) {
      return baseConditions;
    }

    WeatherForecastClient.Forecast forecast =
        weatherForecastClient.forecastFor(hostTeam.getAbbreviation(), currentTeam.getUpcomingGameTime());
    return forecast == null ? baseConditions : new GameConditions(roof, forecast.windMph(), null);
  }

  /**
   * Core projection for one stat (e.g. "rushingYards"). See the class doc for the algorithm in
   * prose; {@code blendedMean} is the 65/35 recent/season split, {@code opponentAdjustment}
   * nudges it toward how the upcoming opponent's defense has performed. The 0.65/0.35 split is
   * arbitrary (weights recent form higher without letting a small 5-game sample dominate) and
   * not derived from any backtest.
   */
  PlayerPredictionResponse.PredictionSummaryResponse buildProjection(
      String metric,
      List<PlayerGameStat> recentStats,
      List<PlayerGameStat> allStats,
      String position,
      List<TeamDefenseGameStat> opponentDefenseHistory,
      LeagueDefenseAverages leagueAverages,
      GameConditions conditions) {
    List<Double> recentValues = metricValues(recentStats, metric);
    List<Double> allValues = metricValues(allStats, metric);

    double recentAverage = recentValues.isEmpty() ? 0.0d : recentValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double seasonAverage = allValues.isEmpty() ? 0.0d : allValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double blendedMean = (0.65d * recentAverage) + (0.35d * seasonAverage);
    double opponentAdjustment = opponentAdjustment(metric, opponentDefenseHistory, leagueAverages);
    double conditionsAdjustment = gameConditionsAdjustment(metric, conditions);
    double rushingQualityAdjustment = rushingQualityAdjustment(metric, recentStats);
    double projectedMean =
        Math.max(0.0d, blendedMean + opponentAdjustment + conditionsAdjustment + rushingQualityAdjustment);

    // Not surfaced in the UI today (see class doc) but kept for API consumers / future use.
    // stdDev uses the full season sample (not just the recent window) since 5 games is too few
    // to estimate variance from on its own. margin uses a z-score of 1.28, i.e. roughly an 80%
    // interval around projectedMean under a normal approximation - a simplification, since the
    // interval doesn't account for the opponent/conditions adjustments' own uncertainty.
    double stdDev = standardDeviation(allValues);
    int sampleSize = Math.max(1, allValues.size());
    double margin = 1.28d * (stdDev / Math.sqrt(sampleSize));
    double lower = Math.max(0.0d, projectedMean - margin);
    double upper = projectedMean + margin;

    return new PlayerPredictionResponse.PredictionSummaryResponse(
        metric,
        projectedMean,
        lower,
        upper,
        sampleSize,
        opponentAdjustment,
        conditionsAdjustment,
        rushingQualityAdjustment,
        notesForMetric(position, metric));
  }

  List<Double> metricValues(List<PlayerGameStat> stats, String metric) {
    List<Double> values = new ArrayList<>();
    for (PlayerGameStat stat : stats) {
      Integer value = switch (metric) {
        case "passingYards" -> stat.getPassingYards();
        case "rushingYards" -> stat.getRushingYards();
        case "receivingYards" -> stat.getReceivingYards();
        case "receptions" -> stat.getReceptions();
        case "touchdowns" -> stat.getTotalTouchdowns() != null ? stat.getTotalTouchdowns() : stat.getTouchdowns();
        case "passingTouchdowns" -> stat.getPassingTouchdowns();
        case "rushingTouchdowns" -> stat.getRushingTouchdowns();
        case "turnovers" -> stat.getTurnovers();
        default -> null;
      };
      if (value != null) {
        values.add(value.doubleValue());
      }
    }
    return values;
  }

  /**
   * Nudges a projection based on the upcoming opponent's defensive history this season. For each
   * metric: average what the opponent has allowed per game, compare it to the real league-average
   * baseline computed from every stored team's defensive history (see {@link
   * #leagueDefenseAverages}), and scale the difference by a small coefficient.
   *
   * <p>The coefficients (0.08-0.10 for yardage, 0.02-0.03 for receptions/touchdowns) are small on
   * purpose so a single below-average defense doesn't swing the projection wildly - e.g. a
   * defense allowing 25 yards/game more than league average only shifts a projection by ~2 yards.
   * That's a deliberate, if arbitrary, choice to keep this a nudge rather than the dominant term;
   * not derived from a backtest.
   */
  double opponentAdjustment(
      String metric, List<TeamDefenseGameStat> defenseHistory, LeagueDefenseAverages leagueAverages) {
    if (defenseHistory.isEmpty()) {
      return 0.0d;
    }

    List<Integer> values =
        defenseHistory.stream()
            .map(
                stat ->
                switch (metric) {
                      case "passingYards" -> stat.getPassingYardsAllowed();
                      case "rushingYards" -> stat.getRushingYardsAllowed();
                      case "receivingYards" -> stat.getReceivingYardsAllowed();
                      case "receptions" -> stat.getReceivingYardsAllowed();
                      case "touchdowns" -> stat.getPointsAllowed();
                      default -> null;
                    })
            .filter(java.util.Objects::nonNull)
            .toList();

    if (values.isEmpty()) {
      return 0.0d;
    }

    double opponentAverage = values.stream().mapToDouble(Integer::doubleValue).average().orElse(0.0d);
    return switch (metric) {
      case "passingYards" -> (opponentAverage - leagueAverages.passingYardsAllowed()) * 0.10d;
      case "rushingYards" -> (opponentAverage - leagueAverages.rushingYardsAllowed()) * 0.08d;
      case "receivingYards" -> (opponentAverage - leagueAverages.receivingYardsAllowed()) * 0.08d;
      case "receptions" -> (opponentAverage - leagueAverages.receivingYardsAllowed()) * 0.02d;
      case "touchdowns" -> (opponentAverage - leagueAverages.pointsAllowed()) * 0.03d;
      default -> 0.0d;
    };
  }

  /**
   * A specific game's own weather/scoring-environment context - the target game being backtested,
   * or (live path) the player's upcoming game. {@code roof}/{@code windMph}/{@code gameTotalLine}
   * being null just means "unknown," which {@link #gameConditionsAdjustment} treats as no effect,
   * not an error - true for most of the live path today (see that method's doc for why).
   */
  record GameConditions(String roof, Double windMph, Double gameTotalLine) {
    static final GameConditions UNKNOWN = new GameConditions(null, null, null);

    boolean isOutdoors() {
      return roof == null || "outdoors".equalsIgnoreCase(roof) || "open".equalsIgnoreCase(roof);
    }
  }

  /**
   * Nudges a projection based on this specific game's own conditions - wind (passing metrics only,
   * and only when the game isn't in a dome/closed-roof stadium) and the Vegas game total (a proxy
   * for expected overall scoring volume, vs. {@link #DEFAULT_GAME_TOTAL_LINE} when the real one
   * isn't known). Same small-coefficient, additive style as {@link #opponentAdjustment} - picked to
   * be directionally reasonable, not backtest-derived yet.
   *
   * <p>Backtesting uses real historical values from the target game's own stored {@code
   * PlayerGameStat} row (nflverse-sourced, via {@code nfl_schedules}). The live path only reliably
   * knows {@code roof} in advance (a static property of the host stadium, from {@code
   * Team.venueIndoor}) plus a live {@code windMph} forecast (via {@link WeatherForecastClient},
   * when the game is outdoors and within the forecastable window) - {@code gameTotalLine} isn't
   * available live yet (no game-level Vegas odds source wired up - Priority 1's odds-api.io
   * ingestion covers player props, not game lines), so live predictions get the wind/dome
   * adjustment but not the scoring-environment one until that's closed.
   */
  double gameConditionsAdjustment(String metric, GameConditions conditions) {
    double adjustment = 0.0d;

    if (conditions.isOutdoors() && conditions.windMph() != null) {
      double windAboveThreshold = Math.max(0.0d, conditions.windMph() - WIND_EFFECT_THRESHOLD_MPH);
      adjustment += switch (metric) {
        case "passingYards" -> -windAboveThreshold * 1.2d;
        case "passingTouchdowns" -> -windAboveThreshold * 0.015d;
        default -> 0.0d;
      };
    }

    double totalLine = conditions.gameTotalLine() != null ? conditions.gameTotalLine() : DEFAULT_GAME_TOTAL_LINE;
    double totalDelta = totalLine - DEFAULT_GAME_TOTAL_LINE;
    adjustment += switch (metric) {
      case "passingYards" -> totalDelta * 3.0d;
      case "rushingYards" -> totalDelta * 1.0d;
      case "receivingYards" -> totalDelta * 2.0d;
      case "passingTouchdowns", "rushingTouchdowns", "touchdowns" -> totalDelta * 0.03d;
      default -> 0.0d;
    };

    return adjustment;
  }

  /**
   * Nudges {@code rushingYards} using PFR's advanced rushing charting ({@code
   * PlayerGameStat.rushingYardsAfterContact}/{@code carries}): a player's own recent
   * yards-after-contact-per-carry, compared against a league-average baseline, as a
   * quality/reliability signal independent of the blend above. Before-contact + after-contact
   * yards already roughly sum to the rushing yards the blend already projects - this isn't a
   * second volume predictor, just a small tilt (see {@link #AFTER_CONTACT_QUALITY_COEFFICIENT})
   * scaled by how many carries the player actually gets. Returns 0 for every other metric, and for
   * players with no PFR advanced-rushing data yet (charting only goes back to 2018 and isn't
   * published for every game).
   */
  double rushingQualityAdjustment(String metric, List<PlayerGameStat> recentStats) {
    if (!"rushingYards".equals(metric)) {
      return 0.0d;
    }

    int totalCarries = 0;
    int totalAfterContactYards = 0;
    int gamesWithData = 0;
    for (PlayerGameStat stat : recentStats) {
      Integer afterContactYards = stat.getRushingYardsAfterContact();
      Integer carries = stat.getCarries();
      if (afterContactYards != null && carries != null && carries > 0) {
        totalAfterContactYards += afterContactYards;
        totalCarries += carries;
        gamesWithData++;
      }
    }

    if (gamesWithData == 0 || totalCarries == 0) {
      return 0.0d;
    }

    double playerRate = totalAfterContactYards / (double) totalCarries;
    double averageCarriesPerGame = totalCarries / (double) gamesWithData;
    return (playerRate - LEAGUE_AVERAGE_AFTER_CONTACT_YARDS_PER_CARRY)
        * averageCarriesPerGame
        * AFTER_CONTACT_QUALITY_COEFFICIENT;
  }

  /**
   * Per-game defensive averages across every stored team's {@code TeamDefenseGameStat} history -
   * the real baseline "opponentAdjustment" compares an upcoming opponent against, instead of a
   * hardcoded guess. Cached ({@link #LEAGUE_AVERAGES_TTL}) since it's identical for every player
   * and only changes as more team-defense data accumulates. Falls back to rough NFL ballpark
   * figures (225 passing yards, 110 rushing, 125 receiving, 21 points allowed per game) only if
   * no team-defense data is stored at all yet - so a fresh/empty database still produces a
   * reasonable adjustment instead of dividing by zero or always returning 0.
   */
  private LeagueDefenseAverages leagueDefenseAverages() {
    CachedLeagueAverages cached = cachedLeagueAverages;
    if (cached != null && !cached.isExpired(clock.instant())) {
      return cached.averages();
    }

    LeagueDefenseAverages averages = leagueDefenseAverages(LocalDate.now(clock));
    cachedLeagueAverages = new CachedLeagueAverages(averages, clock.instant().plus(LEAGUE_AVERAGES_TTL));
    return averages;
  }

  /**
   * Historical-date twin of the live, cached {@link #leagueDefenseAverages()} above - same
   * averaging logic and fallbacks, but scoped to {@code [asOfDate - LEAGUE_AVERAGES_LOOKBACK,
   * asOfDate]} instead of "now", and deliberately uncached since {@link PredictionBacktestService}
   * calls this with many different dates (a single-entry cache would just thrash).
   */
  LeagueDefenseAverages leagueDefenseAverages(LocalDate asOfDate) {
    List<TeamDefenseGameStat> allDefenseGames =
        teamDefenseGameStatRepository.findAllByGameDateBetween(
            asOfDate.minusDays(LEAGUE_AVERAGES_LOOKBACK.toDays()), asOfDate);
    return leagueDefenseAveragesFrom(allDefenseGames);
  }

  /**
   * Same averaging/fallback logic as {@link #leagueDefenseAverages(LocalDate)}, but over an
   * already-fetched list instead of issuing its own query - lets {@link PredictionBacktestService}
   * preload every {@code TeamDefenseGameStat} once and filter in memory per backtested game,
   * instead of one DB round-trip per game (confirmed directly: the naive per-game-query version of
   * this backtest took over two minutes against only 1,252 stored defense rows).
   */
  LeagueDefenseAverages leagueDefenseAveragesFrom(List<TeamDefenseGameStat> defenseGames) {
    return new LeagueDefenseAverages(
        averageOrDefault(defenseGames, TeamDefenseGameStat::getPassingYardsAllowed, 225.0d),
        averageOrDefault(defenseGames, TeamDefenseGameStat::getRushingYardsAllowed, 110.0d),
        averageOrDefault(defenseGames, TeamDefenseGameStat::getReceivingYardsAllowed, 125.0d),
        averageOrDefault(defenseGames, TeamDefenseGameStat::getPointsAllowed, 21.0d));
  }

  private double averageOrDefault(
      List<TeamDefenseGameStat> defenseGames,
      java.util.function.Function<TeamDefenseGameStat, Integer> accessor,
      double defaultValue) {
    List<Integer> values = defenseGames.stream().map(accessor).filter(java.util.Objects::nonNull).toList();
    return values.isEmpty()
        ? defaultValue
        : values.stream().mapToDouble(Integer::doubleValue).average().orElse(defaultValue);
  }

  /**
   * Heuristic "how much should you trust this" score (0-1), separate from the statistical
   * interval above. Not currently displayed in the UI (see class doc) but kept for API
   * consumers. Blends three components, each capped at 1.0:
   *
   * <ul>
   *   <li>sample size adequacy (45% weight) - maxes out once the player has 10+ stored games
   *   <li>recent-data adequacy (30% weight) - maxes out once at least 5 recent games are present
   *   <li>freshness (25% weight) - decays linearly over a year since the last stored game date
   * </ul>
   *
   * <p>The 45/30/25 split and the 10-game/5-game/365-day caps are hand-picked, not derived from
   * how well the score actually predicts projection accuracy.
   */
  private double confidenceScore(List<PlayerGameStat> allStats, List<PlayerGameStat> recentStats) {
    if (allStats.isEmpty()) {
      return 0.15d;
    }

    double sampleComponent = Math.min(1.0d, allStats.size() / 10.0d);
    double recencyComponent = Math.min(1.0d, recentStats.size() / 5.0d);
    double freshnessComponent =
        allStats.stream().map(PlayerGameStat::getGameDate).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder())
            .map(date -> 1.0d - Math.min(1.0d, ChronoUnit.DAYS.between(date, LocalDate.now(clock)) / 365.0d))
            .orElse(0.5d);
    return roundTwoDecimals((sampleComponent * 0.45d) + (recencyComponent * 0.30d) + (freshnessComponent * 0.25d));
  }

  private List<String> notesForMetric(String position, String metric) {
    if (position == null) {
      return List.of("Position unknown, using blended historical form.");
    }

    return switch (position) {
      case "QB" -> metric.equals("passingYards") || metric.equals("rushingYards") || metric.equals("passingTouchdowns") || metric.equals("turnovers")
          ? List.of("QB projection uses passing, rushing, and turnover form.")
          : List.of("QB projections exclude receiving metrics.");
      case "RB" -> metric.equals("rushingYards") || metric.equals("receivingYards")
          ? List.of("RB projection blends workload and yardage.")
          : List.of("RB scoring is based on recent touchdown rates.");
      case "WR", "TE" -> metric.equals("receivingYards") || metric.equals("receptions")
          ? List.of("Receiver projection blends targets, receptions, and yardage.")
          : List.of("Receiver scoring is based on recent touchdown rates.");
      default -> List.of("Projection uses available historical game logs.");
    };
  }

  private double standardDeviation(List<Double> values) {
    if (values.size() < 2) {
      return 0.0d;
    }

    double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
    double variance =
        values.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum() / (values.size() - 1);
    return Math.sqrt(variance);
  }

  private double roundTwoDecimals(double value) {
    return Math.round(value * 100.0d) / 100.0d;
  }

  // Which stats we bother projecting per position - e.g. QBs don't get a receiving projection,
  // and non-QBs don't get a passing one. "default" covers FB/unusual/missing position values.
  List<String> metricsForPosition(String position) {
    return switch (position) {
      case "QB" -> List.of("passingYards", "rushingYards", "passingTouchdowns", "turnovers");
      case "RB" -> List.of("rushingYards", "receivingYards", "receptions", "touchdowns");
      case "WR", "TE", "FB" -> List.of("receivingYards", "receptions", "touchdowns");
      case null, default -> List.of("rushingYards", "receivingYards", "receptions", "touchdowns");
    };
  }

  String normalizePosition(String position) {
    return position == null ? null : position.trim().toUpperCase();
  }

  private record CachedPrediction(PlayerPredictionResponse response, Instant expiresAt) {
    private boolean isExpired(Instant now) {
      return now.isAfter(expiresAt);
    }
  }

  record LeagueDefenseAverages(
      double passingYardsAllowed,
      double rushingYardsAllowed,
      double receivingYardsAllowed,
      double pointsAllowed) {}

  private record CachedLeagueAverages(LeagueDefenseAverages averages, Instant expiresAt) {
    private boolean isExpired(Instant now) {
      return now.isAfter(expiresAt);
    }
  }
}

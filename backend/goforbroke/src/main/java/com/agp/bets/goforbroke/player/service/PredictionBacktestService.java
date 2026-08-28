package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.odds.domain.PlayerPropLine;
import com.agp.bets.goforbroke.odds.repository.PlayerPropLineRepository;
import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.player.web.dto.PlayerPredictionResponse.PredictionSummaryResponse;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import com.agp.bets.goforbroke.team.service.NflverseTeamAbbreviations;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backtests {@link PlayerPredictionService}'s heuristic against real, already-stored data - first
 * against what actually happened (outcome MAE), then against real captured market lines (the
 * metric that actually matters for the core mission: how often the projection would have been on
 * the correct side of the line).
 *
 * <p>Point-in-time correctness is the entire point of a backtest: for a target historical game,
 * only games strictly before it are used to build the projection, and the opponent adjustment uses
 * that game's real stored opponent (not whatever the player's team is "currently" facing). This
 * reuses {@link PlayerPredictionService#buildProjection} directly (now package-private) rather than
 * re-implementing the scoring math, so the backtest can never silently drift from what the live
 * prediction endpoint actually computes.
 */
@Service
@Transactional(readOnly = true)
public class PredictionBacktestService {

  // odds-api.io market names that map 1:1 onto one of our projected metrics. Markets without a
  // clean mapping (e.g. "Touchdown Scorers" is an anytime/first bet, not a total-touchdowns O/U
  // line) are intentionally left out rather than compared approximately.
  private static final Map<String, String> MARKET_TO_METRIC =
      Map.of(
          "Passing Yards O/U", "passingYards",
          "Rushing Yards O/U", "rushingYards",
          "Receiving Yards O/U", "receivingYards",
          "Receptions O/U", "receptions",
          "Passing Touchdowns O/U", "passingTouchdowns");

  private final PlayerRepository playerRepository;
  private final PlayerGameStatRepository playerGameStatRepository;
  private final TeamRepository teamRepository;
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository;
  private final PlayerPropLineRepository playerPropLineRepository;
  private final PlayerPredictionService predictionService;

  public PredictionBacktestService(
      PlayerRepository playerRepository,
      PlayerGameStatRepository playerGameStatRepository,
      TeamRepository teamRepository,
      TeamDefenseGameStatRepository teamDefenseGameStatRepository,
      PlayerPropLineRepository playerPropLineRepository,
      PlayerPredictionService predictionService) {
    this.playerRepository = playerRepository;
    this.playerGameStatRepository = playerGameStatRepository;
    this.teamRepository = teamRepository;
    this.teamDefenseGameStatRepository = teamDefenseGameStatRepository;
    this.playerPropLineRepository = playerPropLineRepository;
    this.predictionService = predictionService;
  }

  public record OutcomeStats(int sampleSize, double meanAbsoluteError, double meanActual) {}

  public record OutcomeBacktestSummary(Map<String, OutcomeStats> byMetric) {}

  public record MarketLineStats(int comparisons, int pushes, int correctSide, double hitRate) {}

  public record MarketLineBacktestSummary(Map<String, MarketLineStats> byMetric) {}

  /**
   * One row of real, already-computed inputs for a single historical (player, target game, metric)
   * - the raw material for a real coefficient-calibration regression (fit {@code actual ~
   * recentAverage + seasonAverage + opponentAdjustment + ...} in R), instead of every coefficient
   * in {@link PlayerPredictionService} staying a permanent hand-picked guess. Deliberately exposes
   * {@code recentAverage}/{@code seasonAverage} separately rather than the already-blended {@code
   * blendedMean} - calibrating the 0.65/0.35 blend split itself needs both terms as independent
   * regression inputs, not their pre-combined value. Every other field is exactly what {@link
   * PlayerPredictionService#buildProjection} already computed for that row (via the same {@code
   * PredictionSummaryResponse} the live path returns) - no separate/duplicate feature computation
   * that could silently drift from what predictions actually use.
   */
  public record CalibrationRow(
      // Added 2026-08-28 so a real temporal train/test split is possible (every calibration this
      // app has ever done before this was fit and evaluated on the exact same full historical
      // dataset - no held-out validation at all). Null gameDate is possible (see PlayerGameStat's
      // own doc - pre-2014 nfl_schedules gap), season is always real.
      Integer season,
      String gameDate,
      double actual,
      double recentAverage,
      double seasonAverage,
      double opponentAdjustment,
      double conditionsAdjustment,
      double rushingQualityAdjustment,
      double advancedMetricAdjustment,
      double targetShareAdjustment,
      // Raw trailing deltas (opponent's own recent average minus the league baseline) for the
      // participation-sourced signals - not summed into opponentAdjustment above, and not yet
      // scaled by any coefficient, since none has been picked yet. Exported so a real regression
      // can test each for incremental significance controlling for every term above (which
      // already includes the PFR-sourced pressures/missedTacklePct signals) before any coefficient
      // gets chosen - see PlayerPredictionService#zoneCoverageRateDelta's doc and WORKPLAN.md.
      double zoneCoverageRateDelta,
      double avgPassRushersDelta,
      double avgDefendersInBoxDelta) {}

  public OutcomeBacktestSummary runOutcomeBacktest() {
    Map<String, OutcomeAccumulator> accumulators = new LinkedHashMap<>();

    forEachBacktestableGame(
        (player, position, targetGame, recentStats, allStats, opponentDefenseHistory, leagueAverages) -> {
          for (String metric : predictionService.metricsForPosition(position)) {
            List<Double> actualValues = predictionService.metricValues(List.of(targetGame), metric);
            if (actualValues.isEmpty()) {
              continue;
            }

            // gameStatus is deliberately null here - see PlayerPredictionService
            // #injuryStatusMultiplier's doc: nothing in this database records what a player's
            // weekly status *was* for a historical game, only the live-fetched current one exists.
            PredictionSummaryResponse projection =
                predictionService.buildProjection(
                    metric, recentStats, allStats, position, opponentDefenseHistory, leagueAverages,
                    gameConditionsFor(targetGame), null);
            accumulators
                .computeIfAbsent(metric, key -> new OutcomeAccumulator())
                .add(projection.mean(), actualValues.get(0));
          }
        });

    Map<String, OutcomeStats> byMetric = new LinkedHashMap<>();
    accumulators.forEach((metric, accumulator) -> byMetric.put(metric, accumulator.toStats()));
    return new OutcomeBacktestSummary(byMetric);
  }

  public MarketLineBacktestSummary runMarketLineBacktest() {
    Map<Long, List<PlayerPropLine>> linesByPlayerId = new LinkedHashMap<>();
    for (PlayerPropLine line :
        playerPropLineRepository.findByPlayerIsNotNullAndMarketRawIn(List.copyOf(MARKET_TO_METRIC.keySet()))) {
      linesByPlayerId.computeIfAbsent(line.getPlayer().getId(), key -> new ArrayList<>()).add(line);
    }

    Map<String, MarketLineAccumulator> accumulators = new LinkedHashMap<>();

    forEachBacktestableGame(
        (player, position, targetGame, recentStats, allStats, opponentDefenseHistory, leagueAverages) -> {
          List<PlayerPropLine> playerLines = linesByPlayerId.get(player.getId());
          if (playerLines == null || playerLines.isEmpty()) {
            return;
          }

          for (Map.Entry<String, String> marketAndMetric : MARKET_TO_METRIC.entrySet()) {
            String metric = marketAndMetric.getValue();
            PlayerPropLine matchingLine =
                findMatchingLine(playerLines, marketAndMetric.getKey(), targetGame.getGameDate());
            if (matchingLine == null || matchingLine.getLine() == null) {
              continue;
            }

            List<Double> actualValues = predictionService.metricValues(List.of(targetGame), metric);
            if (actualValues.isEmpty()) {
              continue;
            }

            // gameStatus is deliberately null here - see PlayerPredictionService
            // #injuryStatusMultiplier's doc: nothing in this database records what a player's
            // weekly status *was* for a historical game, only the live-fetched current one exists.
            PredictionSummaryResponse projection =
                predictionService.buildProjection(
                    metric, recentStats, allStats, position, opponentDefenseHistory, leagueAverages,
                    gameConditionsFor(targetGame), null);

            double lineValue = matchingLine.getLine();
            double actual = actualValues.get(0);
            boolean isPush = actual == lineValue;
            boolean actualWentOver = actual > lineValue;
            boolean projectedOver = projection.mean() > lineValue;
            boolean correctSide = !isPush && actualWentOver == projectedOver;

            accumulators.computeIfAbsent(metric, key -> new MarketLineAccumulator()).add(isPush, correctSide);
          }
        });

    Map<String, MarketLineStats> byMetric = new LinkedHashMap<>();
    accumulators.forEach((metric, accumulator) -> byMetric.put(metric, accumulator.toStats()));
    return new MarketLineBacktestSummary(byMetric);
  }

  /** See {@link CalibrationRow}'s doc - a one-off data export for offline (R) regression, not a live-facing summary. */
  public Map<String, List<CalibrationRow>> runCalibrationExport() {
    Map<String, List<CalibrationRow>> byMetric = new LinkedHashMap<>();

    forEachBacktestableGame(
        (player, position, targetGame, recentStats, allStats, opponentDefenseHistory, leagueAverages) -> {
          for (String metric : predictionService.metricsForPosition(position)) {
            List<Double> actualValues = predictionService.metricValues(List.of(targetGame), metric);
            if (actualValues.isEmpty()) {
              continue;
            }

            List<Double> recentValues = predictionService.metricValues(recentStats, metric);
            List<Double> allValues = predictionService.metricValues(allStats, metric);
            double recentAverage = average(recentValues);
            double seasonAverage = average(allValues);

            PredictionSummaryResponse projection =
                predictionService.buildProjection(
                    metric, recentStats, allStats, position, opponentDefenseHistory, leagueAverages,
                    gameConditionsFor(targetGame), null);

            byMetric
                .computeIfAbsent(metric, key -> new ArrayList<>())
                .add(
                    new CalibrationRow(
                        targetGame.getSeason(),
                        targetGame.getGameDate() == null ? null : targetGame.getGameDate().toString(),
                        actualValues.get(0),
                        recentAverage,
                        seasonAverage,
                        projection.opponentAdjustment(),
                        projection.conditionsAdjustment(),
                        projection.rushingQualityAdjustment(),
                        projection.advancedMetricAdjustment(),
                        projection.targetShareAdjustment(),
                        predictionService.zoneCoverageRateDelta(opponentDefenseHistory, leagueAverages),
                        predictionService.avgPassRushersDelta(opponentDefenseHistory, leagueAverages),
                        predictionService.avgDefendersInBoxDelta(opponentDefenseHistory, leagueAverages)));
          }
        });

    return byMetric;
  }

  private double average(List<Double> values) {
    return values.isEmpty() ? 0.0d : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
  }

  /**
   * The target game's own real, historical weather/Vegas context - sourced from nflverse via
   * {@code nfl_schedules} (already joined into {@code PlayerGameStat} at ingestion time), not a
   * live forecast. Unlike the live path, every field here is either a known real value or a known
   * null (e.g. a dome game's null wind), never "unknown because we haven't looked yet."
   */
  private PlayerPredictionService.GameConditions gameConditionsFor(PlayerGameStat targetGame) {
    return new PlayerPredictionService.GameConditions(
        targetGame.getRoof(), targetGame.getWindMph(), targetGame.getGameTotalLine());
  }

  /**
   * odds-api.io's captured {@code eventDate} is a raw UTC instant, so a night game can roll onto
   * the calendar day after the game's real (nflverse-sourced) {@code gameDate} - confirmed directly
   * during this session's odds ingestion work. Matches either the same day or the day after.
   */
  private PlayerPropLine findMatchingLine(List<PlayerPropLine> playerLines, String marketRaw, LocalDate gameDate) {
    if (gameDate == null) {
      return null;
    }

    for (PlayerPropLine line : playerLines) {
      if (!marketRaw.equals(line.getMarketRaw()) || line.getRawEvent().getEventDate() == null) {
        continue;
      }
      LocalDate eventDate = line.getRawEvent().getEventDate().atZone(ZoneOffset.UTC).toLocalDate();
      if (eventDate.equals(gameDate) || eventDate.equals(gameDate.plusDays(1))) {
        return line;
      }
    }
    return null;
  }

  /**
   * Walks every stored player's game history in chronological order, invoking {@code visitor} once
   * per game that has at least {@link PlayerPredictionService#THIN_SAMPLE_GAME_THRESHOLD} strictly-
   * prior games on file and a known game date - assembling the same point-in-time inputs
   * {@code buildProjection} needs (prior-only stats, the real historical opponent's defense history
   * up to that date, league averages as of that date). Shared by both backtest passes so the
   * point-in-time assembly logic exists in exactly one place.
   */
  private void forEachBacktestableGame(GameVisitor visitor) {
    // Preloaded once and filtered in memory per game below, instead of one DB round-trip per
    // backtested game - confirmed directly that the naive per-game-query version took over two
    // minutes against only 1,252 stored defense rows, all N+1 query overhead rather than real work.
    Map<Long, List<TeamDefenseGameStat>> defenseGamesByTeamId = new LinkedHashMap<>();
    List<TeamDefenseGameStat> allDefenseGames = teamDefenseGameStatRepository.findAll();
    for (TeamDefenseGameStat defenseGame : allDefenseGames) {
      defenseGamesByTeamId
          .computeIfAbsent(defenseGame.getTeam().getId(), key -> new ArrayList<>())
          .add(defenseGame);
    }
    Map<String, Team> teamsByAbbreviation = new LinkedHashMap<>();
    for (Team team : teamRepository.findAllByOrderByDisplayNameAsc()) {
      teamsByAbbreviation.put(team.getAbbreviation(), team);
    }

    for (Player player : playerRepository.findAllByOrderByDisplayNameAsc()) {
      String position = predictionService.normalizePosition(player.getPosition());

      // findAllByPlayer_IdOrderBySeasonDescWeekDesc is most-recent-first; reverse to chronological
      // so "games before index i" is a simple prefix.
      List<PlayerGameStat> chronological =
          new ArrayList<>(playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(player.getId()));
      java.util.Collections.reverse(chronological);

      for (int i = PlayerPredictionService.THIN_SAMPLE_GAME_THRESHOLD; i < chronological.size(); i++) {
        PlayerGameStat targetGame = chronological.get(i);
        if (targetGame.getGameDate() == null) {
          continue;
        }

        // Most-recent-prior-game-first, matching what buildProjection expects (recentStats.limit(N)
        // assumes descending order).
        List<PlayerGameStat> priorGamesDesc = new ArrayList<>(chronological.subList(0, i));
        java.util.Collections.reverse(priorGamesDesc);
        List<PlayerGameStat> recentStats =
            priorGamesDesc.stream().limit(PlayerPredictionService.RECENT_GAME_WINDOW).toList();

        List<TeamDefenseGameStat> opponentDefenseHistory =
            resolveOpponentDefenseHistory(targetGame, teamsByAbbreviation, defenseGamesByTeamId);
        List<TeamDefenseGameStat> leagueWindow =
            allDefenseGames.stream()
                .filter(
                    defenseGame ->
                        defenseGame.getGameDate() != null
                            && !defenseGame.getGameDate().isAfter(targetGame.getGameDate())
                            && !defenseGame
                                .getGameDate()
                                .isBefore(
                                    targetGame
                                        .getGameDate()
                                        .minusDays(PlayerPredictionService.LEAGUE_AVERAGES_LOOKBACK.toDays())))
                .toList();
        PlayerPredictionService.LeagueDefenseAverages leagueAverages =
            predictionService.leagueDefenseAveragesFrom(leagueWindow);

        visitor.visit(player, position, targetGame, recentStats, priorGamesDesc, opponentDefenseHistory, leagueAverages);
      }
    }
  }

  // Package-private (not private): covered directly by PredictionBacktestServiceTest, since this
  // is exactly the kind of point-in-time opponent-resolution logic that's easy to silently break
  // (see toEspnTeamAbbreviation's doc for a real case where it was broken for the entire session).
  List<TeamDefenseGameStat> resolveOpponentDefenseHistory(
      PlayerGameStat targetGame,
      Map<String, Team> teamsByAbbreviation,
      Map<Long, List<TeamDefenseGameStat>> defenseGamesByTeamId) {
    if (targetGame.getOpponentTeamId() == null) {
      return List.of();
    }

    Team opponent =
        teamsByAbbreviation.get(NflverseTeamAbbreviations.toEspnAbbreviation(targetGame.getOpponentTeamId()));
    if (opponent == null) {
      return List.of();
    }

    List<TeamDefenseGameStat> opponentGames = defenseGamesByTeamId.get(opponent.getId());
    if (opponentGames == null) {
      return List.of();
    }

    return opponentGames.stream()
        .filter(
            defenseGame ->
                defenseGame.getGameDate() != null && defenseGame.getGameDate().isBefore(targetGame.getGameDate()))
        .sorted(java.util.Comparator.comparing(TeamDefenseGameStat::getGameDate).reversed())
        .limit(PlayerPredictionService.DEFENSE_HISTORY_GAME_WINDOW)
        .toList();
  }

  @FunctionalInterface
  private interface GameVisitor {
    void visit(
        Player player,
        String position,
        PlayerGameStat targetGame,
        List<PlayerGameStat> recentStats,
        List<PlayerGameStat> allStats,
        List<TeamDefenseGameStat> opponentDefenseHistory,
        PlayerPredictionService.LeagueDefenseAverages leagueAverages);
  }

  private static final class OutcomeAccumulator {
    private int count;
    private double sumAbsError;
    private double sumActual;

    void add(double predicted, double actual) {
      count++;
      sumAbsError += Math.abs(predicted - actual);
      sumActual += actual;
    }

    OutcomeStats toStats() {
      return new OutcomeStats(count, count == 0 ? 0 : sumAbsError / count, count == 0 ? 0 : sumActual / count);
    }
  }

  private static final class MarketLineAccumulator {
    private int comparisons;
    private int pushes;
    private int correctSide;

    void add(boolean isPush, boolean correct) {
      comparisons++;
      if (isPush) {
        pushes++;
        return;
      }
      if (correct) {
        correctSide++;
      }
    }

    MarketLineStats toStats() {
      int decided = comparisons - pushes;
      double hitRate = decided == 0 ? 0 : (double) correctSide / decided;
      return new MarketLineStats(comparisons, pushes, correctSide, hitRate);
    }
  }
}

package com.agp.bets.goforbroke.team.service;

import com.agp.bets.goforbroke.team.domain.NflSchedule;
import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import com.agp.bets.goforbroke.team.domain.TeamOffenseGameStat;
import com.agp.bets.goforbroke.team.domain.TeamStrengthRating;
import com.agp.bets.goforbroke.team.repository.NflScheduleRepository;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamOffenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamStrengthRatingRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backtests {@link TeamMatchupPredictionService} against real, already-stored game results
 * (nflverse {@code nfl_schedules}, via {@code TeamStrengthRating} - see {@code
 * compute_team_strength_ratings.R}). Point-in-time correctness comes for free here, unlike the
 * player-prop backtest: {@code TeamStrengthRating.ratingBefore} already IS each team's rating
 * entering that exact game (computed by replaying Elo forward in chronological order), so there's
 * no separate "assemble what was knowable as of this date" step to get right.
 *
 * <p>Validates against real final scores first (winner accuracy, margin error) - per explicit
 * product direction, this model's whole point is that it can be evaluated on results we already
 * fully have, without depending on Priority 1's paid odds decision. {@link #runSpreadBacktest} is
 * a secondary read against the real market ({@code nfl_schedules.spread_line}, already stored,
 * zero new ingestion cost) - not required for this model to be useful, but a natural "does this
 * beat the market" question once the model itself is trustworthy.
 */
@Service
@Transactional(readOnly = true)
public class TeamMatchupBacktestService {

  private final TeamStrengthRatingRepository teamStrengthRatingRepository;
  private final NflScheduleRepository nflScheduleRepository;
  private final TeamOffenseGameStatRepository teamOffenseGameStatRepository;
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository;
  private final TeamMatchupPredictionService predictionService;

  public TeamMatchupBacktestService(
      TeamStrengthRatingRepository teamStrengthRatingRepository,
      NflScheduleRepository nflScheduleRepository,
      TeamOffenseGameStatRepository teamOffenseGameStatRepository,
      TeamDefenseGameStatRepository teamDefenseGameStatRepository,
      TeamMatchupPredictionService predictionService) {
    this.teamStrengthRatingRepository = teamStrengthRatingRepository;
    this.nflScheduleRepository = nflScheduleRepository;
    this.teamOffenseGameStatRepository = teamOffenseGameStatRepository;
    this.teamDefenseGameStatRepository = teamDefenseGameStatRepository;
    this.predictionService = predictionService;
  }

  public record TeamMatchupBacktestSummary(
      int games, int correctWinnerPicks, double winnerAccuracy, double meanAbsoluteMarginError) {}

  public TeamMatchupBacktestSummary runBacktest() {
    List<TeamStrengthRating> allRatings = teamStrengthRatingRepository.findAll();

    // Keyed by "gameDate|team abbreviation" so each home-team row can find its away-team
    // counterpart for the same game in one lookup, instead of a second query per game.
    Map<String, TeamStrengthRating> byDateAndTeam = new HashMap<>();
    Map<Long, List<TeamStrengthRating>> gamesByTeamId = new HashMap<>();
    for (TeamStrengthRating rating : allRatings) {
      byDateAndTeam.put(rating.getGameDate() + "|" + rating.getTeam().getAbbreviation(), rating);
      gamesByTeamId.computeIfAbsent(rating.getTeam().getId(), key -> new ArrayList<>()).add(rating);
    }

    int games = 0;
    int correctWinnerPicks = 0;
    double sumAbsMarginError = 0.0d;

    for (TeamStrengthRating homeRow : allRatings) {
      if (!"home".equals(homeRow.getHomeAway())) {
        continue;
      }

      String awayAbbreviation = NflverseTeamAbbreviations.toEspnAbbreviation(homeRow.getOpponentTeamId());
      TeamStrengthRating awayRow = byDateAndTeam.get(homeRow.getGameDate() + "|" + awayAbbreviation);
      if (awayRow == null || homeRow.getRatingBefore() == null || awayRow.getRatingBefore() == null) {
        continue;
      }

      // Real-calibrated offense/defense terms (2026-08-28) - see
      // TeamMatchupPredictionService.OFFENSE_DEFENSE_INTERCEPT's doc. Null (a team's first game on
      // record) falls back to pure Elo automatically inside predict().
      Double homeRecentScored =
          recentAverage(gamesByTeamId.get(homeRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsScored);
      Double homeRecentAllowed =
          recentAverage(gamesByTeamId.get(homeRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsAllowed);
      Double awayRecentScored =
          recentAverage(gamesByTeamId.get(awayRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsScored);

      TeamMatchupPredictionService.MatchupPrediction prediction =
          predictionService.predict(
              homeRow.getRatingBefore(), awayRow.getRatingBefore(), homeRecentScored, homeRecentAllowed, awayRecentScored);

      int actualMargin = homeRow.getPointsScored() - homeRow.getPointsAllowed();
      boolean actualHomeWin = actualMargin > 0;
      boolean predictedHomeWin = prediction.predictedMargin() > 0;

      games++;
      if (actualHomeWin == predictedHomeWin) {
        correctWinnerPicks++;
      }
      sumAbsMarginError += Math.abs(prediction.predictedMargin() - actualMargin);
    }

    double winnerAccuracy = games == 0 ? 0.0d : (double) correctWinnerPicks / games;
    double meanAbsoluteMarginError = games == 0 ? 0.0d : sumAbsMarginError / games;
    return new TeamMatchupBacktestSummary(games, correctWinnerPicks, winnerAccuracy, meanAbsoluteMarginError);
  }

  public record TotalsBacktestSummary(
      int games,
      double meanAbsoluteError,
      double meanPredictedTotal,
      double meanActualTotal,
      double minPredictedTotal,
      double maxPredictedTotal,
      double minActualTotal,
      double maxActualTotal) {}

  /**
   * Answers a real question raised 2026-08-20 rather than debating it: does {@code
   * UpcomingTeamMatchupService}'s per-game-total math actually capture genuine defensive
   * slugfests and shootouts, or does it smooth everything back toward a narrow middle range?
   * Reports the predicted-total range side by side with the real-total range so the answer is a
   * direct comparison, not an inference from MAE alone. Point-in-time correct, same principle as
   * the player-prop backtest: only games strictly before the target game feed its recent-scoring
   * averages.
   *
   * <p>Scores the exact same hybrid logic the live path uses (prefer the real posted {@code
   * nfl_schedules.total_line}, fall back to the computed recent-scoring estimate) - a real
   * 2026-08-20 backtest found the real total explains meaningfully more variance (~9%) than the
   * computed estimate alone (~4%), so this is genuinely the more accurate source when it exists,
   * not just a convenient one.
   */
  public TotalsBacktestSummary runTotalsBacktest() {
    List<TeamStrengthRating> allRatings = teamStrengthRatingRepository.findAll();

    Map<String, TeamStrengthRating> byDateAndTeam = new HashMap<>();
    Map<Long, List<TeamStrengthRating>> gamesByTeamId = new HashMap<>();
    for (TeamStrengthRating rating : allRatings) {
      byDateAndTeam.put(rating.getGameDate() + "|" + rating.getTeam().getAbbreviation(), rating);
      gamesByTeamId.computeIfAbsent(rating.getTeam().getId(), key -> new ArrayList<>()).add(rating);
    }

    // Keyed by "gameday|home team ESPN abbreviation" so each target game can look up its own real
    // posted total in one lookup instead of a query per game.
    Map<String, Double> totalLineByDateAndHomeTeam = new HashMap<>();
    for (NflSchedule schedule : nflScheduleRepository.findAll()) {
      if (schedule.getTotalLine() != null) {
        totalLineByDateAndHomeTeam.put(
            schedule.getGameday() + "|" + NflverseTeamAbbreviations.toEspnAbbreviation(schedule.getHomeTeam()),
            schedule.getTotalLine());
      }
    }

    int games = 0;
    double sumAbsError = 0.0d;
    double sumPredicted = 0.0d;
    double sumActual = 0.0d;
    double minPredicted = Double.MAX_VALUE;
    double maxPredicted = -Double.MAX_VALUE;
    double minActual = Double.MAX_VALUE;
    double maxActual = -Double.MAX_VALUE;

    for (TeamStrengthRating homeRow : allRatings) {
      if (!"home".equals(homeRow.getHomeAway())) {
        continue;
      }

      String awayAbbreviation = NflverseTeamAbbreviations.toEspnAbbreviation(homeRow.getOpponentTeamId());
      TeamStrengthRating awayRow = byDateAndTeam.get(homeRow.getGameDate() + "|" + awayAbbreviation);
      if (awayRow == null) {
        continue;
      }

      Double realTotalLine =
          totalLineByDateAndHomeTeam.get(homeRow.getGameDate() + "|" + homeRow.getTeam().getAbbreviation());

      Double predictedTotal;
      if (realTotalLine != null) {
        predictedTotal = realTotalLine;
      } else {
        Double homeScoredAvg =
            recentAverage(gamesByTeamId.get(homeRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsScored);
        Double homeAllowedAvg =
            recentAverage(gamesByTeamId.get(homeRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsAllowed);
        Double awayScoredAvg =
            recentAverage(gamesByTeamId.get(awayRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsScored);
        Double awayAllowedAvg =
            recentAverage(gamesByTeamId.get(awayRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsAllowed);
        if (homeScoredAvg == null || homeAllowedAvg == null || awayScoredAvg == null || awayAllowedAvg == null) {
          continue;
        }
        predictedTotal = predictionService.expectedTotalPoints(homeScoredAvg, homeAllowedAvg, awayScoredAvg, awayAllowedAvg);
      }

      int actualTotal = homeRow.getPointsScored() + homeRow.getPointsAllowed();

      games++;
      sumAbsError += Math.abs(predictedTotal - actualTotal);
      sumPredicted += predictedTotal;
      sumActual += actualTotal;
      minPredicted = Math.min(minPredicted, predictedTotal);
      maxPredicted = Math.max(maxPredicted, predictedTotal);
      minActual = Math.min(minActual, actualTotal);
      maxActual = Math.max(maxActual, actualTotal);
    }

    if (games == 0) {
      return new TotalsBacktestSummary(0, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
    }

    return new TotalsBacktestSummary(
        games,
        sumAbsError / games,
        sumPredicted / games,
        sumActual / games,
        minPredicted,
        maxPredicted,
        minActual,
        maxActual);
  }

  public record SpreadBacktestSummary(int games, int pushes, int correctSide, double hitRate) {}

  /**
   * Does our own predicted margin beat the real posted spread more often than a coin flip? For
   * every backtestable game with a real posted {@code nfl_schedules.spread_line}, compares whether
   * our predicted margin diverged from the market's implied home margin in the same direction the
   * real result did - i.e. whether we'd have picked the side of the spread that actually covered.
   * Same point-in-time correctness as {@link #runBacktest} ({@code TeamStrengthRating.ratingBefore}
   * already is each team's rating entering that exact game), and the same push-exclusion
   * convention {@code PredictionBacktestService}'s market-line backtest uses (an exact tie against
   * the line doesn't count either way).
   *
   * <p><b>Sign convention, confirmed empirically (2026-08-20), not assumed</b>: nflverse's {@code
   * spread_line} is already the home team's own implied margin directly - positive means home
   * favored, not the traditional bookmaker "favorite gets a minus sign" notation. A first version
   * of this method assumed the opposite (negating it) and produced an implausible ~76% hit rate;
   * verified independently in R that {@code spread_line} (unmodified) correlates +0.43 with real
   * home margin while the negated version correlates -0.43, settling it empirically rather than by
   * documentation alone. The same wrong assumption was found baked into {@code
   * PlayerGameStat.teamImpliedSpread}'s derivation in {@code _common.R} - fixed there too, see
   * WORKPLAN.md.
   */
  public SpreadBacktestSummary runSpreadBacktest() {
    List<TeamStrengthRating> allRatings = teamStrengthRatingRepository.findAll();

    Map<String, TeamStrengthRating> byDateAndTeam = new HashMap<>();
    Map<Long, List<TeamStrengthRating>> gamesByTeamId = new HashMap<>();
    for (TeamStrengthRating rating : allRatings) {
      byDateAndTeam.put(rating.getGameDate() + "|" + rating.getTeam().getAbbreviation(), rating);
      gamesByTeamId.computeIfAbsent(rating.getTeam().getId(), key -> new ArrayList<>()).add(rating);
    }

    // Keyed the same way runTotalsBacktest's totalLineByDateAndHomeTeam is, just for spread_line.
    Map<String, Double> spreadLineByDateAndHomeTeam = new HashMap<>();
    for (NflSchedule schedule : nflScheduleRepository.findAll()) {
      if (schedule.getSpreadLine() != null) {
        spreadLineByDateAndHomeTeam.put(
            schedule.getGameday() + "|" + NflverseTeamAbbreviations.toEspnAbbreviation(schedule.getHomeTeam()),
            schedule.getSpreadLine());
      }
    }

    int games = 0;
    int pushes = 0;
    int correctSide = 0;

    for (TeamStrengthRating homeRow : allRatings) {
      if (!"home".equals(homeRow.getHomeAway())) {
        continue;
      }

      String awayAbbreviation = NflverseTeamAbbreviations.toEspnAbbreviation(homeRow.getOpponentTeamId());
      TeamStrengthRating awayRow = byDateAndTeam.get(homeRow.getGameDate() + "|" + awayAbbreviation);
      if (awayRow == null || homeRow.getRatingBefore() == null || awayRow.getRatingBefore() == null) {
        continue;
      }

      Double spreadLine =
          spreadLineByDateAndHomeTeam.get(homeRow.getGameDate() + "|" + homeRow.getTeam().getAbbreviation());
      if (spreadLine == null) {
        continue;
      }

      double vegasImpliedHomeMargin = spreadLine;
      Double homeRecentScored =
          recentAverage(gamesByTeamId.get(homeRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsScored);
      Double homeRecentAllowed =
          recentAverage(gamesByTeamId.get(homeRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsAllowed);
      Double awayRecentScored =
          recentAverage(gamesByTeamId.get(awayRow.getTeam().getId()), homeRow.getGameDate(), TeamStrengthRating::getPointsScored);
      TeamMatchupPredictionService.MatchupPrediction prediction =
          predictionService.predict(
              homeRow.getRatingBefore(), awayRow.getRatingBefore(), homeRecentScored, homeRecentAllowed, awayRecentScored);
      int actualMargin = homeRow.getPointsScored() - homeRow.getPointsAllowed();

      games++;
      if (actualMargin == vegasImpliedHomeMargin) {
        pushes++;
        continue;
      }

      boolean actualCoveredHome = actualMargin > vegasImpliedHomeMargin;
      boolean predictedCoverHome = prediction.predictedMargin() > vegasImpliedHomeMargin;
      if (actualCoveredHome == predictedCoverHome) {
        correctSide++;
      }
    }

    int decided = games - pushes;
    double hitRate = decided == 0 ? 0.0d : (double) correctSide / decided;
    return new SpreadBacktestSummary(games, pushes, correctSide, hitRate);
  }

  /**
   * Raw export (not pre-scaled by any coefficient) for Priority 5's style-vs-style matchup work -
   * see WORKPLAN.md and {@code TeamOffenseGameStat}'s doc. For every backtestable game: the actual
   * margin, the already-validated Elo-predicted margin, and each side's own trailing (last {@link
   * UpcomingTeamMatchupService#RECENT_GAMES_FOR_SCORING} games, strictly before this game's date -
   * same point-in-time rule as every other method in this class) offense/defense style averages -
   * home offense vs. away defense, and the mirror set for away offense vs. home defense. A real R
   * regression against this - {@code actual_margin ~ elo_predicted_margin + <interaction terms>},
   * controlling for the Elo prediction so a significant coefficient means something genuinely
   * incremental - decides what (if anything) gets wired into {@link TeamMatchupPredictionService}.
   * A team-game with no stored offense/defense row yet for the relevant side is skipped for that
   * game's row shape overall (rather than exporting partial nulls) - keeps every exported row
   * usable in R without per-column null-handling.
   *
   * <p>{@code eloPredictedMargin} here is deliberately the pure-Elo baseline (2-arg {@code
   * predict()}, not the real-calibrated offense/defense version other methods in this class now
   * use as of 2026-08-28) - this export's whole point is testing whether style interactions add
   * anything *beyond* Elo, and using an already-enriched baseline would muddy that comparison.
   * This export wasn't rerun after the offense/defense recalibration landed (Priority 5's
   * style-matchup investigation is paused - see WORKPLAN.md), so its own numbers still reflect the
   * pre-recalibration baseline; revisit together if style-matchup work resumes.
   */
  public record StyleCalibrationRow(
      double actualMargin,
      double eloPredictedMargin,
      double homePassRate,
      double homeShotgunRate,
      double awayPassRate,
      double awayShotgunRate,
      double homeZoneCoverageRate,
      double homeAvgPassRushers,
      double homeAvgDefendersInBox,
      double homePressures,
      double awayZoneCoverageRate,
      double awayAvgPassRushers,
      double awayAvgDefendersInBox,
      double awayPressures) {}

  public List<StyleCalibrationRow> runStyleCalibrationExport() {
    List<TeamStrengthRating> allRatings = teamStrengthRatingRepository.findAll();
    Map<String, TeamStrengthRating> byDateAndTeam = new HashMap<>();
    for (TeamStrengthRating rating : allRatings) {
      byDateAndTeam.put(rating.getGameDate() + "|" + rating.getTeam().getAbbreviation(), rating);
    }

    Map<Long, List<TeamOffenseGameStat>> offenseByTeamId =
        groupByTeamId(teamOffenseGameStatRepository.findAll(), stat -> stat.getTeam().getId());
    Map<Long, List<TeamDefenseGameStat>> defenseByTeamId =
        groupByTeamId(teamDefenseGameStatRepository.findAll(), stat -> stat.getTeam().getId());

    List<StyleCalibrationRow> rows = new ArrayList<>();
    for (TeamStrengthRating homeRow : allRatings) {
      if (!"home".equals(homeRow.getHomeAway())) {
        continue;
      }

      String awayAbbreviation = NflverseTeamAbbreviations.toEspnAbbreviation(homeRow.getOpponentTeamId());
      TeamStrengthRating awayRow = byDateAndTeam.get(homeRow.getGameDate() + "|" + awayAbbreviation);
      if (awayRow == null || homeRow.getRatingBefore() == null || awayRow.getRatingBefore() == null) {
        continue;
      }

      OffenseStyle homeOffense =
          recentOffenseAverages(offenseByTeamId.get(homeRow.getTeam().getId()), homeRow.getGameDate());
      OffenseStyle awayOffense =
          recentOffenseAverages(offenseByTeamId.get(awayRow.getTeam().getId()), homeRow.getGameDate());
      DefenseStyle homeDefense =
          recentDefenseAverages(defenseByTeamId.get(homeRow.getTeam().getId()), homeRow.getGameDate());
      DefenseStyle awayDefense =
          recentDefenseAverages(defenseByTeamId.get(awayRow.getTeam().getId()), homeRow.getGameDate());
      if (homeOffense == null || awayOffense == null || homeDefense == null || awayDefense == null) {
        continue;
      }

      TeamMatchupPredictionService.MatchupPrediction prediction =
          predictionService.predict(homeRow.getRatingBefore(), awayRow.getRatingBefore());
      int actualMargin = homeRow.getPointsScored() - homeRow.getPointsAllowed();

      rows.add(
          new StyleCalibrationRow(
              actualMargin,
              prediction.predictedMargin(),
              homeOffense.passRate(),
              homeOffense.shotgunRate(),
              awayOffense.passRate(),
              awayOffense.shotgunRate(),
              homeDefense.zoneCoverageRate(),
              homeDefense.avgPassRushers(),
              homeDefense.avgDefendersInBox(),
              homeDefense.pressures(),
              awayDefense.zoneCoverageRate(),
              awayDefense.avgPassRushers(),
              awayDefense.avgDefendersInBox(),
              awayDefense.pressures()));
    }
    return rows;
  }

  private record OffenseStyle(double passRate, double shotgunRate) {}

  private record DefenseStyle(
      double zoneCoverageRate, double avgPassRushers, double avgDefendersInBox, double pressures) {}

  private <T> Map<Long, List<T>> groupByTeamId(List<T> all, Function<T, Long> teamIdAccessor) {
    Map<Long, List<T>> byTeamId = new HashMap<>();
    for (T item : all) {
      byTeamId.computeIfAbsent(teamIdAccessor.apply(item), key -> new ArrayList<>()).add(item);
    }
    return byTeamId;
  }

  /** Null if this team has no offense rows strictly before {@code beforeDate} yet - excluded from the export rather than defaulted to 0. */
  private OffenseStyle recentOffenseAverages(List<TeamOffenseGameStat> teamGames, LocalDate beforeDate) {
    if (teamGames == null) {
      return null;
    }
    List<TeamOffenseGameStat> recent =
        teamGames.stream()
            .filter(game -> game.getGameDate() != null && game.getGameDate().isBefore(beforeDate))
            .sorted(Comparator.comparing(TeamOffenseGameStat::getGameDate).reversed())
            .limit(UpcomingTeamMatchupService.RECENT_GAMES_FOR_SCORING)
            .toList();
    if (recent.isEmpty()) {
      return null;
    }
    Double passRate = averageOrNull(recent, TeamOffenseGameStat::getPassRate);
    Double shotgunRate = averageOrNull(recent, TeamOffenseGameStat::getShotgunRate);
    if (passRate == null || shotgunRate == null) {
      return null;
    }
    return new OffenseStyle(passRate, shotgunRate);
  }

  /** Same point-in-time rule as {@link #recentOffenseAverages} - null if any of the four signals has no real trailing data yet. */
  private DefenseStyle recentDefenseAverages(List<TeamDefenseGameStat> teamGames, LocalDate beforeDate) {
    if (teamGames == null) {
      return null;
    }
    List<TeamDefenseGameStat> recent =
        teamGames.stream()
            .filter(game -> game.getGameDate() != null && game.getGameDate().isBefore(beforeDate))
            .sorted(Comparator.comparing(TeamDefenseGameStat::getGameDate).reversed())
            .limit(UpcomingTeamMatchupService.RECENT_GAMES_FOR_SCORING)
            .toList();
    if (recent.isEmpty()) {
      return null;
    }
    Double zoneCoverageRate = averageOrNull(recent, TeamDefenseGameStat::getZoneCoverageRate);
    Double avgPassRushers = averageOrNull(recent, TeamDefenseGameStat::getAvgPassRushers);
    Double avgDefendersInBox = averageOrNull(recent, TeamDefenseGameStat::getAvgDefendersInBox);
    Double pressures = averageOrNullInt(recent, TeamDefenseGameStat::getPressures);
    if (zoneCoverageRate == null || avgPassRushers == null || avgDefendersInBox == null || pressures == null) {
      return null;
    }
    return new DefenseStyle(zoneCoverageRate, avgPassRushers, avgDefendersInBox, pressures);
  }

  private <T> Double averageOrNull(List<T> games, Function<T, Double> accessor) {
    List<Double> values = games.stream().map(accessor).filter(java.util.Objects::nonNull).toList();
    return values.isEmpty() ? null : values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0d);
  }

  private <T> Double averageOrNullInt(List<T> games, Function<T, Integer> accessor) {
    List<Integer> values = games.stream().map(accessor).filter(java.util.Objects::nonNull).toList();
    return values.isEmpty() ? null : values.stream().mapToDouble(Integer::doubleValue).average().orElse(0.0d);
  }

  /** Only counts games strictly before {@code beforeDate} - the same point-in-time rule the whole session's other backtests use. */
  private Double recentAverage(
      List<TeamStrengthRating> teamGames, LocalDate beforeDate, ToIntFunction<TeamStrengthRating> accessor) {
    if (teamGames == null) {
      return null;
    }

    List<TeamStrengthRating> recent =
        teamGames.stream()
            .filter(game -> game.getGameDate().isBefore(beforeDate))
            .sorted(Comparator.comparing(TeamStrengthRating::getGameDate).reversed())
            .limit(UpcomingTeamMatchupService.RECENT_GAMES_FOR_SCORING)
            .toList();

    return recent.isEmpty() ? null : recent.stream().mapToInt(accessor).average().orElse(Double.NaN);
  }
}

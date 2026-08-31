package com.agp.bets.goforbroke.team.service;

import com.agp.bets.goforbroke.team.domain.NflSchedule;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamStrengthRating;
import com.agp.bets.goforbroke.team.repository.NflScheduleRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import com.agp.bets.goforbroke.team.repository.TeamStrengthRatingRepository;
import com.agp.bets.goforbroke.team.web.dto.UpcomingMatchupResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live-facing twin of {@link TeamMatchupBacktestService}: predicts every not-yet-played regular
 * season/playoff game on file, instead of scoring already-completed ones. Explicitly excludes
 * preseason - not by filtering it out, but because nflverse's own schedules dataset never included
 * it in the first place (confirmed directly: no "PRE" {@code game_type} value exists in stored
 * data), so restricting to {@code NON_PRESEASON_GAME_TYPES} is really just "the real game types,"
 * a defensive allowlist rather than a preseason-specific exclusion.
 *
 * <p>A team's rating for its next game is its {@code ratingAfter} from its most recent completed
 * game (there's no {@code ratingBefore} row for a game that hasn't been played yet to read from -
 * {@code ratingAfter} from the last real result is exactly what {@code ratingBefore} would become
 * once this next game is played).
 */
@Service
@Transactional(readOnly = true)
public class UpcomingTeamMatchupService {

  private static final List<String> NON_PRESEASON_GAME_TYPES = List.of("REG", "WC", "DIV", "CON", "SB");

  // The public /matchups page showing all 18 weeks at once was too dense (2026-08-20 user
  // feedback) - scope the public method to a short window; allUpcomingMatchups() keeps the full
  // season for admin/internal use. Counts distinct (season, gameType, week) groups, not games, so
  // a week's slate is never split across the boundary.
  private static final int PUBLIC_MAX_DISTINCT_WEEKS = 2;

  // How many of a team's most recent games to average style features (pass rate, shotgun rate,
  // zone coverage rate, etc.) over in TeamMatchupBacktestService's style-calibration export. Not
  // the same question as the two scoring windows below - style-vs-style matchup awareness was
  // investigated and found to be a real, converging null (see WORKPLAN.md, Priority 5), so this
  // window was never itself retested and stays at its original value.
  static final int RECENT_GAMES_FOR_SCORING = 8;

  // Real-calibrated (2026-08-31), genuine held-out train/test split (same 6-cutoff discipline,
  // 2018-2023, as every other recalibration this session) - the first time either scoring window
  // was retested against real alternatives rather than the 2026-08-20 in-sample-only comparison
  // (against 4 and 1) that originally picked 8 for both purposes. Directly computed from raw
  // nflverse scores in R (not through the Java calibration export - these windows aren't one of
  // its fields), holding the already-fit OFFENSE_DEFENSE_*/TOTAL_POINTS_* regression coefficients
  // fixed and varying only the window feeding their four raw inputs (home/away recent scored/
  // allowed). The margin/win-pick side and the totals side wanted genuinely different windows -
  // window 6 beat the old 8 on real held-out winner-pick accuracy on every single one of the 6
  // cutoffs (mean 64.49% -> 65.21%), while margin MAE itself had no single clean winner across
  // windows (mixed by cutoff) - kept at 6 since winner-pick accuracy is the metric this whole
  // priority is scored on (63.9% baseline, see Priority 5). See RECENT_GAMES_FOR_TOTALS_SCORING
  // for the separate, totals-specific finding.
  static final int RECENT_GAMES_FOR_MARGIN_SCORING = 6;

  // Real-calibrated (2026-08-31) - see RECENT_GAMES_FOR_MARGIN_SCORING's doc for the shared
  // methodology. Window 10 beat the old 8 on real held-out totals MAE on every single one of the 6
  // cutoffs (mean 10.521 -> 10.494) - a smaller, consistent win, not a range-compression fix (see
  // TOTAL_POINTS_INTERCEPT's own doc in TeamMatchupPredictionService for that separate, still-open
  // problem).
  static final int RECENT_GAMES_FOR_TOTALS_SCORING = 10;

  private final NflScheduleRepository nflScheduleRepository;
  private final TeamRepository teamRepository;
  private final TeamStrengthRatingRepository teamStrengthRatingRepository;
  private final TeamMatchupPredictionService predictionService;

  public UpcomingTeamMatchupService(
      NflScheduleRepository nflScheduleRepository,
      TeamRepository teamRepository,
      TeamStrengthRatingRepository teamStrengthRatingRepository,
      TeamMatchupPredictionService predictionService) {
    this.nflScheduleRepository = nflScheduleRepository;
    this.teamRepository = teamRepository;
    this.teamStrengthRatingRepository = teamStrengthRatingRepository;
    this.predictionService = predictionService;
  }

  public List<UpcomingMatchupResponse> upcomingMatchups() {
    return buildUpcomingMatchups(PUBLIC_MAX_DISTINCT_WEEKS);
  }

  public List<UpcomingMatchupResponse> allUpcomingMatchups() {
    return buildUpcomingMatchups(Integer.MAX_VALUE);
  }

  private List<UpcomingMatchupResponse> buildUpcomingMatchups(int maxDistinctWeekGroups) {
    List<NflSchedule> upcomingGames =
        nflScheduleRepository.findAllByHomeScoreIsNullAndGameTypeInOrderByGamedayAsc(NON_PRESEASON_GAME_TYPES);
    upcomingGames = scopeToWeekWindow(upcomingGames, maxDistinctWeekGroups);

    Map<String, Team> teamsByAbbreviation =
        teamRepository.findAllByOrderByDisplayNameAsc().stream()
            .collect(Collectors.toMap(Team::getAbbreviation, Function.identity()));

    // Preloaded once and filtered/sorted in memory per game below, instead of one query per team
    // per game - confirmed directly earlier this session (PredictionBacktestService,
    // TeamMatchupBacktestService) that the naive per-lookup version of this exact pattern is a
    // real, measurable bottleneck, not a hypothetical one.
    Map<Long, List<TeamStrengthRating>> ratingsByTeamId =
        teamStrengthRatingRepository.findAll().stream()
            .collect(Collectors.groupingBy(rating -> rating.getTeam().getId()));
    for (List<TeamStrengthRating> ratings : ratingsByTeamId.values()) {
      ratings.sort(Comparator.comparing(TeamStrengthRating::getGameDate).reversed());
    }

    List<UpcomingMatchupResponse> results = new ArrayList<>();
    for (NflSchedule game : upcomingGames) {
      Team homeTeam = teamsByAbbreviation.get(NflverseTeamAbbreviations.toEspnAbbreviation(game.getHomeTeam()));
      Team awayTeam = teamsByAbbreviation.get(NflverseTeamAbbreviations.toEspnAbbreviation(game.getAwayTeam()));
      if (homeTeam == null || awayTeam == null) {
        continue;
      }

      List<TeamStrengthRating> homeHistory = ratingsByTeamId.get(homeTeam.getId());
      List<TeamStrengthRating> awayHistory = ratingsByTeamId.get(awayTeam.getId());
      if (homeHistory == null || homeHistory.isEmpty() || awayHistory == null || awayHistory.isEmpty()) {
        continue;
      }

      double homeRating = homeHistory.get(0).getRatingAfter();
      double awayRating = awayHistory.get(0).getRatingAfter();
      // 2026-08-31: margin/win-pick and totals now use separately-calibrated windows (see
      // RECENT_GAMES_FOR_MARGIN_SCORING's doc) - real held-out validation found they genuinely
      // want different values, so each is computed on its own rather than sharing one trailing
      // average the way both used to.
      double homeRecentScoredForMargin = recentAverage(homeHistory, TeamStrengthRating::getPointsScored, RECENT_GAMES_FOR_MARGIN_SCORING);
      double homeRecentAllowedForMargin = recentAverage(homeHistory, TeamStrengthRating::getPointsAllowed, RECENT_GAMES_FOR_MARGIN_SCORING);
      double awayRecentScoredForMargin = recentAverage(awayHistory, TeamStrengthRating::getPointsScored, RECENT_GAMES_FOR_MARGIN_SCORING);

      TeamMatchupPredictionService.MatchupPrediction matchup =
          predictionService.predict(
              homeRating, awayRating, homeRecentScoredForMargin, homeRecentAllowedForMargin, awayRecentScoredForMargin);

      // Prefer the real posted Vegas total when one exists - a real 2026-08-20 backtest finding
      // (see WORKPLAN.md) showed it explains meaningfully more of the real variance in game totals
      // (~9%) than our own recent-scoring-based estimate (~4%), so it's a genuinely more accurate
      // number, not just a convenient one. Falls back to the computed estimate for games far
      // enough out that a line hasn't been posted yet.
      double expectedTotal;
      if (game.getTotalLine() != null) {
        expectedTotal = game.getTotalLine();
      } else {
        double homeRecentScoredForTotals = recentAverage(homeHistory, TeamStrengthRating::getPointsScored, RECENT_GAMES_FOR_TOTALS_SCORING);
        double homeRecentAllowedForTotals = recentAverage(homeHistory, TeamStrengthRating::getPointsAllowed, RECENT_GAMES_FOR_TOTALS_SCORING);
        double awayRecentScoredForTotals = recentAverage(awayHistory, TeamStrengthRating::getPointsScored, RECENT_GAMES_FOR_TOTALS_SCORING);
        double awayRecentAllowedForTotals = recentAverage(awayHistory, TeamStrengthRating::getPointsAllowed, RECENT_GAMES_FOR_TOTALS_SCORING);
        expectedTotal =
            predictionService.expectedTotalPoints(
                homeRecentScoredForTotals, homeRecentAllowedForTotals, awayRecentScoredForTotals, awayRecentAllowedForTotals);
      }
      TeamMatchupPredictionService.ScorePrediction score =
          predictionService.predictScore(matchup.predictedMargin(), expectedTotal);

      String predictedWinner =
          score.predictedTie()
              ? null
              : score.predictedHomeScore() > score.predictedAwayScore()
                  ? homeTeam.getAbbreviation()
                  : awayTeam.getAbbreviation();

      results.add(
          new UpcomingMatchupResponse(
              game.getGameId(),
              game.getSeason(),
              game.getGameType(),
              game.getWeek(),
              game.getGameday(),
              homeTeam.getAbbreviation(),
              homeTeam.getDisplayName(),
              awayTeam.getAbbreviation(),
              awayTeam.getDisplayName(),
              matchup.predictedMargin(),
              matchup.homeWinProbability(),
              score.predictedHomeScore(),
              score.predictedAwayScore(),
              score.predictedTie(),
              predictedWinner));
    }
    return results;
  }

  private List<NflSchedule> scopeToWeekWindow(List<NflSchedule> upcomingGames, int maxDistinctWeekGroups) {
    if (maxDistinctWeekGroups >= Integer.MAX_VALUE) {
      return upcomingGames;
    }

    LinkedHashSet<String> groupsSeen = new LinkedHashSet<>();
    List<NflSchedule> scoped = new ArrayList<>();
    for (NflSchedule game : upcomingGames) {
      String groupKey = game.getSeason() + "|" + game.getGameType() + "|" + game.getWeek();
      if (!groupsSeen.contains(groupKey) && groupsSeen.size() >= maxDistinctWeekGroups) {
        break;
      }
      groupsSeen.add(groupKey);
      scoped.add(game);
    }
    return scoped;
  }

  private double recentAverage(List<TeamStrengthRating> historyDesc, ToIntFunction<TeamStrengthRating> accessor, int window) {
    List<TeamStrengthRating> recent = historyDesc.stream().limit(window).toList();
    return recent.isEmpty()
        ? TeamMatchupPredictionService.DEFAULT_GAME_TOTAL_POINTS / 2.0d
        : recent.stream().mapToInt(accessor).average().orElse(TeamMatchupPredictionService.DEFAULT_GAME_TOTAL_POINTS / 2.0d);
  }
}

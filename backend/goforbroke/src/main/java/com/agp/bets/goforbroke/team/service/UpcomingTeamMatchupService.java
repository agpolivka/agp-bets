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

  // How many of a team's most recent games to average points scored/allowed over when estimating
  // a real per-game total. Actually compared against 4 and 1 via TeamMatchupBacktestService
  // .runTotalsBacktest() (2026-08-20), unlike most constants in this codebase - 8 has the best MAE
  // (10.93 vs 11.23 at 4, 12.78 at 1). Shorter windows widen the predicted range some (span 36 ->
  // 68 at window=1) but never get close to the real range (actual totals span 3-105 in the same
  // data) while making the central prediction meaningfully worse - so this isn't "the best window
  // we found," it's "window length has a real ceiling on fixing the range problem at all, and 8 is
  // the best accuracy among the options tested." A structurally different approach (not just a
  // shorter window) is what would actually close the range gap - see WORKPLAN.md.
  static final int RECENT_GAMES_FOR_SCORING = 8;

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
      TeamMatchupPredictionService.MatchupPrediction matchup = predictionService.predict(homeRating, awayRating);

      // Prefer the real posted Vegas total when one exists - a real 2026-08-20 backtest finding
      // (see WORKPLAN.md) showed it explains meaningfully more of the real variance in game totals
      // (~9%) than our own recent-scoring-based estimate (~4%), so it's a genuinely more accurate
      // number, not just a convenient one. Falls back to the computed estimate for games far
      // enough out that a line hasn't been posted yet. The margin/winner prediction itself is
      // unaffected either way - that stays our own validated Elo signal, only the total changes.
      double expectedTotal =
          game.getTotalLine() != null
              ? game.getTotalLine()
              : predictionService.expectedTotalPoints(
                  recentAverage(homeHistory, TeamStrengthRating::getPointsScored),
                  recentAverage(homeHistory, TeamStrengthRating::getPointsAllowed),
                  recentAverage(awayHistory, TeamStrengthRating::getPointsScored),
                  recentAverage(awayHistory, TeamStrengthRating::getPointsAllowed));
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

  private double recentAverage(List<TeamStrengthRating> historyDesc, ToIntFunction<TeamStrengthRating> accessor) {
    List<TeamStrengthRating> recent = historyDesc.stream().limit(RECENT_GAMES_FOR_SCORING).toList();
    return recent.isEmpty()
        ? TeamMatchupPredictionService.DEFAULT_GAME_TOTAL_POINTS / 2.0d
        : recent.stream().mapToInt(accessor).average().orElse(TeamMatchupPredictionService.DEFAULT_GAME_TOTAL_POINTS / 2.0d);
  }
}

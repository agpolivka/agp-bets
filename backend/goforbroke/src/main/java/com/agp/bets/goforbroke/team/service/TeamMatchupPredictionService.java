package com.agp.bets.goforbroke.team.service;

import org.springframework.stereotype.Service;

/**
 * Converts two teams' Elo ratings (see {@code etl/r/compute_team_strength_ratings.R}) into a
 * matchup prediction - point margin and home win probability. This is the live/backtest-shared
 * math (analogous to {@code PlayerPredictionService.buildProjection} for the player-prop
 * heuristic), kept deliberately tiny: a rating differential, a home-field bump, and two standard,
 * well-established Elo-to-outcome formulas, not a trained model.
 *
 * <p><b>Every constant here is hand-picked, not backtest-derived</b> - same honesty convention as
 * the player-prop heuristic. {@code HOME_FIELD_ADVANTAGE_ELO} must match the constant of the same
 * name in {@code compute_team_strength_ratings.R} (both sides need the same assumption about how
 * much home field is worth for the rating math to stay internally consistent between the R-side
 * rating computation and this Java-side conversion to a margin/probability).
 */
@Service
public class TeamMatchupPredictionService {

  // Standard NFL Elo home-field bump (roughly what public models like FiveThirtyEight's have used)
  // - must match compute_team_strength_ratings.R's own HOME_FIELD_ADVANTAGE_ELO constant.
  static final double HOME_FIELD_ADVANTAGE_ELO = 55.0d;

  // Rough, widely-used approximation linking a chess-style Elo scale to an expected point margin -
  // about 25 Elo points per point of expected scoring margin. Hand-picked/well-established, not
  // fit against this project's own data yet.
  static final double ELO_POINTS_PER_MARGIN_POINT = 25.0d;

  // Fallback only - used when a team has zero rating history to compute a real recent scoring
  // average from (shouldn't happen in practice, since a team needs at least one rating row to
  // reach this code at all). NOT the primary source of the predicted total anymore (see
  // expectedTotalPoints) - previously every game's score summed to ~44-45 points because this was
  // used unconditionally, which doesn't reflect real per-game scoring variance.
  static final double DEFAULT_GAME_TOTAL_POINTS = 44.5d;

  public record MatchupPrediction(double predictedMargin, double homeWinProbability) {}

  public record ScorePrediction(long predictedHomeScore, long predictedAwayScore, boolean predictedTie) {}

  /**
   * {@code homeRating}/{@code awayRating} should be each team's rating entering the game (Elo
   * "before" value - see {@code TeamStrengthRating.ratingBefore}), not their rating after any game
   * being predicted already happened.
   */
  public MatchupPrediction predict(double homeRating, double awayRating) {
    double ratingDiff = homeRating - awayRating + HOME_FIELD_ADVANTAGE_ELO;
    double predictedMargin = ratingDiff / ELO_POINTS_PER_MARGIN_POINT;
    double homeWinProbability = 1.0d / (1.0d + Math.pow(10.0d, -ratingDiff / 400.0d));
    return new MatchupPrediction(predictedMargin, homeWinProbability);
  }

  /**
   * A real, matchup-specific expected combined score - each team's own recent scoring average
   * (offense) blended against the opponent's own recent points-allowed average (defense), the same
   * "compare a team's own output to what this opponent typically allows" shape as
   * PlayerPredictionService.opponentAdjustment, not a single fixed number for every game.
   */
  public double expectedTotalPoints(
      double homeRecentPointsScored,
      double homeRecentPointsAllowed,
      double awayRecentPointsScored,
      double awayRecentPointsAllowed) {
    double expectedHomeScore = (homeRecentPointsScored + awayRecentPointsAllowed) / 2.0d;
    double expectedAwayScore = (awayRecentPointsScored + homeRecentPointsAllowed) / 2.0d;
    return expectedHomeScore + expectedAwayScore;
  }

  /**
   * Splits a predicted margin and an expected total into two whole-number team scores, and - this
   * is the point of returning whole numbers here rather than leaving rounding to the caller/UI -
   * decides "predicted tie" from the exact same rounded numbers a page would display, so the pick
   * and the displayed score can never disagree the way they used to when the winner was chosen
   * from the raw, unrounded margin's sign instead.
   */
  public ScorePrediction predictScore(double predictedMargin, double expectedTotalPoints) {
    long predictedHomeScore = Math.round((expectedTotalPoints + predictedMargin) / 2.0d);
    long predictedAwayScore = Math.round((expectedTotalPoints - predictedMargin) / 2.0d);
    return new ScorePrediction(predictedHomeScore, predictedAwayScore, predictedHomeScore == predictedAwayScore);
  }
}

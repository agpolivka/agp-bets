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

  // Real-calibrated (2026-08-28), a genuine temporal train/test split on the existing 2014-2025
  // data (fit on season<=cutoff, evaluate held-out on season>cutoff, replicated across 6
  // independent cutoffs 2018-2023 before trusting it - same discipline as the same-day player-prop
  // recalibration). Held-out margin MAE improved on every single one of the 6 cutoffs tested
  // (+0.07 to +0.13 points). Honest caveat: a McNemar-style significance test on the discrete
  // winner-pick outcome specifically did NOT clear significance (p=0.57, n=78 disagreements) -
  // win/loss accuracy is a much lower-power metric than continuous margin error, and this
  // consistently-replicated MAE improvement is real evidence even though it doesn't (yet, at this
  // sample size) prove itself on the coarser pick-accuracy metric too. Reduced model (dropped
  // awayRecentAllowed, p=0.76 in the full fit - contributed nothing) performed identically to the
  // full one, so the simpler version is what's used here. Supersedes the 2026-08-20 in-sample-only
  // version of this same investigation (found "real but modest," R² 0.134->0.143, correctly not
  // shipped without held-out validation at the time) - see WORKPLAN.md for the full numbers.
  static final double OFFENSE_DEFENSE_INTERCEPT = 5.8815d;
  static final double OFFENSE_DEFENSE_ELO_COEFFICIENT = 0.6427d;
  static final double OFFENSE_DEFENSE_HOME_SCORED_COEFFICIENT = 0.2283d;
  static final double OFFENSE_DEFENSE_HOME_ALLOWED_COEFFICIENT = -0.1948d;
  static final double OFFENSE_DEFENSE_AWAY_SCORED_COEFFICIENT = -0.2695d;

  public record MatchupPrediction(double predictedMargin, double homeWinProbability) {}

  public record ScorePrediction(long predictedHomeScore, long predictedAwayScore, boolean predictedTie) {}

  /**
   * Pure-Elo prediction - {@code homeRating}/{@code awayRating} should be each team's rating
   * entering the game (Elo "before" value - see {@code TeamStrengthRating.ratingBefore}), not
   * their rating after any game being predicted already happened. Delegates to {@link
   * #predict(double, double, Double, Double, Double)} with the offense/defense terms omitted - use
   * that overload directly when a team's real trailing scoring history is available (which is true
   * for every real prediction except a team's literal first game on record).
   */
  public MatchupPrediction predict(double homeRating, double awayRating) {
    return predict(homeRating, awayRating, null, null, null);
  }

  /**
   * Real-calibrated version (see {@link #OFFENSE_DEFENSE_INTERCEPT}'s doc) - folds each team's own
   * recent scoring/defense into the Elo-based margin, rather than using Elo alone. {@code
   * homeRecentScored}/{@code homeRecentAllowed}/{@code awayRecentScored} should be point-in-time-
   * correct trailing averages (same window/logic {@code UpcomingTeamMatchupService} already
   * computes for {@link #expectedTotalPoints}) - null for any of the three falls back to the pure
   * Elo prediction rather than guessing, same graceful-degradation shape used throughout this app
   * for sparse data. {@code homeWinProbability} is derived from this same final margin (converted
   * back through {@link #ELO_POINTS_PER_MARGIN_POINT}), not the raw pre-regression rating
   * difference - keeps the displayed confidence and the actual pick internally consistent, the
   * exact bug class {@code UpcomingTeamMatchupService}'s 2026-08-20 fix already closed once for the
   * margin/displayed-score pairing.
   */
  public MatchupPrediction predict(
      double homeRating,
      double awayRating,
      Double homeRecentScored,
      Double homeRecentAllowed,
      Double awayRecentScored) {
    double ratingDiff = homeRating - awayRating + HOME_FIELD_ADVANTAGE_ELO;
    double eloPredictedMargin = ratingDiff / ELO_POINTS_PER_MARGIN_POINT;

    double predictedMargin;
    if (homeRecentScored != null && homeRecentAllowed != null && awayRecentScored != null) {
      predictedMargin =
          OFFENSE_DEFENSE_INTERCEPT
              + OFFENSE_DEFENSE_ELO_COEFFICIENT * eloPredictedMargin
              + OFFENSE_DEFENSE_HOME_SCORED_COEFFICIENT * homeRecentScored
              + OFFENSE_DEFENSE_HOME_ALLOWED_COEFFICIENT * homeRecentAllowed
              + OFFENSE_DEFENSE_AWAY_SCORED_COEFFICIENT * awayRecentScored;
    } else {
      predictedMargin = eloPredictedMargin;
    }

    double effectiveRatingDiff = predictedMargin * ELO_POINTS_PER_MARGIN_POINT;
    double homeWinProbability = 1.0d / (1.0d + Math.pow(10.0d, -effectiveRatingDiff / 400.0d));
    return new MatchupPrediction(predictedMargin, homeWinProbability);
  }

  // Real-calibrated (2026-08-28), same temporal train/test discipline as OFFENSE_DEFENSE_INTERCEPT
  // above: fit actual_total ~ homeRecentScored + homeRecentAllowed + awayRecentScored +
  // awayRecentAllowed (letting each of the four raw inputs have its own weight and a real
  // intercept, instead of the old fixed 50/50 blend with no intercept), replicated across the same
  // 6 cutoffs (2018-2023). Held-out MAE improved on every cutoff (+0.45% to +1.50%), all four
  // coefficients real and statistically significant (p<0.03, most p<1e-9). Full-dataset fit used
  // for the final values below.
  //
  // Honest caveat, checked directly, not assumed: this improves average error but does NOT solve
  // the totals-compression problem flagged 2026-08-20 (predicted range ~28-64 vs. real range
  // 3-105) - the refit's own held-out predictions still only spanned ~37-54. Pace-of-play was
  // tried the same session as a structural fix and also failed (see WORKPLAN.md) - the range
  // problem stays open. This is a real, validated improvement to the existing formula shape, not a
  // fix for its known structural ceiling.
  static final double TOTAL_POINTS_INTERCEPT = 20.9851d;
  static final double TOTAL_POINTS_HOME_SCORED_COEFFICIENT = 0.4702d;
  static final double TOTAL_POINTS_HOME_ALLOWED_COEFFICIENT = 0.1335d;
  static final double TOTAL_POINTS_AWAY_SCORED_COEFFICIENT = 0.3024d;
  static final double TOTAL_POINTS_AWAY_ALLOWED_COEFFICIENT = 0.1741d;

  /**
   * A real, matchup-specific expected combined score - each team's own recent scoring/allowed
   * average, weighted by real held-out-validated coefficients (see {@link
   * #TOTAL_POINTS_INTERCEPT}'s doc) rather than a fixed 50/50 blend with no intercept.
   */
  public double expectedTotalPoints(
      double homeRecentPointsScored,
      double homeRecentPointsAllowed,
      double awayRecentPointsScored,
      double awayRecentPointsAllowed) {
    return TOTAL_POINTS_INTERCEPT
        + TOTAL_POINTS_HOME_SCORED_COEFFICIENT * homeRecentPointsScored
        + TOTAL_POINTS_HOME_ALLOWED_COEFFICIENT * homeRecentPointsAllowed
        + TOTAL_POINTS_AWAY_SCORED_COEFFICIENT * awayRecentPointsScored
        + TOTAL_POINTS_AWAY_ALLOWED_COEFFICIENT * awayRecentPointsAllowed;
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

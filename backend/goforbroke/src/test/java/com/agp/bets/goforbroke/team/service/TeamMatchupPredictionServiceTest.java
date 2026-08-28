package com.agp.bets.goforbroke.team.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TeamMatchupPredictionServiceTest {

  private final TeamMatchupPredictionService service = new TeamMatchupPredictionService();

  @Test
  void equalRatingsFavorHomeTeamByHomeFieldAdvantageOnly() {
    TeamMatchupPredictionService.MatchupPrediction prediction = service.predict(1500.0d, 1500.0d);

    // ratingDiff = 0 + 55 (home field) = 55; margin = 55 / 25 = 2.2.
    assertEquals(2.2d, prediction.predictedMargin(), 0.0001d);
    // Home team is favored (diff > 0), so win probability is above 50%.
    assertTrue(prediction.homeWinProbability() > 0.5d);
  }

  @Test
  void largeRatingGapProducesLopsidedPredictionInTheStrongerTeamsFavor() {
    // A 200-point Elo gap is a real, meaningfully-large team-strength difference.
    TeamMatchupPredictionService.MatchupPrediction homeIsStronger = service.predict(1700.0d, 1500.0d);
    TeamMatchupPredictionService.MatchupPrediction awayIsStronger = service.predict(1500.0d, 1700.0d);

    assertTrue(homeIsStronger.predictedMargin() > 0.0d);
    assertTrue(homeIsStronger.homeWinProbability() > 0.7d);
    assertTrue(awayIsStronger.predictedMargin() < 0.0d);
    // Home field advantage partially offsets the gap (ratingDiff = -145, not -200), so this lands
    // just above 0.30, not below it - asserting a looser, still-meaningful "clearly the underdog"
    // bound instead of a tight number.
    assertTrue(awayIsStronger.homeWinProbability() < 0.35d);
  }

  @Test
  void predictWithRecentScoringUsesTheRealCalibratedOffenseDefenseFormula() {
    // ratingDiff = 1600 - 1500 + 55 = 155; eloPredictedMargin = 155 / 25 = 6.2. predictedMargin =
    // 5.8815 + 0.6427*6.2 + 0.2283*24.0 - 0.1948*18.0 - 0.2695*20.0 = 6.44904 (2026-08-28
    // real-calibrated coefficients - see OFFENSE_DEFENSE_INTERCEPT's doc).
    TeamMatchupPredictionService.MatchupPrediction prediction =
        service.predict(1600.0d, 1500.0d, 24.0d, 18.0d, 20.0d);

    assertEquals(6.44904d, prediction.predictedMargin(), 0.0001d);
    // homeWinProbability is derived from this same enriched margin (converted back through
    // ELO_POINTS_PER_MARGIN_POINT), not the raw pre-regression rating difference.
    assertEquals(0.716688d, prediction.homeWinProbability(), 0.0001d);
  }

  @Test
  void predictFallsBackToPureEloWhenAnyRecentScoringValueIsMissing() {
    // Same ratings as equalRatingsFavorHomeTeamByHomeFieldAdvantageOnly - only one of the three
    // recent-scoring values is null (a team's first game on record, in practice), which should
    // fall back to the pure-Elo formula rather than guessing at the missing inputs.
    TeamMatchupPredictionService.MatchupPrediction prediction =
        service.predict(1500.0d, 1500.0d, null, 20.0d, 20.0d);

    assertEquals(2.2d, prediction.predictedMargin(), 0.0001d);
  }

  @Test
  void expectedTotalPointsBlendsEachTeamsOwnRecentOffenseAgainstTheOpponentsRecentDefense() {
    // Home averages 27 scored/17 allowed recently; away averages 20 scored/24 allowed.
    // 2026-08-28 real-calibrated formula (see TOTAL_POINTS_INTERCEPT's doc): 20.9851 + 0.4702*27 +
    // 0.1335*17 + 0.3024*20 + 0.1741*24 = 46.1764.
    double total = service.expectedTotalPoints(27.0d, 17.0d, 20.0d, 24.0d);

    assertEquals(46.1764d, total, 0.0001d);
  }

  @Test
  void predictScoreSplitsTheExpectedTotalAroundTheMarginAndRoundsToWholeNumbers() {
    TeamMatchupPredictionService.ScorePrediction score = service.predictScore(6.0d, 44.0d);

    // (44 + 6) / 2 = 25; (44 - 6) / 2 = 19.
    assertEquals(25L, score.predictedHomeScore());
    assertEquals(19L, score.predictedAwayScore());
    assertFalse(score.predictedTie());
  }

  @Test
  void predictScoreReportsATieWhenBothRoundedScoresMatch() {
    TeamMatchupPredictionService.ScorePrediction score = service.predictScore(0.0d, 44.0d);

    assertEquals(score.predictedHomeScore(), score.predictedAwayScore());
    assertTrue(score.predictedTie());
  }
}

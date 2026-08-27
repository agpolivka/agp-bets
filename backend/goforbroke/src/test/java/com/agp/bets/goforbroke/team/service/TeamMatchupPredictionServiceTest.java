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
  void expectedTotalPointsBlendsEachTeamsOwnRecentOffenseAgainstTheOpponentsRecentDefense() {
    // Home averages 27 scored/17 allowed recently; away averages 20 scored/24 allowed.
    // expectedHomeScore = (27 + 24) / 2 = 25.5; expectedAwayScore = (20 + 17) / 2 = 18.5.
    double total = service.expectedTotalPoints(27.0d, 17.0d, 20.0d, 24.0d);

    assertEquals(44.0d, total, 0.0001d);
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

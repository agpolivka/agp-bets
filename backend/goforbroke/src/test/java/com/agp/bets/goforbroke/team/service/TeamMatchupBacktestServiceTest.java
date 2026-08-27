package com.agp.bets.goforbroke.team.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.team.domain.NflSchedule;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamStrengthRating;
import com.agp.bets.goforbroke.team.repository.NflScheduleRepository;
import com.agp.bets.goforbroke.team.repository.TeamStrengthRatingRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TeamMatchupBacktestServiceTest {

  private final TeamStrengthRatingRepository repository = Mockito.mock(TeamStrengthRatingRepository.class);
  private final NflScheduleRepository nflScheduleRepository = Mockito.mock(NflScheduleRepository.class);
  private final TeamMatchupBacktestService service =
      new TeamMatchupBacktestService(repository, nflScheduleRepository, new TeamMatchupPredictionService());

  @Test
  void matchesHomeAndAwayRowsForTheSameGameThroughTheCrosswalkAndScoresThePrediction() {
    Team chiefs = new Team();
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setAbbreviation("LAR");

    // Home team (KC) enters this game a stronger team (1600 vs 1500) - should be predicted to win,
    // and the real result agrees (KC won by 10), so this should count as a correct pick.
    TeamStrengthRating homeRow = new TeamStrengthRating();
    homeRow.setTeam(chiefs);
    homeRow.setHomeAway("home");
    homeRow.setGameDate(LocalDate.parse("2025-09-07"));
    // Opponent stored as nflverse's raw code ("LA"), same convention as PlayerGameStat.opponentTeamId.
    homeRow.setOpponentTeamId("LA");
    homeRow.setRatingBefore(1600.0d);
    homeRow.setPointsScored(27);
    homeRow.setPointsAllowed(17);

    TeamStrengthRating awayRow = new TeamStrengthRating();
    awayRow.setTeam(rams);
    awayRow.setHomeAway("away");
    awayRow.setGameDate(LocalDate.parse("2025-09-07"));
    awayRow.setOpponentTeamId("KC");
    awayRow.setRatingBefore(1500.0d);
    awayRow.setPointsScored(17);
    awayRow.setPointsAllowed(27);

    when(repository.findAll()).thenReturn(List.of(homeRow, awayRow));

    TeamMatchupBacktestService.TeamMatchupBacktestSummary summary = service.runBacktest();

    assertEquals(1, summary.games());
    assertEquals(1, summary.correctWinnerPicks());
    assertEquals(1.0d, summary.winnerAccuracy(), 0.0001d);
  }

  @Test
  void totalsBacktestComparesPredictedTotalsAgainstRealOnesUsingOnlyPriorGames() {
    Team chiefs = new Team();
    chiefs.setId(1L);
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setId(2L);
    rams.setAbbreviation("LAR");

    // Each team's week-1 result feeds the recent-average used for the week-2 target game below -
    // point-in-time correctness means the target game itself must NOT contribute to its own
    // prediction.
    TeamStrengthRating chiefsWeek1 = rating(chiefs, "2025-09-07", "away", "LAR", 24, 10);
    TeamStrengthRating ramsWeek1 = rating(rams, "2025-09-07", "home", "KC", 20, 14);

    TeamStrengthRating chiefsWeek2 = rating(chiefs, "2025-09-14", "home", "LA", 30, 20);
    TeamStrengthRating ramsWeek2 = rating(rams, "2025-09-14", "away", "KC", 20, 30);

    when(repository.findAll()).thenReturn(List.of(chiefsWeek1, ramsWeek1, chiefsWeek2, ramsWeek2));
    // No real posted lines - forces the computed-estimate fallback path.
    when(nflScheduleRepository.findAll()).thenReturn(List.of());

    TeamMatchupBacktestService.TotalsBacktestSummary summary = service.runTotalsBacktest();

    assertEquals(1, summary.games());
    // expectedHomeScore = (24 scored + 14 allowed) / 2 = 19; expectedAwayScore = (20 + 10) / 2 = 15;
    // predictedTotal = 34. actualTotal = 30 + 20 = 50.
    assertEquals(34.0d, summary.meanPredictedTotal(), 0.0001d);
    assertEquals(50.0d, summary.meanActualTotal(), 0.0001d);
    assertEquals(16.0d, summary.meanAbsoluteError(), 0.0001d);
  }

  @Test
  void totalsBacktestPrefersARealPostedTotalLineOverTheComputedEstimate() {
    Team chiefs = new Team();
    chiefs.setId(1L);
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setId(2L);
    rams.setAbbreviation("LAR");

    TeamStrengthRating chiefsWeek1 = rating(chiefs, "2025-09-07", "away", "LAR", 24, 10);
    TeamStrengthRating ramsWeek1 = rating(rams, "2025-09-07", "home", "KC", 20, 14);
    TeamStrengthRating chiefsWeek2 = rating(chiefs, "2025-09-14", "home", "LA", 30, 20);
    TeamStrengthRating ramsWeek2 = rating(rams, "2025-09-14", "away", "KC", 20, 30);

    when(repository.findAll()).thenReturn(List.of(chiefsWeek1, ramsWeek1, chiefsWeek2, ramsWeek2));

    NflSchedule schedule = new NflSchedule();
    schedule.setGameday(LocalDate.parse("2025-09-14"));
    schedule.setHomeTeam("KC");
    schedule.setTotalLine(41.5d);
    when(nflScheduleRepository.findAll()).thenReturn(List.of(schedule));

    TeamMatchupBacktestService.TotalsBacktestSummary summary = service.runTotalsBacktest();

    assertEquals(1, summary.games());
    // The real posted line (41.5) is used instead of the computed estimate (34.0 - see the
    // fallback test above), so the error reflects the real line vs. the real actual (50).
    assertEquals(41.5d, summary.meanPredictedTotal(), 0.0001d);
    assertEquals(8.5d, summary.meanAbsoluteError(), 0.0001d);
  }

  @Test
  void spreadBacktestCountsACorrectPickWhenOurPredictedMarginAgreesWithWhichSideCoveredTheRealSpread() {
    Team chiefs = new Team();
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setAbbreviation("LAR");

    // predictedMargin = (1600 - 1500 + 55) / 25 = 6.2.
    TeamStrengthRating homeRow = new TeamStrengthRating();
    homeRow.setTeam(chiefs);
    homeRow.setHomeAway("home");
    homeRow.setGameDate(LocalDate.parse("2025-09-07"));
    homeRow.setOpponentTeamId("LA");
    homeRow.setRatingBefore(1600.0d);
    homeRow.setPointsScored(27);
    homeRow.setPointsAllowed(17);

    TeamStrengthRating awayRow = new TeamStrengthRating();
    awayRow.setTeam(rams);
    awayRow.setHomeAway("away");
    awayRow.setGameDate(LocalDate.parse("2025-09-07"));
    awayRow.setOpponentTeamId("KC");
    awayRow.setRatingBefore(1500.0d);
    awayRow.setPointsScored(17);
    awayRow.setPointsAllowed(27);

    when(repository.findAll()).thenReturn(List.of(homeRow, awayRow));

    // spread_line +3.0 (home favored by 3 - nflverse's spread_line is already the home team's own
    // implied margin, positive = favored, confirmed empirically 2026-08-20) -> Vegas implied home
    // margin 3.0. We predict 6.2 (more favorable to home than the market), and the real result
    // (actual margin 10) agrees home covered - a correct pick.
    NflSchedule schedule = new NflSchedule();
    schedule.setGameday(LocalDate.parse("2025-09-07"));
    schedule.setHomeTeam("KC");
    schedule.setSpreadLine(3.0d);
    when(nflScheduleRepository.findAll()).thenReturn(List.of(schedule));

    TeamMatchupBacktestService.SpreadBacktestSummary summary = service.runSpreadBacktest();

    assertEquals(1, summary.games());
    assertEquals(0, summary.pushes());
    assertEquals(1, summary.correctSide());
    assertEquals(1.0d, summary.hitRate(), 0.0001d);
  }

  @Test
  void spreadBacktestExcludesAnExactPushFromTheHitRate() {
    Team chiefs = new Team();
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setAbbreviation("LAR");

    TeamStrengthRating homeRow = new TeamStrengthRating();
    homeRow.setTeam(chiefs);
    homeRow.setHomeAway("home");
    homeRow.setGameDate(LocalDate.parse("2025-09-07"));
    homeRow.setOpponentTeamId("LA");
    homeRow.setRatingBefore(1600.0d);
    homeRow.setPointsScored(20);
    homeRow.setPointsAllowed(17);

    TeamStrengthRating awayRow = new TeamStrengthRating();
    awayRow.setTeam(rams);
    awayRow.setHomeAway("away");
    awayRow.setGameDate(LocalDate.parse("2025-09-07"));
    awayRow.setOpponentTeamId("KC");
    awayRow.setRatingBefore(1500.0d);
    awayRow.setPointsScored(17);
    awayRow.setPointsAllowed(20);

    when(repository.findAll()).thenReturn(List.of(homeRow, awayRow));

    // Actual margin (20-17=3) exactly equals the Vegas implied home margin (spread +3.0 -> 3.0) -
    // a push, excluded from the hit rate.
    NflSchedule schedule = new NflSchedule();
    schedule.setGameday(LocalDate.parse("2025-09-07"));
    schedule.setHomeTeam("KC");
    schedule.setSpreadLine(3.0d);
    when(nflScheduleRepository.findAll()).thenReturn(List.of(schedule));

    TeamMatchupBacktestService.SpreadBacktestSummary summary = service.runSpreadBacktest();

    assertEquals(1, summary.games());
    assertEquals(1, summary.pushes());
    assertEquals(0, summary.correctSide());
    assertEquals(0.0d, summary.hitRate(), 0.0001d);
  }

  @Test
  void spreadBacktestSkipsGamesWithNoRealPostedSpreadLine() {
    Team chiefs = new Team();
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setAbbreviation("LAR");

    TeamStrengthRating homeRow = new TeamStrengthRating();
    homeRow.setTeam(chiefs);
    homeRow.setHomeAway("home");
    homeRow.setGameDate(LocalDate.parse("2025-09-07"));
    homeRow.setOpponentTeamId("LA");
    homeRow.setRatingBefore(1600.0d);
    homeRow.setPointsScored(27);
    homeRow.setPointsAllowed(17);

    TeamStrengthRating awayRow = new TeamStrengthRating();
    awayRow.setTeam(rams);
    awayRow.setHomeAway("away");
    awayRow.setGameDate(LocalDate.parse("2025-09-07"));
    awayRow.setOpponentTeamId("KC");
    awayRow.setRatingBefore(1500.0d);
    awayRow.setPointsScored(17);
    awayRow.setPointsAllowed(27);

    when(repository.findAll()).thenReturn(List.of(homeRow, awayRow));
    when(nflScheduleRepository.findAll()).thenReturn(List.of());

    TeamMatchupBacktestService.SpreadBacktestSummary summary = service.runSpreadBacktest();

    assertEquals(0, summary.games());
  }

  private TeamStrengthRating rating(
      Team team, String gameDate, String homeAway, String opponentTeamId, int pointsScored, int pointsAllowed) {
    TeamStrengthRating rating = new TeamStrengthRating();
    rating.setTeam(team);
    rating.setGameDate(LocalDate.parse(gameDate));
    rating.setHomeAway(homeAway);
    rating.setOpponentTeamId(opponentTeamId);
    rating.setPointsScored(pointsScored);
    rating.setPointsAllowed(pointsAllowed);
    return rating;
  }

  @Test
  void skipsGamesWhereTheAwayCounterpartRowIsMissing() {
    Team chiefs = new Team();
    chiefs.setAbbreviation("KC");

    TeamStrengthRating orphanHomeRow = new TeamStrengthRating();
    orphanHomeRow.setTeam(chiefs);
    orphanHomeRow.setHomeAway("home");
    orphanHomeRow.setGameDate(LocalDate.parse("2025-09-07"));
    orphanHomeRow.setOpponentTeamId("LA");
    orphanHomeRow.setRatingBefore(1600.0d);
    orphanHomeRow.setPointsScored(27);
    orphanHomeRow.setPointsAllowed(17);

    when(repository.findAll()).thenReturn(List.of(orphanHomeRow));

    TeamMatchupBacktestService.TeamMatchupBacktestSummary summary = service.runBacktest();

    assertEquals(0, summary.games());
  }
}

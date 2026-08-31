package com.agp.bets.goforbroke.team.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.team.domain.NflSchedule;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import com.agp.bets.goforbroke.team.domain.TeamOffenseGameStat;
import com.agp.bets.goforbroke.team.domain.TeamStrengthRating;
import com.agp.bets.goforbroke.team.repository.NflScheduleRepository;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamOffenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamStrengthRatingRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TeamMatchupBacktestServiceTest {

  private final TeamStrengthRatingRepository repository = Mockito.mock(TeamStrengthRatingRepository.class);
  private final NflScheduleRepository nflScheduleRepository = Mockito.mock(NflScheduleRepository.class);
  private final TeamOffenseGameStatRepository teamOffenseGameStatRepository =
      Mockito.mock(TeamOffenseGameStatRepository.class);
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository =
      Mockito.mock(TeamDefenseGameStatRepository.class);
  private final TeamMatchupBacktestService service =
      new TeamMatchupBacktestService(
          repository,
          nflScheduleRepository,
          teamOffenseGameStatRepository,
          teamDefenseGameStatRepository,
          new TeamMatchupPredictionService());

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
    // 2026-08-28 real-calibrated formula (see TOTAL_POINTS_INTERCEPT's doc): 20.9851 +
    // 0.4702*24(home scored) + 0.1335*10(home allowed) + 0.3024*20(away scored) +
    // 0.1741*14(away allowed) = 42.0903. actualTotal = 30 + 20 = 50 (real data, unchanged).
    assertEquals(42.0903d, summary.meanPredictedTotal(), 0.0001d);
    assertEquals(50.0d, summary.meanActualTotal(), 0.0001d);
    assertEquals(7.9097d, summary.meanAbsoluteError(), 0.0001d);
  }

  @Test
  void backtestUsesTheRealCalibratedOffenseDefenseMarginWhenTrailingHistoryExists() {
    Team chiefs = new Team();
    chiefs.setId(1L);
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setId(2L);
    rams.setAbbreviation("LAR");

    // Same shape as the totals-backtest fixture above - week 1 feeds week 2's trailing averages.
    TeamStrengthRating chiefsWeek1 = rating(chiefs, "2025-09-07", "away", "LAR", 24, 10);
    TeamStrengthRating ramsWeek1 = rating(rams, "2025-09-07", "home", "KC", 20, 14);

    TeamStrengthRating chiefsWeek2 = rating(chiefs, "2025-09-14", "home", "LA", 30, 20);
    chiefsWeek2.setRatingBefore(1600.0d);
    TeamStrengthRating ramsWeek2 = rating(rams, "2025-09-14", "away", "KC", 20, 30);
    ramsWeek2.setRatingBefore(1500.0d);

    when(repository.findAll()).thenReturn(List.of(chiefsWeek1, ramsWeek1, chiefsWeek2, ramsWeek2));

    TeamMatchupBacktestService.TeamMatchupBacktestSummary summary = service.runBacktest();

    assertEquals(1, summary.games());
    // homeRecentScored=24 (chiefs' own week-1 output), homeRecentAllowed=10, awayRecentScored=20
    // (rams' own week-1 output) - real prior history, not the null-fallback case. eloPredictedMargin
    // = (1600-1500+55)/25 = 6.2. predictedMargin = 5.8815 + 0.6427*6.2 + 0.2283*24 - 0.1948*10 -
    // 0.2695*20 = 8.00744 (2026-08-28 real-calibrated formula - see
    // TeamMatchupPredictionService.OFFENSE_DEFENSE_INTERCEPT's doc). If this were still using the
    // pure-Elo fallback (margin 6.2), the error below would be 3.8, not 1.99256 - a direct proof
    // the enriched formula is what actually ran.
    assertEquals(1.99256d, summary.meanAbsoluteMarginError(), 0.0001d);
  }

  @Test
  void marginAndTotalsBacktestsEachUseTheirOwnSeparatelyCalibratedTrailingWindow() {
    // 2026-08-31 real finding: real held-out validation found margin/win-pick accuracy wants a
    // 6-game trailing window while totals MAE wants a 10-game window - genuinely different from
    // each other and from the old shared 8. KC gets 11 prior games with strictly increasing scored
    // (1..11) and strictly decreasing allowed (110..10) so trailing-6 and trailing-10 averages are
    // unambiguously different, proving runBacktest and runTotalsBacktest each read their own window
    // rather than one shared value. LAR (away) gets only 1 prior game - below either window size,
    // so it can't itself distinguish the two windows; only KC's (home) side does.
    Team chiefs = new Team();
    chiefs.setId(1L);
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setId(2L);
    rams.setAbbreviation("LAR");

    // KC's 11 filler rows are all real, otherwise-independently-backtestable "home" rows (matching
    // the shape every other row in this file uses) - dating LAR's one prior row well outside that
    // range (rather than reusing one of the 11 dates) keeps it from accidentally matching one of
    // them as its own away counterpart and inflating games() beyond the single target game below.
    java.util.List<TeamStrengthRating> allRatings = new java.util.ArrayList<>();
    for (int week = 1; week <= 11; week++) {
      TeamStrengthRating kcGame =
          rating(chiefs, LocalDate.parse("2025-01-01").plusWeeks(week).toString(), "home", "LA", week, 120 - 10 * week);
      allRatings.add(kcGame);
    }
    allRatings.add(rating(rams, "2024-06-01", "away", "KC", 25, 15));

    TeamStrengthRating kcTarget = rating(chiefs, LocalDate.parse("2025-01-01").plusWeeks(12).toString(), "home", "LA", 24, 20);
    kcTarget.setRatingBefore(1600.0d);
    TeamStrengthRating ramsTarget = rating(rams, LocalDate.parse("2025-01-01").plusWeeks(12).toString(), "away", "KC", 20, 24);
    ramsTarget.setRatingBefore(1500.0d);
    allRatings.add(kcTarget);
    allRatings.add(ramsTarget);

    when(repository.findAll()).thenReturn(allRatings);
    when(nflScheduleRepository.findAll()).thenReturn(List.of());

    TeamMatchupBacktestService.TeamMatchupBacktestSummary marginSummary = service.runBacktest();
    TeamMatchupBacktestService.TotalsBacktestSummary totalsSummary = service.runTotalsBacktest();

    assertEquals(1, marginSummary.games());
    // window=6: homeRecentScored=mean(6..11)=8.5, homeRecentAllowed=mean(60,50,40,30,20,10)=35.0,
    // awayRecentScored=25 (LAR's only prior game). eloMargin=(1600-1500+55)/25=6.2. predictedMargin
    // = 5.8815 + 0.6427*6.2 + 0.2283*8.5 - 0.1948*35 - 0.2695*25 = -1.74871. actualMargin=24-20=4.
    assertEquals(5.74871d, marginSummary.meanAbsoluteMarginError(), 0.0001d);

    assertEquals(1, totalsSummary.games());
    // window=10: homeRecentScored=mean(2..11)=6.5, homeRecentAllowed=mean(100,90,...,10)=55.0,
    // awayRecentScored=25, awayRecentAllowed=15. predictedTotal = 20.9851 + 0.4702*6.5 + 0.1335*55
    // + 0.3024*25 + 0.1741*15 = 41.5554. actualTotal=24+20=44.
    assertEquals(41.5554d, totalsSummary.meanPredictedTotal(), 0.0001d);
    assertEquals(2.4446d, totalsSummary.meanAbsoluteError(), 0.0001d);
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

  @Test
  void styleCalibrationExportComputesTrailingAveragesForBothSidesOfBothOffenseAndDefense() {
    Team chiefs = new Team();
    chiefs.setId(1L);
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setId(2L);
    rams.setAbbreviation("LAR");

    TeamStrengthRating homeRow = rating(chiefs, "2025-09-14", "home", "LA", 30, 20);
    homeRow.setRatingBefore(1550.0d);
    TeamStrengthRating awayRow = rating(rams, "2025-09-14", "away", "KC", 20, 30);
    awayRow.setRatingBefore(1500.0d);
    when(repository.findAll()).thenReturn(List.of(homeRow, awayRow));

    when(teamOffenseGameStatRepository.findAll())
        .thenReturn(
            List.of(
                offenseStat(chiefs, "2025-09-07", 0.6d, 0.5d),
                offenseStat(rams, "2025-09-07", 0.5d, 0.4d)));
    when(teamDefenseGameStatRepository.findAll())
        .thenReturn(
            List.of(
                defenseStat(chiefs, "2025-09-07", 0.3d, 4.0d, 6.0d, 8),
                defenseStat(rams, "2025-09-07", 0.35d, 4.5d, 6.5d, 9)));

    List<TeamMatchupBacktestService.StyleCalibrationRow> rows = service.runStyleCalibrationExport();

    assertEquals(1, rows.size());
    TeamMatchupBacktestService.StyleCalibrationRow row = rows.get(0);
    assertEquals(10.0d, row.actualMargin(), 0.0001d);
    // predictedMargin = (1550 - 1500 + 55) / 25 = 4.2.
    assertEquals(4.2d, row.eloPredictedMargin(), 0.0001d);
    assertEquals(0.6d, row.homePassRate(), 0.0001d);
    assertEquals(0.5d, row.homeShotgunRate(), 0.0001d);
    assertEquals(0.5d, row.awayPassRate(), 0.0001d);
    assertEquals(0.4d, row.awayShotgunRate(), 0.0001d);
    assertEquals(0.3d, row.homeZoneCoverageRate(), 0.0001d);
    assertEquals(4.0d, row.homeAvgPassRushers(), 0.0001d);
    assertEquals(6.0d, row.homeAvgDefendersInBox(), 0.0001d);
    assertEquals(8.0d, row.homePressures(), 0.0001d);
    assertEquals(0.35d, row.awayZoneCoverageRate(), 0.0001d);
    assertEquals(4.5d, row.awayAvgPassRushers(), 0.0001d);
    assertEquals(6.5d, row.awayAvgDefendersInBox(), 0.0001d);
    assertEquals(9.0d, row.awayPressures(), 0.0001d);
  }

  @Test
  void styleCalibrationExportSkipsGamesWithNoOffenseOrDefenseHistoryYet() {
    Team chiefs = new Team();
    chiefs.setId(1L);
    chiefs.setAbbreviation("KC");
    Team rams = new Team();
    rams.setId(2L);
    rams.setAbbreviation("LAR");

    TeamStrengthRating homeRow = rating(chiefs, "2025-09-14", "home", "LA", 30, 20);
    homeRow.setRatingBefore(1550.0d);
    TeamStrengthRating awayRow = rating(rams, "2025-09-14", "away", "KC", 20, 30);
    awayRow.setRatingBefore(1500.0d);
    when(repository.findAll()).thenReturn(List.of(homeRow, awayRow));
    // No offense/defense rows stored for either team yet.

    List<TeamMatchupBacktestService.StyleCalibrationRow> rows = service.runStyleCalibrationExport();

    assertEquals(0, rows.size());
  }

  private TeamOffenseGameStat offenseStat(Team team, String gameDate, double passRate, double shotgunRate) {
    TeamOffenseGameStat stat = new TeamOffenseGameStat();
    stat.setTeam(team);
    stat.setGameDate(LocalDate.parse(gameDate));
    stat.setPassRate(passRate);
    stat.setShotgunRate(shotgunRate);
    return stat;
  }

  private TeamDefenseGameStat defenseStat(
      Team team, String gameDate, double zoneCoverageRate, double avgPassRushers, double avgDefendersInBox, int pressures) {
    TeamDefenseGameStat stat = new TeamDefenseGameStat();
    stat.setTeam(team);
    stat.setGameDate(LocalDate.parse(gameDate));
    stat.setZoneCoverageRate(zoneCoverageRate);
    stat.setAvgPassRushers(avgPassRushers);
    stat.setAvgDefendersInBox(avgDefendersInBox);
    stat.setPressures(pressures);
    return stat;
  }
}

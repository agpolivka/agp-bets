package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.player.web.dto.PlayerPredictionResponse.PredictionSummaryResponse;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayerPredictionServiceTest {

  private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
  private final PlayerGameStatRepository playerGameStatRepository =
      Mockito.mock(PlayerGameStatRepository.class);
  private final TeamRepository teamRepository = Mockito.mock(TeamRepository.class);
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository =
      Mockito.mock(TeamDefenseGameStatRepository.class);
  private final WeatherForecastClient weatherForecastClient = Mockito.mock(WeatherForecastClient.class);
  private final PlayerPredictionService service =
      new PlayerPredictionService(
          playerRepository,
          playerGameStatRepository,
          teamRepository,
          teamDefenseGameStatRepository,
          weatherForecastClient);

  @Test
  void opponentAdjustmentUsesRealLeagueAverageNotTheOldHardcodedBaseline() {
    Player player = new Player();
    player.setId(1L);
    player.setEspnAthleteId("4038815");
    player.setDisplayName("Test Back");
    player.setPosition("RB");
    player.setTeamId("7");

    Team currentTeam = new Team();
    currentTeam.setId(7L);
    currentTeam.setEspnTeamId("7");
    currentTeam.setUpcomingOpponentTeamId("26");

    Team opponentTeam = new Team();
    opponentTeam.setId(26L);
    opponentTeam.setEspnTeamId("26");

    when(playerRepository.findByEspnAthleteId("4038815")).thenReturn(Optional.of(player));
    when(playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(1L))
        .thenReturn(List.of(rushingGame("2026-09-01", 100), rushingGame("2026-09-08", 100)));
    when(teamRepository.findByEspnTeamId("7")).thenReturn(Optional.of(currentTeam));
    when(teamRepository.findByEspnTeamId("26")).thenReturn(Optional.of(opponentTeam));
    // Opponent has allowed 150 rushing yards/game.
    when(teamDefenseGameStatRepository.findAllByTeam_IdOrderByGameDateDesc(26L))
        .thenReturn(List.of(defenseGame(150), defenseGame(150)));
    // League-wide average (across every stored team, not just this opponent) is 120, not the old
    // hardcoded 110.0 constant.
    when(teamDefenseGameStatRepository.findAllByGameDateBetween(Mockito.any(), Mockito.any()))
        .thenReturn(List.of(defenseGame(100), defenseGame(120), defenseGame(140)));

    var response = service.getPredictionForAthleteId("4038815");

    PredictionSummaryResponse rushing =
        response.projections().stream()
            .filter(p -> p.metric().equals("rushingYards"))
            .findFirst()
            .orElseThrow();

    // (opponentAverage 150 - leagueAverage 120) * 0.035 = 1.05, not (150 - 110) * 0.035 = 1.4.
    assertEquals(1.05d, rushing.opponentAdjustment(), 0.0001d);
  }

  @Test
  void opponentAdjustmentFallsBackToDefaultBaselineWhenNoLeagueDataStored() {
    Player player = new Player();
    player.setId(1L);
    player.setEspnAthleteId("4038815");
    player.setDisplayName("Test Back");
    player.setPosition("RB");
    player.setTeamId("7");

    Team currentTeam = new Team();
    currentTeam.setId(7L);
    currentTeam.setEspnTeamId("7");
    currentTeam.setUpcomingOpponentTeamId("26");

    Team opponentTeam = new Team();
    opponentTeam.setId(26L);
    opponentTeam.setEspnTeamId("26");

    when(playerRepository.findByEspnAthleteId("4038815")).thenReturn(Optional.of(player));
    when(playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(1L))
        .thenReturn(List.of(rushingGame("2026-09-01", 100)));
    when(teamRepository.findByEspnTeamId("7")).thenReturn(Optional.of(currentTeam));
    when(teamRepository.findByEspnTeamId("26")).thenReturn(Optional.of(opponentTeam));
    when(teamDefenseGameStatRepository.findAllByTeam_IdOrderByGameDateDesc(26L))
        .thenReturn(List.of(defenseGame(150)));
    // No team-defense data stored anywhere yet.
    when(teamDefenseGameStatRepository.findAllByGameDateBetween(Mockito.any(), Mockito.any()))
        .thenReturn(List.of());

    var response = service.getPredictionForAthleteId("4038815");

    PredictionSummaryResponse rushing =
        response.projections().stream()
            .filter(p -> p.metric().equals("rushingYards"))
            .findFirst()
            .orElseThrow();

    // Falls back to the documented default baseline of 110.0: (150 - 110) * 0.035 = 1.4.
    assertEquals(1.4d, rushing.opponentAdjustment(), 0.0001d);
  }

  @Test
  void recentGameWindowForReturnsTheRealCalibratedPerMetricOverrides() {
    // 2026-08-30: window length retested per metric with a real held-out train/test split (same 6
    // cutoffs as every other recalibration in this file) - these three metrics won on every single
    // cutoff with a wider window, everything else stays at the default 5 - see the doc on
    // RECENT_GAME_WINDOW_BY_METRIC.
    assertEquals(10, service.recentGameWindowFor("receivingYards"));
    assertEquals(8, service.recentGameWindowFor("passingTouchdowns"));
    assertEquals(10, service.recentGameWindowFor("turnovers"));
    assertEquals(5, service.recentGameWindowFor("rushingYards"));
    assertEquals(5, service.recentGameWindowFor("receptions"));
    assertEquals(5, service.recentGameWindowFor("touchdowns"));
    assertEquals(5, service.recentGameWindowFor("passingYards"));
  }

  @Test
  void buildProjectionUsesTheWiderRealCalibratedWindowForReceivingYardsNotThePassedInRecentStats() {
    // 12-game career, most-recent-first, matching how allStats is always ordered in this file.
    List<PlayerGameStat> allStats =
        List.of(
            receivingGame("2026-09-01", 100), receivingGame("2026-08-25", 90), receivingGame("2026-08-18", 80),
            receivingGame("2026-08-11", 70), receivingGame("2026-08-04", 60), receivingGame("2026-07-28", 50),
            receivingGame("2026-07-21", 40), receivingGame("2026-07-14", 30), receivingGame("2026-07-07", 20),
            receivingGame("2026-06-30", 10), receivingGame("2026-06-23", 5), receivingGame("2026-06-16", 5));
    // The old fixed 5-game recentStats, passed through unused for this metric now - deliberately
    // different from allStats' own 10-game prefix, so a passing test proves buildProjection really
    // is reading the wider window from allStats and not silently falling back to this list.
    List<PlayerGameStat> recentStats = allStats.subList(0, 5);

    PredictionSummaryResponse projection =
        service.buildProjection(
            "receivingYards",
            recentStats,
            allStats,
            "WR",
            List.of(),
            new PlayerPredictionService.LeagueDefenseAverages(0, 0, 0, 0, 0, 0, 0, 0, 0),
            new PlayerPredictionService.GameConditions("dome", null, null),
            null);

    // recentAverage (window=10) = mean(100,90,80,70,60,50,40,30,20,10) = 55; seasonAverage (full
    // 12) = mean(...,5,5) = 46.66667. blendedMean = 0.65*55 + 0.35*46.66667 = 52.08333. Every other
    // adjustment is 0 (no defense history, dome/no wind/no total line, no advanced-stat fields set
    // on these fixtures) so this is the whole projection.
    assertEquals(52.08333d, projection.mean(), 0.001d);
  }

  @Test
  void buildProjectionsLowerAndUpperBoundUseAPredictionIntervalNotAConfidenceIntervalForTheMean() {
    // 2026-08-31 real bug fix: the old formula (1.28 * stdDev / sqrt(n)) is a confidence interval
    // for the player's true average, not a prediction interval for one upcoming game - it shrinks
    // toward zero as more games accumulate instead of converging to stdDev. 3 games of
    // receivingYards (40, 50, 60): mean=50, sample stdDev=10 (variance=((40-50)^2+(60-50)^2)/(3-1)
    // = 200/2 = 100). margin = 1.28 * 10 * sqrt(1 + 1/3) = 12.8 * sqrt(4/3) = 14.780166891254419.
    List<PlayerGameStat> allStats =
        List.of(receivingGame("2026-09-01", 40), receivingGame("2026-08-25", 50), receivingGame("2026-08-18", 60));

    PredictionSummaryResponse projection =
        service.buildProjection(
            "receivingYards",
            allStats,
            allStats,
            "WR",
            List.of(),
            new PlayerPredictionService.LeagueDefenseAverages(0, 0, 0, 0, 0, 0, 0, 0, 0),
            new PlayerPredictionService.GameConditions("dome", null, null),
            null);

    assertEquals(50.0d, projection.mean(), 0.001d);
    assertEquals(35.21983d, projection.lowerBound(), 0.001d);
    assertEquals(64.78017d, projection.upperBound(), 0.001d);
  }

  @Test
  void metricsForPositionReturnsNoProjectionsForKnownNonSkillPositions() {
    assertEquals(List.of(), service.metricsForPosition("K"));
    assertEquals(List.of(), service.metricsForPosition("OL"));
    assertEquals(List.of(), service.metricsForPosition("LB"));
  }

  @Test
  void metricsForPositionFallsBackToDefaultForGenuinelyUnknownPosition() {
    // Null/blank position means "we don't actually know" (could still be a skill player with
    // unsynced data), unlike a real, recognized non-skill position above - so it keeps the old
    // graceful-fallback behavior instead of returning nothing.
    List<String> expectedDefault = List.of("rushingYards", "receivingYards", "receptions", "touchdowns");
    assertEquals(expectedDefault, service.metricsForPosition(null));
    assertEquals(expectedDefault, service.metricsForPosition(""));
  }

  @Test
  void rushingQualityAdjustmentIsPositiveWhenPlayerBeatsLeagueAverageAfterContactRate() {
    // League average is 2.6 yds/carry after contact (see PlayerPredictionService). This player
    // averages 4.0 yds/carry after contact across 20 carries/game -> should nudge the projection up.
    List<PlayerGameStat> recentStats =
        List.of(
            rushingGameWithContact(20, 80),
            rushingGameWithContact(20, 80));

    double adjustment = service.rushingQualityAdjustment("rushingYards", recentStats);

    // (4.0 - 2.6) * 20 * 0.35 = 9.8
    assertEquals(9.8d, adjustment, 0.0001d);
  }

  @Test
  void rushingQualityAdjustmentIsZeroForNonRushingMetricsAndWhenNoContactDataStored() {
    List<PlayerGameStat> recentStats = List.of(rushingGameWithContact(20, 80));

    assertEquals(0.0d, service.rushingQualityAdjustment("receivingYards", recentStats), 0.0001d);
    assertEquals(0.0d, service.rushingQualityAdjustment("rushingYards", List.of(rushingGame("2026-09-01", 100))), 0.0001d);
  }

  @Test
  void advancedMetricAdjustmentScalesPassingYardsAndTouchdownsByRecentCpoe() {
    List<PlayerGameStat> recentStats = List.of(cpoeGame(4.0), cpoeGame(2.0));

    // Average CPOE is 3.0 -> passingYards: 3.0 * 0.0273 = 0.0819; passingTouchdowns: 3.0 * 0.0054 = 0.0162
    // (2026-08-28 real-calibrated train/test values - see CPOE_PASSING_YARDS_COEFFICIENT's doc).
    assertEquals(0.0819d, service.advancedMetricAdjustment("passingYards", recentStats), 0.0001d);
    assertEquals(0.0162d, service.advancedMetricAdjustment("passingTouchdowns", recentStats), 0.0001d);
    assertEquals(0.0d, service.advancedMetricAdjustment("rushingYards", recentStats), 0.0001d);
  }

  @Test
  void advancedMetricAdjustmentScalesReceivingYardsByRecentYacAboveExpectationAndVolume() {
    // 1.0 YAC-over-expectation/catch across 5 catches/game, both games -> playerRate 1.0,
    // averageReceptions 5.0 -> 1.0 * 5.0 * -0.0648 = -0.324 (2026-08-28: real train/test
    // calibration found a consistent sign flip - see RECEIVING_QUALITY_COEFFICIENT's doc).
    List<PlayerGameStat> recentStats = List.of(yacGame(5, 1.0), yacGame(5, 1.0));

    assertEquals(-0.324d, service.advancedMetricAdjustment("receivingYards", recentStats), 0.0001d);
    assertEquals(0.0d, service.advancedMetricAdjustment("passingYards", recentStats), 0.0001d);
  }

  @Test
  void targetShareAdjustmentIsPositiveWhenRecentWoprBeatsLeagueAverage() {
    // League average is 0.28 (see PlayerPredictionService). This player averages 0.48 wopr across
    // recent games -> delta 0.20 -> should nudge receivingYards/receptions up.
    List<PlayerGameStat> recentStats = List.of(woprGame(0.5), woprGame(0.46));

    // delta = 0.48 - 0.28 = 0.20 (within floating point tolerance).
    assertEquals(0.916d, service.targetShareAdjustment("receivingYards", recentStats), 0.001d);
    assertEquals(0.05d, service.targetShareAdjustment("receptions", recentStats), 0.001d);
  }

  @Test
  void targetShareAdjustmentIsZeroForNonReceivingMetricsAndWhenNoWoprDataStored() {
    List<PlayerGameStat> recentStats = List.of(woprGame(0.5));

    assertEquals(0.0d, service.targetShareAdjustment("rushingYards", recentStats), 0.0001d);
    assertEquals(0.0d, service.targetShareAdjustment("receivingYards", List.of(rushingGame("2026-09-01", 100))), 0.0001d);
  }

  private PlayerGameStat woprGame(double wopr) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setWopr(wopr);
    return stat;
  }

  @Test
  void usageAdjustmentIsPositiveWhenRecentSnapShareBeatsThePositionBaseline() {
    // RB baseline is 0.3551 (see PlayerPredictionService). This player averages 0.4551 recent snap
    // share -> delta 0.10 -> should nudge rushingYards up, nothing else.
    List<PlayerGameStat> rbStats = List.of(snapPctGame(0.46), snapPctGame(0.4502));
    assertEquals(3.75455d, service.usageAdjustment("rushingYards", "RB", rbStats), 0.001d);
    assertEquals(0.0d, service.usageAdjustment("receivingYards", "RB", rbStats), 0.0001d);

    // WR baseline is 0.5125. This player averages 0.6125 -> delta 0.10 -> nudges receivingYards up.
    List<PlayerGameStat> wrStats = List.of(snapPctGame(0.62), snapPctGame(0.605));
    assertEquals(1.86292d, service.usageAdjustment("receivingYards", "WR", wrStats), 0.001d);
  }

  @Test
  void usageAdjustmentIsZeroForNonSkillPositionsAndWhenNoSnapDataStored() {
    List<PlayerGameStat> recentStats = List.of(snapPctGame(0.6));

    assertEquals(0.0d, service.usageAdjustment("passingYards", "QB", recentStats), 0.0001d);
    assertEquals(0.0d, service.usageAdjustment("rushingYards", "RB", List.of(rushingGame("2026-09-01", 100))), 0.0001d);
  }

  @Test
  void usageAdjustmentIsZeroRatherThanThrowingForANullPosition() {
    // Regression test: Map.of(...).get(null) throws NPE rather than returning null - caught live
    // 2026-08-20/22 via a real 500 on /api/backtest/outcomes for players with an unknown position
    // (metricsForPosition's own documented null-position fallback reaches this method too).
    assertEquals(0.0d, service.usageAdjustment("rushingYards", null, List.of(snapPctGame(0.6))), 0.0001d);
  }

  private PlayerGameStat snapPctGame(double offenseSnapPct) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setOffenseSnapPct(offenseSnapPct);
    return stat;
  }

  private PlayerGameStat rushingGame(String gameDate, int rushingYards) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setGameDate(LocalDate.parse(gameDate));
    stat.setRushingYards(rushingYards);
    return stat;
  }

  private PlayerGameStat receivingGame(String gameDate, int receivingYards) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setGameDate(LocalDate.parse(gameDate));
    stat.setReceivingYards(receivingYards);
    return stat;
  }

  private PlayerGameStat rushingGameWithContact(int carries, int rushingYardsAfterContact) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setCarries(carries);
    stat.setRushingYardsAfterContact(rushingYardsAfterContact);
    return stat;
  }

  private PlayerGameStat cpoeGame(double cpoe) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setPassingCpoe(cpoe);
    return stat;
  }

  private PlayerGameStat yacGame(int receptions, double yacAboveExpectation) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setReceptions(receptions);
    stat.setReceivingYacAboveExpectation(yacAboveExpectation);
    return stat;
  }

  private TeamDefenseGameStat defenseGame(int rushingYardsAllowed) {
    TeamDefenseGameStat stat = new TeamDefenseGameStat();
    stat.setRushingYardsAllowed(rushingYardsAllowed);
    return stat;
  }

  @Test
  void opponentAdjustmentAddsPressureNudgeForPassingMetrics() {
    List<TeamDefenseGameStat> defenseHistory = List.of(defenseGameWithPressures(12), defenseGameWithPressures(12));
    PlayerPredictionService.LeagueDefenseAverages leagueAverages =
        new PlayerPredictionService.LeagueDefenseAverages(
            225.0d, 110.0d, 125.0d, 21.0d, 8.5d, 0.09d, 0.53d, 4.2d, 6.5d);

    // pressureDelta = 12 - 8.5 = 3.5; passingYards: 3.5 * -1.3408 = -4.6928 (base yardage-allowed
    // term is 0 since passingYardsAllowed isn't set on these stats). 2026-08-28: real-calibrated
    // train/test value, was -2.0 - see PRESSURE_PASSING_YARDS_COEFFICIENT's doc.
    assertEquals(-4.6928d, service.opponentAdjustment("passingYards", defenseHistory, leagueAverages), 0.0001d);
    // passingTouchdowns: 3.5 * -0.0235 = -0.08225 (was -0.02 - see PRESSURE_PASSING_TOUCHDOWNS_COEFFICIENT's doc).
    assertEquals(-0.08225d, service.opponentAdjustment("passingTouchdowns", defenseHistory, leagueAverages), 0.0001d);
  }

  @Test
  void opponentAdjustmentAddsMissedTackleNudgeForYardageMetrics() {
    List<TeamDefenseGameStat> defenseHistory =
        List.of(defenseGameWithMissedTacklePct(0.15d), defenseGameWithMissedTacklePct(0.15d));
    PlayerPredictionService.LeagueDefenseAverages leagueAverages =
        new PlayerPredictionService.LeagueDefenseAverages(
            225.0d, 110.0d, 125.0d, 21.0d, 8.5d, 0.09d, 0.53d, 4.2d, 6.5d);

    // missedTackleDelta = 0.15 - 0.09 = 0.06; rushingYards: 0.06 * 100.0 = 6.0 (no real train/test
    // signal 2026-08-28, unchanged). receivingYards: 0.06 * 24.32 = 1.4592 (real-calibrated
    // 2026-08-28, was sharing the same 100.0 coefficient - see
    // MISSED_TACKLE_RECEIVING_YARDS_COEFFICIENT's doc). Both: base yardage-allowed term is 0 since
    // rushing/receivingYardsAllowed aren't set here.
    assertEquals(6.0d, service.opponentAdjustment("rushingYards", defenseHistory, leagueAverages), 0.0001d);
    assertEquals(1.4592d, service.opponentAdjustment("receivingYards", defenseHistory, leagueAverages), 0.0001d);
  }

  @Test
  void opponentAdjustmentAddsZoneCoverageNudgeForReceivingMetrics() {
    List<TeamDefenseGameStat> defenseHistory =
        List.of(defenseGameWithZoneCoverageRate(0.7d), defenseGameWithZoneCoverageRate(0.7d));
    PlayerPredictionService.LeagueDefenseAverages leagueAverages =
        new PlayerPredictionService.LeagueDefenseAverages(
            225.0d, 110.0d, 125.0d, 21.0d, 8.5d, 0.09d, 0.53d, 4.2d, 6.5d);

    // zoneCoverageRateDelta = 0.7 - 0.53 = 0.17; receivingYards: 0.17 * 8.4465 = 1.435905
    // (missedTackleDelta contributes 0 since missedTacklePct isn't set on these stats);
    // receptions: 0.17 * 1.2441 = 0.211497. rushingYards/passingYards are untouched by this
    // signal (real-calibrated - see ZONE_COVERAGE_RECEIVING_YARDS_COEFFICIENT's doc).
    assertEquals(1.435905d, service.opponentAdjustment("receivingYards", defenseHistory, leagueAverages), 0.0001d);
    assertEquals(0.211497d, service.opponentAdjustment("receptions", defenseHistory, leagueAverages), 0.0001d);
    assertEquals(0.0d, service.opponentAdjustment("rushingYards", defenseHistory, leagueAverages), 0.0001d);
  }

  private TeamDefenseGameStat defenseGameWithPressures(int pressures) {
    TeamDefenseGameStat stat = new TeamDefenseGameStat();
    stat.setPressures(pressures);
    return stat;
  }

  private TeamDefenseGameStat defenseGameWithMissedTacklePct(double missedTacklePct) {
    TeamDefenseGameStat stat = new TeamDefenseGameStat();
    stat.setMissedTacklePct(missedTacklePct);
    return stat;
  }

  // Not yet wired into opponentAdjustment/advancedDefenseAdjustment (see those methods' docs), so
  // these three are tested directly rather than through opponentAdjustment the way
  // pressureDelta/missedTackleDelta are above.
  @Test
  void zoneCoverageRateDeltaComparesTrailingAverageAgainstLeagueBaseline() {
    List<TeamDefenseGameStat> defenseHistory =
        List.of(defenseGameWithZoneCoverageRate(0.7d), defenseGameWithZoneCoverageRate(0.7d));
    PlayerPredictionService.LeagueDefenseAverages leagueAverages =
        new PlayerPredictionService.LeagueDefenseAverages(
            225.0d, 110.0d, 125.0d, 21.0d, 8.5d, 0.09d, 0.53d, 4.2d, 6.5d);

    assertEquals(
        0.17d, service.zoneCoverageRateDelta(defenseHistory, leagueAverages), 0.0001d);
  }

  @Test
  void avgPassRushersDeltaComparesTrailingAverageAgainstLeagueBaseline() {
    List<TeamDefenseGameStat> defenseHistory =
        List.of(defenseGameWithAvgPassRushers(5.2d), defenseGameWithAvgPassRushers(5.2d));
    PlayerPredictionService.LeagueDefenseAverages leagueAverages =
        new PlayerPredictionService.LeagueDefenseAverages(
            225.0d, 110.0d, 125.0d, 21.0d, 8.5d, 0.09d, 0.53d, 4.2d, 6.5d);

    assertEquals(1.0d, service.avgPassRushersDelta(defenseHistory, leagueAverages), 0.0001d);
  }

  @Test
  void avgDefendersInBoxDeltaComparesTrailingAverageAgainstLeagueBaseline() {
    List<TeamDefenseGameStat> defenseHistory =
        List.of(defenseGameWithAvgDefendersInBox(7.5d), defenseGameWithAvgDefendersInBox(7.5d));
    PlayerPredictionService.LeagueDefenseAverages leagueAverages =
        new PlayerPredictionService.LeagueDefenseAverages(
            225.0d, 110.0d, 125.0d, 21.0d, 8.5d, 0.09d, 0.53d, 4.2d, 6.5d);

    assertEquals(1.0d, service.avgDefendersInBoxDelta(defenseHistory, leagueAverages), 0.0001d);
  }

  @Test
  void participationDeltasAreZeroWhenNoDataStoredYet() {
    List<TeamDefenseGameStat> defenseHistory = List.of(new TeamDefenseGameStat());
    PlayerPredictionService.LeagueDefenseAverages leagueAverages =
        new PlayerPredictionService.LeagueDefenseAverages(
            225.0d, 110.0d, 125.0d, 21.0d, 8.5d, 0.09d, 0.53d, 4.2d, 6.5d);

    assertEquals(0.0d, service.zoneCoverageRateDelta(defenseHistory, leagueAverages), 0.0001d);
    assertEquals(0.0d, service.avgPassRushersDelta(defenseHistory, leagueAverages), 0.0001d);
    assertEquals(0.0d, service.avgDefendersInBoxDelta(defenseHistory, leagueAverages), 0.0001d);
  }

  private TeamDefenseGameStat defenseGameWithZoneCoverageRate(double zoneCoverageRate) {
    TeamDefenseGameStat stat = new TeamDefenseGameStat();
    stat.setZoneCoverageRate(zoneCoverageRate);
    return stat;
  }

  private TeamDefenseGameStat defenseGameWithAvgPassRushers(double avgPassRushers) {
    TeamDefenseGameStat stat = new TeamDefenseGameStat();
    stat.setAvgPassRushers(avgPassRushers);
    return stat;
  }

  private TeamDefenseGameStat defenseGameWithAvgDefendersInBox(double avgDefendersInBox) {
    TeamDefenseGameStat stat = new TeamDefenseGameStat();
    stat.setAvgDefendersInBox(avgDefendersInBox);
    return stat;
  }

  @Test
  void injuryStatusMultiplierIsANoOpForNullOrActiveStatus() {
    assertEquals(1.0d, PlayerPredictionService.injuryStatusMultiplier(null), 0.0001d);
    assertEquals(1.0d, PlayerPredictionService.injuryStatusMultiplier(""), 0.0001d);
    assertEquals(1.0d, PlayerPredictionService.injuryStatusMultiplier("Active"), 0.0001d);
  }

  @Test
  void injuryStatusMultiplierReducesQuestionableAndDoubtfulProportionally() {
    assertEquals(0.85d, PlayerPredictionService.injuryStatusMultiplier("Questionable"), 0.0001d);
    assertEquals(0.25d, PlayerPredictionService.injuryStatusMultiplier("Doubtful"), 0.0001d);
  }

  @Test
  void injuryStatusMultiplierFloorsToZeroForStatusesMeaningNotPlaying() {
    assertEquals(0.0d, PlayerPredictionService.injuryStatusMultiplier("Out"), 0.0001d);
    assertEquals(0.0d, PlayerPredictionService.injuryStatusMultiplier("Injured Reserve"), 0.0001d);
    assertEquals(0.0d, PlayerPredictionService.injuryStatusMultiplier("IR"), 0.0001d);
    assertEquals(0.0d, PlayerPredictionService.injuryStatusMultiplier("Suspended"), 0.0001d);
  }

  @Test
  void injuryStatusMultiplierTreatsUnrecognizedStatusTextAsANoOp() {
    assertEquals(1.0d, PlayerPredictionService.injuryStatusMultiplier("Probable"), 0.0001d);
  }

  @Test
  void getPredictionScalesEveryProjectionByAnOutPlayersInjuryStatusMultiplier() {
    Player player = new Player();
    player.setId(1L);
    player.setEspnAthleteId("4038815");
    player.setDisplayName("Test Back");
    player.setPosition("RB");
    player.setGameStatus("Out");
    player.setGameStatusDetail("ankle");

    when(playerRepository.findByEspnAthleteId("4038815")).thenReturn(Optional.of(player));
    when(playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(1L))
        .thenReturn(List.of(rushingGame("2026-09-01", 100), rushingGame("2026-09-08", 100)));

    var response = service.getPredictionForAthleteId("4038815");

    assertEquals("Out", response.gameStatus());
    assertEquals("ankle", response.gameStatusDetail());
    for (PredictionSummaryResponse projection : response.projections()) {
      assertEquals(0.0d, projection.mean(), 0.0001d);
    }
  }
}

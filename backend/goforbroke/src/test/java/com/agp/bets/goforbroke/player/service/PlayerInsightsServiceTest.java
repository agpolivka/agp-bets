package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.player.service.PlayerRefreshService;
import com.agp.bets.goforbroke.team.service.TeamRefreshService;
import com.agp.bets.goforbroke.team.service.UpcomingOpponent;
import com.agp.bets.goforbroke.team.service.UpcomingOpponentLookupService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayerInsightsServiceTest {

  private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
  private final PlayerGameStatRepository playerGameStatRepository =
      Mockito.mock(PlayerGameStatRepository.class);
  private final PlayerRefreshService playerRefreshService = Mockito.mock(PlayerRefreshService.class);
  private final UpcomingOpponentLookupService upcomingOpponentLookupService =
      Mockito.mock(UpcomingOpponentLookupService.class);
  private final TeamRefreshService teamRefreshService = Mockito.mock(TeamRefreshService.class);
  private final PlayerInsightsService service =
      new PlayerInsightsService(
          playerRepository,
          playerGameStatRepository,
          playerRefreshService,
          upcomingOpponentLookupService,
          teamRefreshService);

  @Test
  void buildsDerivedSummariesWithoutMutatingStoredStats() {
    Player player = new Player();
    player.setId(1L);
    player.setEspnAthleteId("4426338");
    player.setDisplayName("Bo Nix");
    player.setTeamId("7");

    PlayerGameStat game1 =
        stat("2026-09-01", "home", "Seattle Seahawks", "26", 200, 20, 0, 0, 0, 0, 6, 24, 0);
    PlayerGameStat game2 =
        stat("2026-09-08", "away", "Las Vegas Raiders", "13", 250, 35, 1, 0, 1, 1, 4, 18, 0);
    PlayerGameStat game3 =
        stat("2026-09-15", "home", "Seattle Seahawks", "26", 300, 20, 2, 0, 2, 2, 3, 22, 1);

    when(playerRepository.findByEspnAthleteId("4426338")).thenReturn(Optional.of(player));
    when(playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(1L))
        .thenReturn(List.of(game3, game2, game1));
    when(upcomingOpponentLookupService.findUpcomingOpponent("7"))
        .thenReturn(
            Optional.of(
                new UpcomingOpponent(
                    "26", "Seattle Seahawks", LocalDate.parse("2026-10-01"), null, null)));

    var insights = service.getInsightsForAthleteId("4426338");

    assertEquals("Bo Nix", insights.player().displayName());
    assertEquals(3, insights.gamesLoaded());
    assertEquals(3, insights.overallSummary().games());
    assertEquals(750, insights.overallSummary().passingYardsTotal());
    assertEquals(250.0d, insights.overallSummary().passingYardsPerGame());
    assertEquals(21.333333333333332d, insights.overallSummary().totalYardsPerGame());
    assertEquals(3, insights.overallSummary().touchdownsTotal());
    assertEquals(3, insights.recentGames().size());
    assertFalse(insights.homeAwaySplits().isEmpty());
    assertFalse(insights.opponentSplits().isEmpty());
    assertEquals(2, insights.opponentSplits().size());
    assertNotNull(insights.upcomingOpponent());
    assertEquals("Seattle Seahawks", insights.upcomingOpponent().opponentName());
    assertEquals(2, insights.upcomingOpponent().lastThreeSummary().games());
    assertEquals(2, insights.upcomingOpponent().allTimeSummary().games());
  }

  private PlayerGameStat stat(
      String gameDate,
      String homeAway,
      String opponentName,
      String opponentTeamId,
      Integer passingYards,
      Integer rushingYards,
      Integer passingTouchdowns,
      Integer rushingTouchdowns,
      Integer touchdowns,
      Integer totalTouchdowns,
      Integer carries,
      Integer totalYards,
      Integer drops) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setGameDate(LocalDate.parse(gameDate));
    stat.setHomeAway(homeAway);
    stat.setOpponentName(opponentName);
    stat.setOpponentTeamId(opponentTeamId);
    stat.setPassingYards(passingYards);
    stat.setRushingYards(rushingYards);
    stat.setPassingTouchdowns(passingTouchdowns);
    stat.setRushingTouchdowns(rushingTouchdowns);
    stat.setTouchdowns(touchdowns);
    stat.setTotalTouchdowns(totalTouchdowns);
    stat.setCarries(carries);
    stat.setTotalYards(totalYards);
    stat.setDrops(drops);
    return stat;
  }
}

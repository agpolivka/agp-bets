package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agp.bets.goforbroke.odds.repository.PlayerPropLineRepository;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PredictionBacktestServiceTest {

  private final PredictionBacktestService service =
      new PredictionBacktestService(
          Mockito.mock(PlayerRepository.class),
          Mockito.mock(PlayerGameStatRepository.class),
          Mockito.mock(TeamRepository.class),
          Mockito.mock(TeamDefenseGameStatRepository.class),
          Mockito.mock(PlayerPropLineRepository.class),
          Mockito.mock(PlayerPredictionService.class));

  @Test
  void resolveOpponentDefenseHistoryMatchesOpponentTeamIdThroughTheCrosswalk() {
    // Regression test for a real bug found 2026-08-17: PlayerGameStat.opponentTeamId is nflverse's
    // raw abbreviation ("LA"), but Team.abbreviation is ESPN's ("LAR") - without the crosswalk,
    // this lookup silently never matched for ANY backtested game (not just Rams/Washington games),
    // because the whole method used to key teams by espnTeamId (a numeric ESPN id, e.g. "14"),
    // which opponentTeamId never contains either. Confirmed directly against real stored data
    // before fixing: opponentTeamId values are always raw nflverse codes like "DAL"/"LA"/"WAS".
    Team rams = new Team();
    rams.setId(14L);
    rams.setAbbreviation("LAR");
    Map<String, Team> teamsByAbbreviation = Map.of("LAR", rams);

    TeamDefenseGameStat priorGame = new TeamDefenseGameStat();
    priorGame.setTeam(rams);
    priorGame.setGameDate(LocalDate.parse("2025-09-01"));
    Map<Long, List<TeamDefenseGameStat>> defenseGamesByTeamId = Map.of(14L, List.of(priorGame));

    PlayerGameStat targetGame = new PlayerGameStat();
    targetGame.setOpponentTeamId("LA");
    targetGame.setGameDate(LocalDate.parse("2025-09-08"));

    List<TeamDefenseGameStat> history =
        service.resolveOpponentDefenseHistory(targetGame, teamsByAbbreviation, defenseGamesByTeamId);

    assertEquals(1, history.size());
    assertTrue(history.contains(priorGame));
  }

  @Test
  void resolveOpponentDefenseHistoryReturnsEmptyWhenOpponentNeverSynced() {
    PlayerGameStat targetGame = new PlayerGameStat();
    targetGame.setOpponentTeamId("DAL");
    targetGame.setGameDate(LocalDate.parse("2025-09-08"));

    List<TeamDefenseGameStat> history =
        service.resolveOpponentDefenseHistory(targetGame, Map.of(), Map.of());

    assertEquals(0, history.size());
  }
}

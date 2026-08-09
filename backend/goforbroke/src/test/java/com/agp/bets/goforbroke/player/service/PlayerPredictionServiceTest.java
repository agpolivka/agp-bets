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
  private final PlayerPredictionService service =
      new PlayerPredictionService(
          playerRepository, playerGameStatRepository, teamRepository, teamDefenseGameStatRepository);

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
    when(teamDefenseGameStatRepository.findAllByGameDateAfter(Mockito.any()))
        .thenReturn(List.of(defenseGame(100), defenseGame(120), defenseGame(140)));

    var response = service.getPredictionForAthleteId("4038815");

    PredictionSummaryResponse rushing =
        response.projections().stream()
            .filter(p -> p.metric().equals("rushingYards"))
            .findFirst()
            .orElseThrow();

    // (opponentAverage 150 - leagueAverage 120) * 0.08 = 2.4, not (150 - 110) * 0.08 = 3.2.
    assertEquals(2.4d, rushing.opponentAdjustment(), 0.0001d);
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
    when(teamDefenseGameStatRepository.findAllByGameDateAfter(Mockito.any())).thenReturn(List.of());

    var response = service.getPredictionForAthleteId("4038815");

    PredictionSummaryResponse rushing =
        response.projections().stream()
            .filter(p -> p.metric().equals("rushingYards"))
            .findFirst()
            .orElseThrow();

    // Falls back to the documented default baseline of 110.0: (150 - 110) * 0.08 = 3.2.
    assertEquals(3.2d, rushing.opponentAdjustment(), 0.0001d);
  }

  private PlayerGameStat rushingGame(String gameDate, int rushingYards) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setGameDate(LocalDate.parse(gameDate));
    stat.setRushingYards(rushingYards);
    return stat;
  }

  private TeamDefenseGameStat defenseGame(int rushingYardsAllowed) {
    TeamDefenseGameStat stat = new TeamDefenseGameStat();
    stat.setRushingYardsAllowed(rushingYardsAllowed);
    return stat;
  }
}

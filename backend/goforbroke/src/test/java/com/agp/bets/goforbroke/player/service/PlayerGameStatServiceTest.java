package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayerGameStatServiceTest {

  private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
  private final PlayerGameStatRepository playerGameStatRepository =
      Mockito.mock(PlayerGameStatRepository.class);
  private final PlayerHistoryBackfillService playerHistoryBackfillService =
      Mockito.mock(PlayerHistoryBackfillService.class);
  private final PlayerGameStatService service =
      new PlayerGameStatService(
          playerRepository, playerGameStatRepository, playerHistoryBackfillService);

  @Test
  void syncStatsForAthleteIdReturnsExistingRowsWithoutTriggeringBackfill() {
    Player player = new Player();
    player.setId(1L);
    player.setEspnAthleteId("4426338");

    PlayerGameStat existing = new PlayerGameStat();
    existing.setGameDate(LocalDate.parse("2025-09-09"));
    existing.setPassingYards(215);

    when(playerRepository.findByEspnAthleteId("4426338")).thenReturn(Optional.of(player));
    when(playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(1L))
        .thenReturn(List.of(existing));

    List<PlayerGameStat> result = service.syncStatsForAthleteId("4426338");

    assertEquals(1, result.size());
    verify(playerHistoryBackfillService, never()).requestBackfillIfNeeded(Mockito.anyString());
  }

  @Test
  void syncStatsForAthleteIdTriggersBackfillWhenNoStatsAreStored() {
    Player player = new Player();
    player.setId(1L);
    player.setEspnAthleteId("4426338");

    when(playerRepository.findByEspnAthleteId("4426338")).thenReturn(Optional.of(player));
    when(playerGameStatRepository.findAllByPlayer_IdOrderBySeasonDescWeekDesc(1L)).thenReturn(List.of());

    List<PlayerGameStat> result = service.syncStatsForAthleteId("4426338");

    assertTrue(result.isEmpty());
    verify(playerHistoryBackfillService).requestBackfillIfNeeded("4426338");
  }
}

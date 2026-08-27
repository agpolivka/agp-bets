package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class InjuryStatusRefreshWorkerTest {

  private final EspnInjuryClient espnInjuryClient = Mockito.mock(EspnInjuryClient.class);
  private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
  private final InjuryStatusRefreshWorker worker =
      new InjuryStatusRefreshWorker(espnInjuryClient, playerRepository);
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void appliesAMatchingReportToAPlayerWithNoPriorStatus() throws Exception {
    Player player = new Player();
    player.setEspnAthleteId("4428811");
    when(playerRepository.findAll()).thenReturn(List.of(player));
    when(espnInjuryClient.fetchLeagueInjuries()).thenReturn(feedFor("4428811", "Questionable", "hamstring"));

    worker.refresh();

    assertEquals("Questionable", player.getGameStatus());
    assertEquals("hamstring", player.getGameStatusDetail());
    verify(playerRepository).saveAll(List.of(player));
  }

  @Test
  void clearsAStoredStatusForAPlayerNoLongerInTheFeed() throws Exception {
    Player player = new Player();
    player.setEspnAthleteId("4428811");
    player.setGameStatus("Questionable");
    player.setGameStatusDetail("hamstring");
    player.setGameStatusUpdatedAt(Instant.parse("2026-08-13T00:00:00Z"));
    when(playerRepository.findAll()).thenReturn(List.of(player));
    // Feed no longer mentions this player at all - they've recovered.
    when(espnInjuryClient.fetchLeagueInjuries()).thenReturn(objectMapper.readTree("{\"injuries\": []}"));

    worker.refresh();

    assertNull(player.getGameStatus());
    assertNull(player.getGameStatusDetail());
    assertNull(player.getGameStatusUpdatedAt());
    verify(playerRepository).saveAll(List.of(player));
  }

  @Test
  void leavesAHealthyPlayerUntouchedAndDoesNotSaveThem() throws Exception {
    Player player = new Player();
    player.setEspnAthleteId("4428811");
    when(playerRepository.findAll()).thenReturn(List.of(player));
    when(espnInjuryClient.fetchLeagueInjuries()).thenReturn(objectMapper.readTree("{\"injuries\": []}"));

    worker.refresh();

    assertNull(player.getGameStatus());
    verify(playerRepository, Mockito.never()).saveAll(Mockito.anyList());
  }

  @Test
  void doesNotResaveAPlayerWhoseStatusIsUnchangedFromLastRefresh() throws Exception {
    Player player = new Player();
    player.setEspnAthleteId("4428811");
    player.setGameStatus("Questionable");
    player.setGameStatusDetail("hamstring");
    when(playerRepository.findAll()).thenReturn(List.of(player));
    when(espnInjuryClient.fetchLeagueInjuries()).thenReturn(feedFor("4428811", "Questionable", "hamstring"));

    worker.refresh();

    verify(playerRepository, Mockito.never()).saveAll(Mockito.anyList());
  }

  private JsonNode feedFor(String athleteId, String status, String comment) throws Exception {
    return objectMapper.readTree(
        """
        {
          "injuries": [
            {
              "injuries": [
                {
                  "status": "%s",
                  "shortComment": "%s",
                  "longComment": "%s",
                  "athlete": {
                    "links": [
                      {"rel": ["playercard"], "href": "https://www.espn.com/nfl/player/_/id/%s/someone"}
                    ]
                  }
                }
              ]
            }
          ]
        }
        """
            .formatted(status, comment, comment, athleteId));
  }
}

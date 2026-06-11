package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EspnPlayerIngestionServiceTest {

  private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
  private final EspnPlayerClient espnPlayerClient = Mockito.mock(EspnPlayerClient.class);
  private final PlayerUpsertService playerUpsertService = Mockito.mock(PlayerUpsertService.class);
  private final PlayerRefreshService playerRefreshService = Mockito.mock(PlayerRefreshService.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-06-04T00:00:00Z"), ZoneOffset.UTC);
  private final EspnPlayerIngestionService service =
      new EspnPlayerIngestionService(
          playerRepository, espnPlayerClient, playerUpsertService, playerRefreshService, clock);
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void returnsExistingPlayerAndSkipsImmediateRefreshWhenFresh() {
    Player player = new Player();
    player.setId(1L);
    player.setEspnAthleteId("2");
    player.setDisplayName("Jayden Daniels");
    player.setFetchedAt(Instant.parse("2026-06-03T23:30:00Z"));

    when(playerRepository.findFirstByDisplayNameIgnoreCase("Jayden Daniels"))
        .thenReturn(Optional.of(player));

    Player result = service.findOrLoadPlayerByName("Jayden Daniels");

    assertSame(player, result);
    verify(playerRefreshService, never()).refreshPlayerByAthleteIdAsync("2");
  }

  @Test
  void loadsMissingPlayerAndQueuesBackgroundRefresh() throws Exception {
    JsonNode athleteDetailNode =
        objectMapper.readTree(
            """
            {
              "id": "2",
              "displayName": "Jayden Daniels",
              "firstName": "Jayden",
              "lastName": "Daniels",
              "position": {
                "displayName": "QB",
                "abbreviation": "QB"
              },
              "team": {
                "id": "28",
                "displayName": "Washington Commanders"
              },
              "active": true
            }
            """);

    Player loaded = new Player();
    loaded.setId(1L);
    loaded.setEspnAthleteId("2");
    loaded.setDisplayName("Jayden Daniels");
    loaded.setFetchedAt(clock.instant());

    when(playerRepository.findFirstByDisplayNameIgnoreCase("Jayden Daniels")).thenReturn(Optional.empty());
    when(espnPlayerClient.findBestAthleteCandidateByDisplayName("Jayden Daniels"))
        .thenReturn(Optional.of(new AthleteCandidate("2", "Jayden Daniels", "Jayden", "Daniels", "QB", null, "Washington Commanders", "28", true, 0.99d)));
    when(espnPlayerClient.fetchAthleteById("2")).thenReturn(athleteDetailNode);
    when(espnPlayerClient.buildAthleteUrl("2")).thenReturn("https://example.com/2");
    when(playerUpsertService.upsertAthlete(athleteDetailNode, "https://example.com/2"))
        .thenReturn(loaded);

    Player result = service.findOrLoadPlayerByName("Jayden Daniels");

    assertEquals("2", result.getEspnAthleteId());
    verify(playerRefreshService).refreshPlayerByAthleteIdAsync("2");
  }
}

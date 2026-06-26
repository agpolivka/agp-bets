package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.agp.bets.goforbroke.player.repository.PlayerGameStatRepository;
import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PlayerGameStatServiceTest {

  private final PlayerRepository playerRepository = Mockito.mock(PlayerRepository.class);
  private final PlayerGameStatRepository playerGameStatRepository =
      Mockito.mock(PlayerGameStatRepository.class);
  private final EspnPlayerClient espnPlayerClient = Mockito.mock(EspnPlayerClient.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final PlayerGameStatService service =
      new PlayerGameStatService(
          playerRepository, playerGameStatRepository, espnPlayerClient, objectMapper);

  @Test
  void syncStatsForAthleteIdBackfillsMultipleSeasonsWithoutDeletingHistory() throws Exception {
    int currentSeason = Year.now(Clock.systemUTC()).getValue();
    int previousSeason = currentSeason - 1;

    Player player = new Player();
    player.setId(1L);
    player.setEspnAthleteId("4426338");
    player.setDisplayName("Bo Nix");
    player.setPosition("QB");

    JsonNode currentSeasonLog =
        objectMapper.readTree(
            """
            {
              "groups": [
                {
                  "tbls": [
                    {
                      "events": [
                        {
                          "dt": "2026-09-08T20:15:00.000+00:00",
                          "season": 2026,
                          "week": 1,
                          "opp": {
                            "id": "28",
                            "name": "Washington Commanders",
                            "atVs": "vs"
                          },
                          "stats": ["24", "38", "285", "63.2", "7.5", "2", "1", "40", "2", "104.3", "70.0", "3", "15", "5.0", "1", "8"]
                        }
                      ]
                    }
                  ]
                }
              ],
              "labels": [
                { "data": "CMP" },
                { "data": "ATT" },
                { "data": "YDS" },
                { "data": "CMP%" },
                { "data": "AVG" },
                { "data": "TD" },
                { "data": "INT" },
                { "data": "LNG" },
                { "data": "SACK" },
                { "data": "RTG" },
                { "data": "QBR" },
                { "data": "CAR" },
                { "data": "YDS" },
                { "data": "AVG" },
                { "data": "TD" },
                { "data": "LNG" }
              ]
            }
            """);

    JsonNode previousSeasonLog =
        objectMapper.readTree(
            """
            {
              "groups": [
                {
                  "tbls": [
                    {
                      "events": [
                        {
                          "dt": "2025-09-09T20:15:00.000+00:00",
                          "season": 2025,
                          "week": 1,
                          "opp": {
                            "id": "11",
                            "name": "Las Vegas Raiders",
                            "atVs": "@"
                          },
                          "stats": ["18", "28", "215", "64.2", "7.6", "1", "0", "28", "1", "102.5", "69.0", "2", "12", "6.0", "0", "7"]
                        }
                      ]
                    }
                  ]
                }
              ],
              "labels": [
                { "data": "CMP" },
                { "data": "ATT" },
                { "data": "YDS" },
                { "data": "CMP%" },
                { "data": "AVG" },
                { "data": "TD" },
                { "data": "INT" },
                { "data": "LNG" },
                { "data": "SACK" },
                { "data": "RTG" },
                { "data": "QBR" },
                { "data": "CAR" },
                { "data": "YDS" },
                { "data": "AVG" },
                { "data": "TD" },
                { "data": "LNG" }
              ]
            }
            """);

    JsonNode emptyLog =
        objectMapper.readTree(
            """
            {
              "groups": [],
              "items": []
            }
            """);

    PlayerGameStat existing = new PlayerGameStat();
    existing.setId(99L);
    existing.setPlayer(player);
    existing.setGameDate(LocalDate.parse("2025-09-09"));
    existing.setPassingYards(200);
    existing.setCreatedAt(Instant.parse("2025-09-10T00:00:00Z"));
    existing.setUpdatedAt(Instant.parse("2025-09-10T00:00:00Z"));

    when(playerRepository.findByEspnAthleteId("4426338")).thenReturn(Optional.of(player));
    when(espnPlayerClient.fetchAthleteStatisticsLog(eq("4426338"), anyInt()))
        .thenAnswer(
            invocation -> {
              int season = invocation.getArgument(1);
              if (season == currentSeason) {
                return currentSeasonLog;
              }
              if (season == previousSeason) {
                return previousSeasonLog;
              }
              return emptyLog;
            });
    when(espnPlayerClient.buildAthleteStatisticsLogUrl(eq("4426338"), anyInt()))
        .thenAnswer(
            invocation -> {
              int season = invocation.getArgument(1);
              return "https://example.com/" + season;
            });
    when(playerGameStatRepository.findByPlayer_IdAndGameDate(1L, LocalDate.parse("2026-09-08")))
        .thenReturn(Optional.empty());
    when(playerGameStatRepository.findByPlayer_IdAndGameDate(1L, LocalDate.parse("2025-09-09")))
        .thenReturn(Optional.of(existing));
    when(playerGameStatRepository.save(any(PlayerGameStat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(playerGameStatRepository.findAllByPlayer_IdOrderByGameDateDesc(1L))
        .thenAnswer(
            invocation ->
                List.of(
                    buildSavedStat("2026-09-08", 285),
                    buildSavedStat("2025-09-09", 215)));

    List<PlayerGameStat> result = service.syncStatsForAthleteId("4426338");

    assertEquals(2, result.size());
    verify(espnPlayerClient).fetchAthleteStatisticsLog("4426338", currentSeason);
    verify(espnPlayerClient).fetchAthleteStatisticsLog("4426338", previousSeason);
    ArgumentCaptor<PlayerGameStat> captor = ArgumentCaptor.forClass(PlayerGameStat.class);
    verify(playerGameStatRepository, org.mockito.Mockito.times(2)).save(captor.capture());
    assertEquals(285, captor.getAllValues().get(0).getPassingYards());
    assertEquals(215, captor.getAllValues().get(1).getPassingYards());
  }

  private PlayerGameStat buildSavedStat(String gameDate, int passingYards) {
    PlayerGameStat stat = new PlayerGameStat();
    stat.setGameDate(LocalDate.parse(gameDate));
    stat.setPassingYards(passingYards);
    return stat;
  }
}

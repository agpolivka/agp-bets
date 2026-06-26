package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EspnPlayerGameStatMapperTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void mapsQuarterbackGameLogPageIntoGameSnapshots() {
    Player player = new Player();
    player.setPosition("QB");

    List<PlayerGameStatSnapshot> snapshots =
        EspnPlayerGameStatMapper.toSnapshotsFromGameLogPage(
            player,
            buildGameLogHtml(
                """
                {
                  "groups": [
                    {
                      "tbls": [
                        {
                          "events": [
                            {
                              "dt": "2026-09-10T20:25:00.000+00:00",
                              "season": 2026,
                              "week": 1,
                              "opp": {
                                "id": "6",
                                "abbr": "DAL",
                                "name": "Dallas Cowboys",
                                "atVs": "@"
                              },
                              "stats": ["24", "38", "300", "63.2", "7.9", "2", "1", "40", "2", "104.3", "70.0", "3", "15", "5.0", "1", "8", "67"]
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
                    { "data": "LNG" },
                    { "data": "SNAPS" }
                  ]
                }
                """),
            "https://www.espn.com/nfl/player/gamelog/_/id/12483/matthew-stafford",
            Instant.parse("2026-09-11T00:00:00Z"),
            objectMapper);

    assertEquals(1, snapshots.size());

    PlayerGameStatSnapshot snapshot = snapshots.get(0);
    assertEquals("2026-09-10", snapshot.gameDate().toString());
    assertEquals(2026, snapshot.season());
    assertEquals(1, snapshot.week());
    assertEquals("away", snapshot.homeAway());
    assertEquals("Dallas Cowboys", snapshot.opponentName());
    assertEquals("6", snapshot.opponentTeamId());
    assertEquals(300, snapshot.passingYards());
    assertEquals(15, snapshot.rushingYards());
    assertEquals(315, snapshot.totalYards());
    assertEquals(2, snapshot.passingTouchdowns());
    assertEquals(1, snapshot.rushingTouchdowns());
    assertEquals(3, snapshot.touchdowns());
    assertEquals(3, snapshot.totalTouchdowns());
    assertEquals(1, snapshot.interceptions());
    assertNull(snapshot.fumbles());
    assertEquals(1, snapshot.turnovers());
    assertEquals(3, snapshot.carries());
    assertEquals(67, snapshot.snapCount());
  }

  @Test
  void mapsSkillPlayerGameLogPageIntoGameSnapshots() {
    Player player = new Player();
    player.setPosition("WR");

    List<PlayerGameStatSnapshot> snapshots =
        EspnPlayerGameStatMapper.toSnapshotsFromGameLogPage(
            player,
            buildGameLogHtml(
                """
                {
                  "groups": [
                    {
                      "tbls": [
                        {
                          "events": [
                            {
                              "dt": "2026-09-10T20:25:00.000+00:00",
                              "season": 2026,
                              "week": 1,
                              "opp": {
                                "id": "9",
                                "abbr": "NYG",
                                "name": "New York Giants",
                                "atVs": "vs"
                              },
                                "stats": ["7", "9", "101", "14.4", "1", "34", "2", "2", "0", "0", "0", "0", "0", "0", "0", "0", "58", "2"]
                            }
                          ]
                        }
                      ]
                    }
                  ],
                  "labels": [
                    { "data": "REC" },
                    { "data": "TGTS" },
                    { "data": "YDS" },
                    { "data": "AVG" },
                    { "data": "TD" },
                    { "data": "LNG" },
                    { "data": "CAR" },
                    { "data": "YDS" },
                    { "data": "AVG" },
                    { "data": "LNG" },
                    { "data": "TD" },
                    { "data": "FUM" },
                    { "data": "LST" },
                    { "data": "FF" },
                    { "data": "KB" },
                    { "data": "BLOCK" },
                    { "data": "SNAP COUNT" },
                    { "data": "DROPS" }
                  ]
                }
                """),
            "https://www.espn.com/nfl/player/gamelog/_/id/12345/example-receiver",
            Instant.parse("2026-09-11T00:00:00Z"),
            objectMapper);

    assertEquals(1, snapshots.size());

    PlayerGameStatSnapshot snapshot = snapshots.get(0);
    assertEquals("home", snapshot.homeAway());
    assertEquals("New York Giants", snapshot.opponentName());
    assertEquals(7, snapshot.receptions());
    assertEquals(9, snapshot.receivingTargets());
    assertEquals(101, snapshot.receivingYards());
    assertEquals(103, snapshot.totalYards());
    assertEquals(1, snapshot.receivingTouchdowns());
    assertEquals(1, snapshot.totalTouchdowns());
    assertEquals(0, snapshot.fumbles());
    assertEquals(0, snapshot.fumblesLost());
    assertEquals(58, snapshot.snapCount());
    assertEquals(2, snapshot.drops());
  }

  @Test
  void derivesSeasonFromGameDateWhenSeasonIsMissing() {
    Player player = new Player();
    player.setPosition("QB");

    List<PlayerGameStatSnapshot> snapshots =
        EspnPlayerGameStatMapper.toSnapshotsFromGameLogPage(
            player,
            buildGameLogHtml(
                """
                {
                  "groups": [
                    {
                      "tbls": [
                        {
                          "events": [
                            {
                              "dt": "2026-01-10T20:25:00.000+00:00",
                              "week": 19,
                              "opp": {
                                "id": "9",
                                "abbr": "KC",
                                "name": "Kansas City Chiefs",
                                "atVs": "vs"
                              },
                              "stats": ["24", "38", "300", "63.2", "7.9", "2", "1", "40", "2", "104.3", "70.0", "3", "15", "5.0", "1", "8", "67"]
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
                    { "data": "LNG" },
                    { "data": "SNAPS" }
                  ]
                }
                """),
            "https://www.espn.com/nfl/player/gamelog/_/id/12483/matthew-stafford",
            Instant.parse("2026-01-11T00:00:00Z"),
            objectMapper);

    assertEquals(1, snapshots.size());
    assertEquals(2025, snapshots.get(0).season());
  }

  @Test
  void mapsNestedStatisticsLogIntoGameSnapshots() {
    Player player = new Player();
    player.setPosition("QB");

    List<PlayerGameStatSnapshot> snapshots =
        EspnPlayerGameStatMapper.toSnapshots(
            player,
            parse(
                """
                {
                  "statistics": {
                    "splits": [
                      {
                        "dt": "2025-09-14T20:25:00.000+00:00",
                        "season": 2025,
                        "week": 2,
                        "opp": {
                          "id": "28",
                          "name": "Washington Commanders",
                          "atVs": "vs"
                        },
                        "stats": ["22", "31", "245", "71.0", "7.9", "2", "0", "31", "1", "109.4", "71.0", "2", "12", "6.0", "0", "12"]
                      }
                    ]
                  },
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
                """),
            "https://example.com/statisticslog",
            Instant.parse("2025-09-15T00:00:00Z"));

    assertEquals(1, snapshots.size());
    assertEquals("2025-09-14", snapshots.get(0).gameDate().toString());
    assertEquals(245, snapshots.get(0).passingYards());
    assertEquals(12, snapshots.get(0).rushingYards());
  }

  @Test
  void mapsNestedSplitWithEventChildIntoGameSnapshots() {
    Player player = new Player();
    player.setPosition("QB");

    List<PlayerGameStatSnapshot> snapshots =
        EspnPlayerGameStatMapper.toSnapshots(
            player,
            parse(
                """
                {
                  "statistics": {
                    "splits": [
                      {
                        "event": {
                          "dt": "2025-10-01T20:25:00.000+00:00",
                          "opp": {
                            "id": "17",
                            "name": "New York Jets",
                            "atVs": "@"
                          }
                        },
                        "stats": ["20", "30", "220", "66.7", "7.3", "1", "1", "24", "2", "97.4", "66.0", "2", "11", "5.5", "0", "9"]
                      }
                    ]
                  },
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
                """),
            "https://example.com/statisticslog",
            Instant.parse("2025-10-02T00:00:00Z"));

    assertEquals(1, snapshots.size());
    assertEquals("2025-10-01", snapshots.get(0).gameDate().toString());
    assertEquals("New York Jets", snapshots.get(0).opponentName());
    assertEquals(220, snapshots.get(0).passingYards());
    assertEquals(231, snapshots.get(0).totalYards());
  }

  @Test
  void mapsSnapshotIntoEntityWithDefaultGameCount() {
    Player player = new Player();
    PlayerGameStatSnapshot snapshot =
        new PlayerGameStatSnapshot(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "https://example.com",
            "{}",
            Instant.parse("2026-09-11T00:00:00Z"));

    PlayerGameStat entity =
        EspnPlayerGameStatMapper.toEntity(player, snapshot, Instant.parse("2026-09-12T00:00:00Z"));

    assertEquals(1, entity.getGamesPlayed());
    assertTrue(entity.getCreatedAt().isBefore(entity.getUpdatedAt().plusSeconds(1)));
  }

  private String buildGameLogHtml(String gmlogJson) {
    return "<html><script>window.__TEST__={\"gmlog\":" + gmlogJson + "};</script></html>";
  }

  private JsonNode parse(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}

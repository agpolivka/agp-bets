package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EspnAthleteMapperTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void findsAthleteByDisplayNameInNestedListResponse() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            """
            {
              "items": [
                {
                  "id": "1",
                  "displayName": "Someone Else"
                },
                {
                  "id": "2",
                  "displayName": "Jayden Daniels",
                  "firstName": "Jayden",
                  "lastName": "Daniels",
                  "position": {
                    "displayName": "QB"
                  },
                  "jersey": "5",
                  "team": {
                    "id": "28",
                    "displayName": "Washington Commanders"
                  },
                  "active": true
                }
              ]
            }
            """);

    Optional<JsonNode> athlete = EspnAthleteMapper.findAthleteByDisplayName(root, "Jayden Daniels");

    assertTrue(athlete.isPresent());
    assertEquals("2", athlete.get().path("id").asText());
  }

  @Test
  void findsFuzzyCandidatesAndRanksTheBestMatchFirst() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            """
            {
              "items": [
                {
                  "id": "1",
                  "displayName": "Aaron Rodgers"
                },
                {
                  "id": "2",
                  "displayName": "Jayden Daniels",
                  "firstName": "Jayden",
                  "lastName": "Daniels",
                  "position": {
                    "displayName": "QB"
                  },
                  "team": {
                    "id": "28",
                    "displayName": "Washington Commanders"
                  },
                  "active": true
                }
              ]
            }
            """);

    List<AthleteCandidate> candidates =
        EspnAthleteMapper.findAthleteCandidates(root, "Jaden Daniles", 5);

    assertEquals(1, candidates.size());
    assertEquals("2", candidates.get(0).espnAthleteId());
    assertEquals("Jayden Daniels", candidates.get(0).displayName());
    assertTrue(candidates.get(0).score() > 0.5d);
  }

  @Test
  void prefersACloseFullNameOverSharedLastNameDecoys() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            """
            {
              "items": [
                {
                  "id": "1",
                  "displayName": "Adam Terry",
                  "firstName": "Adam",
                  "lastName": "Terry"
                },
                {
                  "id": "2",
                  "displayName": "Chris Terry",
                  "firstName": "Chris",
                  "lastName": "Terry"
                },
                {
                  "id": "3",
                  "displayName": "Terry McLaurin",
                  "firstName": "Terry",
                  "lastName": "McLaurin"
                },
                {
                  "id": "4",
                  "displayName": "D.D. Terry",
                  "firstName": "D.D.",
                  "lastName": "Terry"
                }
              ]
            }
            """);

    List<AthleteCandidate> candidates =
        EspnAthleteMapper.findAthleteCandidates(root, "Terry McClaurin", 10);

    assertEquals("3", candidates.get(0).espnAthleteId());
    assertEquals("Terry McLaurin", candidates.get(0).displayName());
    assertTrue(candidates.get(0).score() > candidates.get(1).score());
  }

  @Test
  void mapsAthleteNodeToPlayerSnapshot() throws Exception {
    JsonNode athleteNode =
        objectMapper.readTree(
            """
            {
              "id": "2",
              "displayName": "Jayden Daniels",
              "firstName": "Jayden",
              "lastName": "Daniels",
              "position": {
                "displayName": "QB"
              },
              "jersey": "5",
              "team": {
                "id": "28",
                "displayName": "Washington Commanders"
              },
              "active": true
            }
            """);

    PlayerSnapshot snapshot =
        EspnAthleteMapper.toSnapshot(
            athleteNode, "https://sports.core.api.espn.com/v3/sports/football/nfl/athletes/2", Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals("2", snapshot.espnAthleteId());
    assertEquals("Jayden Daniels", snapshot.displayName());
    assertEquals("Jayden", snapshot.firstName());
    assertEquals("Daniels", snapshot.lastName());
    assertEquals("QB", snapshot.position());
    assertEquals("5", snapshot.jerseyNumber());
    assertEquals("Washington Commanders", snapshot.teamName());
    assertEquals("28", snapshot.teamId());
    assertEquals(Boolean.TRUE, snapshot.active());
  }

  @Test
  void mapsAthleteProfileNodeToPlayerSnapshot() throws Exception {
    JsonNode profileNode =
        objectMapper.readTree(
            """
            {
              "athlete": {
                "id": "12483",
                "displayName": "Matthew Stafford",
                "firstName": "Matthew",
                "lastName": "Stafford",
                "jersey": "9"
              },
              "position": {
                "id": "8",
                "name": "Quarterback",
                "displayName": "Quarterback",
                "abbreviation": "QB"
              },
              "team": {
                "id": "14",
                "displayName": "Los Angeles Rams",
                "abbreviation": "LAR"
              },
              "active": true
            }
            """);

    PlayerSnapshot snapshot =
        EspnAthleteMapper.toSnapshot(
            profileNode, "https://site.web.api.espn.com/apis/common/v3/sports/football/nfl/athletes/12483", Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals("12483", snapshot.espnAthleteId());
    assertEquals("Matthew Stafford", snapshot.displayName());
    assertEquals("Matthew", snapshot.firstName());
    assertEquals("Stafford", snapshot.lastName());
    assertEquals("QB", snapshot.position());
    assertEquals("9", snapshot.jerseyNumber());
    assertEquals("Los Angeles Rams", snapshot.teamName());
    assertEquals("14", snapshot.teamId());
    assertEquals(Boolean.TRUE, snapshot.active());
  }

  @Test
  void mapsNestedAthleteProfileTeamAndPositionWhenOnlyAthleteObjectContainsThem() throws Exception {
    JsonNode profileNode =
        objectMapper.readTree(
            """
            {
              "athlete": {
                "id": "12483",
                "displayName": "Matthew Stafford",
                "firstName": "Matthew",
                "lastName": "Stafford",
                "jersey": "9",
                "position": {
                  "id": "8",
                  "name": "Quarterback",
                  "displayName": "Quarterback",
                  "abbreviation": "QB"
                },
                "team": {
                  "id": "14",
                  "displayName": "Los Angeles Rams",
                  "abbreviation": "LAR"
                }
              },
              "active": true
            }
            """);

    PlayerSnapshot snapshot =
        EspnAthleteMapper.toSnapshot(
            profileNode, "https://site.web.api.espn.com/apis/common/v3/sports/football/nfl/athletes/12483", Instant.parse("2026-01-01T00:00:00Z"));

    assertEquals("QB", snapshot.position());
    assertEquals("Los Angeles Rams", snapshot.teamName());
    assertEquals("14", snapshot.teamId());
  }
}

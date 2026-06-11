package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EspnPlayerClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void findAthleteByDisplayNameSearchesSubsequentPages() throws Exception {
    List<Integer> requestedPages = new ArrayList<>();

    EspnPlayerClient client =
        new EspnPlayerClient(objectMapper) {
          @Override
          public JsonNode fetchAthletesPage(int page, int limit) {
            requestedPages.add(page);
            return page == 1
                ? parse(
                    """
                    {
                      "pageIndex": 1,
                      "pageCount": 2,
                      "items": [
                        { "id": "1", "displayName": "Aaron Rodgers" }
                      ]
                    }
                    """)
                : parse(
                    """
                    {
                      "items": [
                        { "id": "2", "displayName": "Jayden Daniels" }
                      ]
                    }
                    """);
          }
        };

    Optional<JsonNode> athlete = client.findAthleteByDisplayName("Jayden Daniels");

    assertTrue(athlete.isPresent());
    org.junit.jupiter.api.Assertions.assertEquals("2", athlete.get().path("id").asText());
    org.junit.jupiter.api.Assertions.assertEquals(List.of(1, 2), requestedPages);
  }

  @Test
  void findAthleteCandidatesByDisplayNameSearchesSubsequentPages() throws Exception {
    List<Integer> requestedPages = new ArrayList<>();

    EspnPlayerClient client =
        new EspnPlayerClient(objectMapper) {
          @Override
          public JsonNode fetchAthletesPage(int page, int limit) {
            requestedPages.add(page);
            return page == 1
                ? parse(
                    """
                    {
                      "items": [
                        { "id": "1", "displayName": "Aaron Rodgers" }
                      ]
                    }
                    """)
                : parse(
                    """
                    {
                      "pageIndex": 2,
                      "pageCount": 2,
                      "items": [
                        {
                          "id": "2",
                          "displayName": "Jayden Daniels",
                          "firstName": "Jayden",
                          "lastName": "Daniels",
                          "position": { "displayName": "QB" },
                          "team": { "displayName": "Washington Commanders" }
                        }
                      ]
                    }
                    """);
          }
        };

    List<AthleteCandidate> candidates = client.findAthleteCandidatesByDisplayName("Jaden Daniles", 5);

    assertEquals(1, candidates.size());
    assertEquals("2", candidates.get(0).espnAthleteId());
    assertTrue(candidates.get(0).score() > 0.5d);
    assertEquals(List.of(1, 2), requestedPages);
  }

  @Test
  void buildsAthleteGameLogUrl() {
    EspnPlayerClient client = new EspnPlayerClient(objectMapper);

    assertEquals(
        "https://www.espn.com/nfl/player/gamelog/_/id/12483/matthew-stafford",
        client.buildAthleteGameLogUrl("12483", "Matthew Stafford"));
  }

  private JsonNode parse(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}

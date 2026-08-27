package com.agp.bets.goforbroke.player.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class EspnInjuryMapperTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void parsesAthleteIdFromPlayercardLinkAndAllReportFields() throws Exception {
    // Trimmed down real shape confirmed via a live pull on 2026-08-20 - the athlete object has no
    // "id" field at all, and the date has no seconds component.
    JsonNode root =
        objectMapper.readTree(
            """
            {
              "injuries": [
                {
                  "id": "22",
                  "displayName": "Arizona Cardinals",
                  "injuries": [
                    {
                      "id": "-1987395",
                      "status": "Questionable",
                      "shortComment": "questionable",
                      "longComment": "questionable - hamstring",
                      "date": "2026-08-19T12:07Z",
                      "athlete": {
                        "displayName": "Xavier Weaver",
                        "links": [
                          {
                            "rel": ["playercard", "desktop", "athlete"],
                            "href": "https://www.espn.com/nfl/player/_/id/4428811/xavier-weaver"
                          }
                        ],
                        "headshot": {
                          "href": "https://a.espncdn.com/i/headshots/nfl/players/full/4428811.png"
                        }
                      }
                    }
                  ]
                }
              ]
            }
            """);

    List<InjuryReport> reports = EspnInjuryMapper.parse(root);

    assertEquals(1, reports.size());
    InjuryReport report = reports.get(0);
    assertEquals("4428811", report.espnAthleteId());
    assertEquals("Questionable", report.status());
    assertEquals("questionable", report.shortComment());
    assertEquals("questionable - hamstring", report.longComment());
    assertEquals(Instant.parse("2026-08-19T12:07:00Z"), report.reportedAt());
  }

  @Test
  void fallsBackToHeadshotHrefWhenNoPlayercardLinkPresent() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            """
            {
              "injuries": [
                {
                  "injuries": [
                    {
                      "status": "Out",
                      "athlete": {
                        "links": [
                          {"rel": ["news"], "href": "https://www.espn.com/nfl/player/news/_/id/12345/someone"}
                        ],
                        "headshot": {"href": "https://a.espncdn.com/i/headshots/nfl/players/full/9999.png"}
                      }
                    }
                  ]
                }
              ]
            }
            """);

    List<InjuryReport> reports = EspnInjuryMapper.parse(root);

    assertEquals(1, reports.size());
    assertEquals("9999", reports.get(0).espnAthleteId());
  }

  @Test
  void skipsEntriesMissingBothAnAthleteIdAndAStatus() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            """
            {
              "injuries": [
                {
                  "injuries": [
                    {"status": "Questionable", "athlete": {"links": [], "headshot": null}},
                    {"athlete": {"links": [{"rel": ["playercard"], "href": ".../id/555/x"}]}}
                  ]
                }
              ]
            }
            """);

    List<InjuryReport> reports = EspnInjuryMapper.parse(root);

    assertTrue(reports.isEmpty());
  }

  @Test
  void parseOfNullRootReturnsEmptyList() {
    assertEquals(List.of(), EspnInjuryMapper.parse(null));
  }

  @Test
  void reportedAtIsNullWhenDateFieldIsMissingOrUnparsable() throws Exception {
    JsonNode root =
        objectMapper.readTree(
            """
            {
              "injuries": [
                {
                  "injuries": [
                    {
                      "status": "Doubtful",
                      "athlete": {
                        "links": [{"rel": ["playercard"], "href": "https://www.espn.com/nfl/player/_/id/777/x"}]
                      }
                    }
                  ]
                }
              ]
            }
            """);

    List<InjuryReport> reports = EspnInjuryMapper.parse(root);

    assertEquals(1, reports.size());
    assertNull(reports.get(0).reportedAt());
  }
}

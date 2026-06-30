package com.agp.bets.goforbroke.team.service;

import com.agp.bets.goforbroke.player.service.EspnLookupException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class EspnTeamClient {

  private static final String TEAM_PROFILE_ENDPOINT =
      "https://site.api.espn.com/apis/site/v2/sports/football/nfl/teams";
  private static final String TEAM_SCHEDULE_ENDPOINT =
      "https://site.api.espn.com/apis/site/v2/sports/football/nfl/teams";
  private static final String GAME_SUMMARY_ENDPOINT =
      "https://site.api.espn.com/apis/site/v2/sports/football/nfl/summary";
  private static final String USER_AGENT = "Mozilla/5.0";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public EspnTeamClient(ObjectMapper objectMapper) {
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.objectMapper = objectMapper;
  }

  public JsonNode fetchTeamSchedule(String teamId) {
    return fetchJson(buildTeamScheduleUrl(teamId));
  }

  public JsonNode fetchTeamById(String teamId) {
    return fetchJson(buildTeamUrl(teamId));
  }

  public JsonNode fetchTeamSchedule(String teamId, Integer season) {
    return fetchJson(buildTeamScheduleUrl(teamId, season));
  }

  public JsonNode fetchGameSummary(String eventId) {
    return fetchJson(buildGameSummaryUrl(eventId));
  }

  public String buildTeamUrl(String teamId) {
    return TEAM_PROFILE_ENDPOINT + "/" + teamId;
  }

  public String buildTeamScheduleUrl(String teamId) {
    return TEAM_SCHEDULE_ENDPOINT + "/" + teamId + "/schedule";
  }

  public String buildTeamScheduleUrl(String teamId, Integer season) {
    String url = buildTeamScheduleUrl(teamId);
    if (season != null) {
      url += "?season=" + season;
    }
    return url;
  }

  public String buildGameSummaryUrl(String eventId) {
    return GAME_SUMMARY_ENDPOINT + "?event=" + eventId;
  }

  private JsonNode fetchJson(String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int statusCode = response.statusCode();
      if (statusCode < 200 || statusCode >= 300) {
        throw new EspnLookupException(
            "ESPN request failed with status " + statusCode + " for " + url);
      }
      return objectMapper.readTree(response.body());
    } catch (IOException exception) {
      throw new EspnLookupException("Failed to read ESPN response from " + url, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new EspnLookupException("Interrupted while calling ESPN for " + url, exception);
    }
  }
}

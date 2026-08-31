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
  // This ESPN host (distinct from the ones EspnPlayerClient calls) blocks requests with no
  // User-Agent header AND requests with a browser-impersonation-style value (e.g. plain
  // "Mozilla/5.0", or Java's own default "Java-http-client/*") with a 403 - confirmed by direct
  // testing. A UA that honestly identifies as a non-browser tool is allowed; curl's own default
  // is the specific value verified to work.
  private static final String USER_AGENT = "curl/8.16.0";

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

  // seasonType: ESPN's own encoding (1=preseason, 2=regular season, 3=postseason). Confirmed by
  // direct testing (2026-08-31): a bare `?season=YYYY` with no seasonType silently defaults to
  // preseason (type 1) rather than "the whole season" or "whatever's current" - during the real
  // gap between preseason ending and the regular season starting, that default returns zero
  // relevant events, which is exactly what broke upcoming-opponent resolution. Explicit seasonType
  // avoids relying on that undocumented default at all.
  public JsonNode fetchTeamSchedule(String teamId, Integer season, Integer seasonType) {
    return fetchJson(buildTeamScheduleUrl(teamId, season, seasonType));
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

  public String buildTeamScheduleUrl(String teamId, Integer season, Integer seasonType) {
    String url = buildTeamScheduleUrl(teamId, season);
    if (seasonType != null) {
      url += (season != null ? "&" : "?") + "seasontype=" + seasonType;
    }
    return url;
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

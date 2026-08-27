package com.agp.bets.goforbroke.player.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * League-wide weekly injury/game-status report (Questionable/Doubtful/Out) - genuinely different
 * data from the roster-level {@code athlete.status} field {@link EspnAthleteMapper} already reads
 * from a different endpoint (confirmed directly: a player can show "Active" via that field while
 * being reported "Out" for this week's game here). Same host (site.api.espn.com) as {@link
 * com.agp.bets.goforbroke.team.service.EspnTeamClient}, which already found this host 403s a bare
 * "Mozilla/5.0" or Java's default User-Agent but allows "curl/8.16.0" - confirmed live for this
 * endpoint too before building against it.
 */
@Component
public class EspnInjuryClient {

  private static final String INJURIES_ENDPOINT =
      "https://site.api.espn.com/apis/site/v2/sports/football/nfl/injuries";
  private static final String USER_AGENT = "curl/8.16.0";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public EspnInjuryClient(ObjectMapper objectMapper) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.objectMapper = objectMapper;
  }

  /** The full league response, grouped by team ({@code injuries[].injuries[]}) - see {@link EspnInjuryMapper}. */
  public JsonNode fetchLeagueInjuries() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(INJURIES_ENDPOINT))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      int statusCode = response.statusCode();
      if (statusCode < 200 || statusCode >= 300) {
        throw new EspnLookupException(
            "ESPN request failed with status " + statusCode + " for " + INJURIES_ENDPOINT);
      }
      return objectMapper.readTree(response.body());
    } catch (IOException exception) {
      throw new EspnLookupException("Failed to read ESPN response from " + INJURIES_ENDPOINT, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new EspnLookupException("Interrupted while calling ESPN for " + INJURIES_ENDPOINT, exception);
    }
  }
}

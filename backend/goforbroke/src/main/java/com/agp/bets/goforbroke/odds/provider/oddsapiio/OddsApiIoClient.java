package com.agp.bets.goforbroke.odds.provider.oddsapiio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Raw HTTP + JSON client for odds-api.io. All odds-api.io-specific URL/JSON shape knowledge stays
 * inside this package - {@link OddsApiIoPropOddsProvider} is the only caller, and it translates
 * everything into the provider-agnostic {@code odds.provider} types before returning.
 *
 * <p>Unlike ESPN's undocumented endpoints (see {@code EspnTeamClient}'s User-Agent gotcha),
 * odds-api.io is a commercial API with no known User-Agent sensitivity - but that hasn't been
 * empirically verified against Java's {@link HttpClient} specifically (only against curl during
 * manual testing), so a failure here should be double-checked against the User-Agent first.
 */
@Component
public class OddsApiIoClient {

  private static final String USER_AGENT = "agp-bets-backend/1.0";
  private static final String SPORT = "american-football";

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String apiKey;

  public OddsApiIoClient(
      ObjectMapper objectMapper,
      @Value("${agp.odds-api-io.base-url:https://api.odds-api.io/v3}") String baseUrl,
      @Value("${agp.odds-api-io.api-key:}") String apiKey) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.objectMapper = objectMapper;
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
  }

  public JsonNode fetchHistoricalEvents(String league, Instant from, Instant to) {
    String url =
        baseUrl
            + "/historical/events?apiKey="
            + encode(apiKey)
            + "&sport="
            + encode(SPORT)
            + "&league="
            + encode(league)
            + "&from="
            + encode(from.toString())
            + "&to="
            + encode(to.toString());
    return fetchJson(url);
  }

  public JsonNode fetchHistoricalOdds(String externalEventId, List<String> bookmakers) {
    String url =
        baseUrl
            + "/historical/odds?apiKey="
            + encode(apiKey)
            + "&eventId="
            + encode(externalEventId)
            + "&bookmakers="
            + encode(String.join(",", bookmakers));
    return fetchJson(url);
  }

  public String buildHistoricalOddsUrl(String externalEventId, List<String> bookmakers) {
    return baseUrl
        + "/historical/odds?eventId="
        + encode(externalEventId)
        + "&bookmakers="
        + encode(String.join(",", bookmakers));
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
        throw new OddsApiIoLookupException(
            "odds-api.io request failed with status " + statusCode + " for " + redactKey(url));
      }
      return objectMapper.readTree(response.body());
    } catch (IOException exception) {
      throw new OddsApiIoLookupException(
          "Failed to read odds-api.io response from " + redactKey(url), exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new OddsApiIoLookupException(
          "Interrupted while calling odds-api.io for " + redactKey(url), exception);
    }
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String redactKey(String url) {
    return url.replace(apiKey, "***");
  }
}

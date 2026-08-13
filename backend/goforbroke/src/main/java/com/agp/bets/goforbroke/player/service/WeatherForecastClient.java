package com.agp.bets.goforbroke.player.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Live kickoff-time weather forecast for a team's home stadium, via Open-Meteo
 * (open-meteo.com) - free, no API key, hourly forecasts up to 16 days out. Confirmed live during
 * this session before building against it (real endpoint shape, real 16-day range).
 *
 * <p>Only meaningful for outdoor/open-roof games with a kickoff inside the forecastable window -
 * callers are expected to skip this entirely for dome/closed games and to treat "no forecast yet"
 * (game too far out, or the call fails) as "no weather adjustment" rather than an error. This is a
 * live-path-only concern: historical weather for backtesting comes from already-stored
 * {@code PlayerGameStat} columns (real recorded conditions), never this client.
 */
@Component
public class WeatherForecastClient {

  private static final Logger log = LoggerFactory.getLogger(WeatherForecastClient.class);
  private static final String FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast";
  private static final int MAX_FORECAST_DAYS = 16;
  // Forecasts change as a game approaches; short enough to stay reasonably fresh, long enough that
  // repeated prediction requests for the same upcoming game don't hit the API every time.
  private static final Duration CACHE_TTL = Duration.ofHours(3);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final Map<String, CachedForecast> cache = new ConcurrentHashMap<>();

  public WeatherForecastClient(ObjectMapper objectMapper) {
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.objectMapper = objectMapper;
  }

  public record Forecast(double temperatureFahrenheit, double windMph, double precipitationProbabilityPercent) {}

  /**
   * Forecast for {@code teamAbbreviation}'s home stadium at {@code kickoff}. Returns null (not an
   * exception) whenever a real forecast isn't obtainable - unknown team, kickoff outside the
   * 16-day window, or a request failure - so callers can treat this uniformly as "no adjustment"
   * rather than needing their own try/catch around every call site.
   */
  public Forecast forecastFor(String teamAbbreviation, Instant kickoff) {
    if (teamAbbreviation == null || kickoff == null) {
      return null;
    }

    NflStadiumCoordinates.Coordinates coordinates = NflStadiumCoordinates.forAbbreviation(teamAbbreviation);
    if (coordinates == null) {
      return null;
    }

    Instant now = Instant.now();
    if (kickoff.isBefore(now) || kickoff.isAfter(now.plus(MAX_FORECAST_DAYS, ChronoUnit.DAYS))) {
      return null;
    }

    // Cache key rounds kickoff to the hour - repeated calls for the same game within the TTL reuse
    // one cached response instead of re-fetching an identical forecast.
    String cacheKey = teamAbbreviation + "|" + kickoff.truncatedTo(ChronoUnit.HOURS);
    CachedForecast cached = cache.get(cacheKey);
    if (cached != null && !cached.isExpired(now)) {
      return cached.forecast();
    }

    Forecast forecast = fetchForecast(coordinates, kickoff);
    if (forecast != null) {
      cache.put(cacheKey, new CachedForecast(forecast, now.plus(CACHE_TTL)));
    }
    return forecast;
  }

  private Forecast fetchForecast(NflStadiumCoordinates.Coordinates coordinates, Instant kickoff) {
    String url =
        FORECAST_ENDPOINT
            + "?latitude="
            + coordinates.latitude()
            + "&longitude="
            + coordinates.longitude()
            + "&hourly=temperature_2m,wind_speed_10m,precipitation_probability"
            + "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=UTC&forecast_days="
            + MAX_FORECAST_DAYS;

    try {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(url))
              .timeout(Duration.ofSeconds(10))
              .header("Accept", "application/json")
              .GET()
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        log.warn("Weather forecast request failed with status {} for {}", response.statusCode(), url);
        return null;
      }

      return parseClosestHour(objectMapper.readTree(response.body()), kickoff);
    } catch (IOException exception) {
      log.warn("Failed to fetch weather forecast: {}", exception.getMessage());
      return null;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while fetching weather forecast");
      return null;
    }
  }

  private Forecast parseClosestHour(JsonNode root, Instant kickoff) {
    JsonNode hourly = root.path("hourly");
    JsonNode times = hourly.path("time");
    JsonNode temps = hourly.path("temperature_2m");
    JsonNode winds = hourly.path("wind_speed_10m");
    JsonNode precipitation = hourly.path("precipitation_probability");
    if (!times.isArray() || times.isEmpty()) {
      return null;
    }

    int closestIndex = -1;
    long closestDiffSeconds = Long.MAX_VALUE;
    for (int i = 0; i < times.size(); i++) {
      Instant candidate = parseHourlyTimestamp(times.get(i).asText(null));
      if (candidate == null) {
        continue;
      }
      long diff = Math.abs(Duration.between(candidate, kickoff).getSeconds());
      if (diff < closestDiffSeconds) {
        closestDiffSeconds = diff;
        closestIndex = i;
      }
    }

    if (closestIndex < 0) {
      return null;
    }

    return new Forecast(
        temps.path(closestIndex).asDouble(Double.NaN),
        winds.path(closestIndex).asDouble(Double.NaN),
        precipitation.path(closestIndex).asDouble(Double.NaN));
  }

  // Open-Meteo's hourly.time entries look like "2026-08-13T14:00" (no offset - already UTC since
  // the request asked for timezone=UTC).
  private Instant parseHourlyTimestamp(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(value + ":00Z").toInstant();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private record CachedForecast(Forecast forecast, Instant expiresAt) {
    boolean isExpired(Instant now) {
      return now.isAfter(expiresAt);
    }
  }
}

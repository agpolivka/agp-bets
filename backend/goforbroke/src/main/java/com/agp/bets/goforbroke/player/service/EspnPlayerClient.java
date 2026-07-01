package com.agp.bets.goforbroke.player.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class EspnPlayerClient {

  private static final String ATHLETES_ENDPOINT =
      "https://sports.core.api.espn.com/v3/sports/football/nfl/athletes";
  private static final String ATHLETE_PROFILE_ENDPOINT =
      "https://site.web.api.espn.com/apis/common/v3/sports/football/nfl/athletes";
  private static final String ATHLETE_STATISTICS_LOG_ENDPOINT =
      "https://site.web.api.espn.com/apis/common/v3/sports/football/nfl/athletes";
  private static final String ATHLETE_GAME_LOG_PAGE =
      "https://www.espn.com/nfl/player/gamelog/_/id";
  private static final String USER_AGENT = "Mozilla/5.0";
  private static final int ATHLETE_PAGE_LIMIT = 1000;
  private static final int MAX_ATHLETE_PAGES = 25;
  private static final Set<String> ALLOWED_SEARCH_POSITIONS =
      Set.of("QB", "RB", "FB", "WR", "TE");

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;

  public EspnPlayerClient(ObjectMapper objectMapper) {
    this.httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    this.objectMapper = objectMapper;
  }

  public JsonNode fetchAthletesPage(int page, int limit) {
    String url = ATHLETES_ENDPOINT + "?limit=" + limit + "&page=" + page;
    return fetchJson(url);
  }

  public JsonNode fetchAthleteById(String athleteId) {
    return fetchJson(ATHLETE_PROFILE_ENDPOINT + "/" + athleteId);
  }

  public JsonNode fetchAthleteStatisticsLog(String athleteId) {
    return fetchAthleteStatisticsLog(athleteId, null);
  }

  public JsonNode fetchAthleteStatisticsLog(String athleteId, Integer season) {
    return fetchJson(buildAthleteStatisticsLogUrl(athleteId, season));
  }

  public String fetchAthleteGameLogPage(String athleteId, String displayName) {
    return fetchAthleteGameLogPage(athleteId, displayName, null);
  }

  public String fetchAthleteGameLogPage(String athleteId, String displayName, Integer season) {
    return fetchText(buildAthleteGameLogUrl(athleteId, displayName, season));
  }

  public Optional<JsonNode> findAthleteByDisplayName(String playerName) {
    for (int page = 1; page <= MAX_ATHLETE_PAGES; page++) {
      JsonNode athletes = fetchAthletesPage(page, ATHLETE_PAGE_LIMIT);
      Optional<JsonNode> athlete = EspnAthleteMapper.findAthleteByDisplayName(athletes, playerName);
      if (athlete.isPresent()) {
        return athlete;
      }

      if (isLastPage(athletes)) {
        break;
      }
    }

    return Optional.empty();
  }

  public Optional<AthleteCandidate> findBestAthleteCandidateByDisplayName(String playerName) {
    return findAthleteCandidatesByDisplayName(playerName, 1).stream().findFirst();
  }

  public List<AthleteCandidate> findAthleteCandidatesByDisplayName(String playerName, int maxCandidates) {
    int requestedLimit = Math.max(1, maxCandidates);
    Map<String, AthleteCandidate> candidatesById = new HashMap<>();
    String normalizedQuery = normalize(playerName);
    boolean foundExactMatch = false;

    for (int page = 1; page <= MAX_ATHLETE_PAGES; page++) {
      JsonNode athletes = fetchAthletesPage(page, ATHLETE_PAGE_LIMIT);
      List<AthleteCandidate> pageCandidates =
          EspnAthleteMapper.findAthleteCandidates(athletes, playerName, ATHLETE_PAGE_LIMIT);

      pageCandidates
          .forEach(
              candidate ->
                  candidatesById.merge(
                      candidate.espnAthleteId(),
                      candidate,
                      (left, right) -> right.score() >= left.score() ? right : left));

      foundExactMatch =
          foundExactMatch
              || pageCandidates.stream()
                  .anyMatch(candidate -> isExactMatch(candidate, normalizedQuery));

      if (isLastPage(athletes)) {
        break;
      }

      if (foundExactMatch) {
        break;
      }
    }

    return candidatesById.values().stream()
        .sorted(
            Comparator.comparingDouble(AthleteCandidate::score)
                .reversed()
                .thenComparing(AthleteCandidate::displayName, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(AthleteCandidate::espnAthleteId))
        .limit(requestedLimit)
        .map(this::enrichCandidateFromAthleteDetails)
        .filter(candidate -> candidate != null && isAllowedSearchPosition(candidate.position()))
        .toList();
  }

  public String buildAthleteUrl(String athleteId) {
    return ATHLETE_PROFILE_ENDPOINT + "/" + athleteId;
  }

  public String buildAthleteStatisticsLogUrl(String athleteId) {
    return buildAthleteStatisticsLogUrl(athleteId, null);
  }

  public String buildAthleteStatisticsLogUrl(String athleteId, Integer season) {
    String url = ATHLETE_STATISTICS_LOG_ENDPOINT + "/" + athleteId + "/statisticslog";
    if (season != null) {
      url += "?season=" + season;
    }
    return url;
  }

  public String buildAthleteGameLogUrl(String athleteId, String displayName) {
    return buildAthleteGameLogUrl(athleteId, displayName, null);
  }

  public String buildAthleteGameLogUrl(String athleteId, String displayName, Integer season) {
    String slug = slugify(displayName);
    String url = ATHLETE_GAME_LOG_PAGE + "/" + athleteId + "/" + slug;
    if (season != null) {
      url += "?season=" + season;
    }
    return url;
  }

  private AthleteCandidate enrichCandidateFromAthleteDetails(AthleteCandidate candidate) {
    if (candidate == null) {
      return null;
    }

    if (candidate.position() != null && candidate.teamName() != null && candidate.teamId() != null) {
      return candidate;
    }

    try {
      JsonNode athlete = fetchAthleteById(candidate.espnAthleteId());
      AthleteCandidate enriched = EspnAthleteMapper.toCandidate(athlete, candidate.score());
      return enriched;
    } catch (EspnLookupException exception) {
      return candidate;
    }
  }

  private boolean isAllowedSearchPosition(String position) {
    if (position == null || position.isBlank()) {
      return false;
    }

    return ALLOWED_SEARCH_POSITIONS.contains(position.trim().toUpperCase());
  }

  private boolean isExactMatch(AthleteCandidate candidate, String normalizedQuery) {
    if (candidate == null || normalizedQuery == null || normalizedQuery.isBlank()) {
      return false;
    }

    return normalizedQuery.equals(normalize(candidate.displayName()))
        || normalizedQuery.equals(normalize(candidate.firstName() + " " + candidate.lastName()))
        || normalizedQuery.equals(normalize(candidate.lastName() + " " + candidate.firstName()));
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

  private String fetchText(String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "text/html,application/xhtml+xml")
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
      return response.body();
    } catch (IOException exception) {
      throw new EspnLookupException("Failed to read ESPN response from " + url, exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new EspnLookupException("Interrupted while calling ESPN for " + url, exception);
    }
  }

  private boolean isLastPage(JsonNode athletesPage) {
    JsonNode items = athletesPage.path("items");
    if (items.isArray() && items.isEmpty()) {
      return true;
    }

    if (athletesPage.hasNonNull("pageIndex") && athletesPage.hasNonNull("pageCount")) {
      return athletesPage.path("pageIndex").asInt() >= athletesPage.path("pageCount").asInt();
    }

    return false;
  }

  private String slugify(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }

    return value.toLowerCase()
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("^-+|-+$", "");
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }

    return value.trim()
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9\\s]", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }
}

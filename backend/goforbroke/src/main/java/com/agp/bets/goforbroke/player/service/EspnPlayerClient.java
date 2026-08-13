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
  private static final String SITE_SEARCH_ENDPOINT = "https://site.api.espn.com/apis/search/v2";
  private static final String USER_AGENT = "Mozilla/5.0";
  // site.api.espn.com (used only by searchAthletesByQuery below) is the same host EspnTeamClient
  // already found blocks a bare Mozilla/5.0 UA with a 403 - confirmed directly this session that
  // this specific endpoint does the same. Deliberately a separate constant from USER_AGENT above,
  // since sports.core.api.espn.com/site.web.api.espn.com (everything else in this class) work
  // fine with Mozilla/5.0 and are NOT interchangeable with this host's requirements.
  private static final String SITE_SEARCH_USER_AGENT = "curl/8.16.0";
  private static final int ATHLETE_PAGE_LIMIT = 1000;
  private static final int MAX_ATHLETE_PAGES = 25;
  private static final java.util.regex.Pattern UID_ATHLETE_ID = java.util.regex.Pattern.compile("a:(\\d+)");
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

  /**
   * Live-search fallback for a player not found locally, using ESPN's general site-search
   * endpoint rather than the bulk `sports.core.api.espn.com/.../athletes` catalog (which paginates
   * every athlete and is comparatively fragile/slow - and, as of this session, was found to be
   * returning corrupted data). This endpoint returns fewer fields per result (no position/jersey),
   * so candidates from here are intentionally partial - callers needing full details already have
   * an enrichment path via {@link #fetchAthleteById}.
   */
  public List<AthleteCandidate> searchAthletesByQuery(String name, int limit) {
    if (name == null || name.isBlank() || limit <= 0) {
      return List.of();
    }

    String url =
        SITE_SEARCH_ENDPOINT
            + "?query="
            + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
            + "&limit="
            + limit;
    JsonNode root = fetchSiteSearchJson(url);
    JsonNode playerResults = findPlayerResultsNode(root);
    if (playerResults == null) {
      return List.of();
    }

    List<AthleteCandidate> candidates = new java.util.ArrayList<>();
    for (JsonNode content : playerResults.path("contents")) {
      String athleteId = extractAthleteId(textOrNull(content, "uid"));
      String displayName = textOrNull(content, "displayName");
      if (athleteId == null || displayName == null) {
        continue;
      }

      double score =
          com.agp.bets.goforbroke.common.text.NameSimilarity.similarity(name, displayName);
      candidates.add(
          new AthleteCandidate(
              athleteId,
              displayName,
              null,
              null,
              null,
              null,
              textOrNull(content, "subtitle"),
              null,
              null,
              score));
    }

    return candidates.stream()
        .sorted(Comparator.comparingDouble(AthleteCandidate::score).reversed())
        .limit(limit)
        .toList();
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

  private JsonNode fetchSiteSearchJson(String url) {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/json")
            .header("User-Agent", SITE_SEARCH_USER_AGENT)
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

  private JsonNode findPlayerResultsNode(JsonNode root) {
    if (root == null) {
      return null;
    }

    for (JsonNode result : root.path("results")) {
      if ("player".equals(textOrNull(result, "type"))) {
        return result;
      }
    }
    return null;
  }

  private String extractAthleteId(String uid) {
    if (uid == null) {
      return null;
    }
    java.util.regex.Matcher matcher = UID_ATHLETE_ID.matcher(uid);
    return matcher.find() ? matcher.group(1) : null;
  }

  private String textOrNull(JsonNode node, String field) {
    if (node == null) {
      return null;
    }
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.isMissingNode()) {
      return null;
    }
    String text = value.asText(null);
    return text == null || text.isBlank() ? null : text;
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

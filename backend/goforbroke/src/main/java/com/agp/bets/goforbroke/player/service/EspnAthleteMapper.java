package com.agp.bets.goforbroke.player.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class EspnAthleteMapper {

  private static final double MIN_CANDIDATE_SCORE = 0.35d;

  private EspnAthleteMapper() {}

  public static Optional<JsonNode> findAthleteByDisplayName(JsonNode root, String playerName) {
    if (root == null || root.isNull() || playerName == null || playerName.isBlank()) {
      return Optional.empty();
    }

    String target = normalize(playerName);
    return findMatchingNode(root, node -> matchesDisplayName(node, target));
  }

  public static List<AthleteCandidate> findAthleteCandidates(
      JsonNode root, String playerName, int maxCandidates) {
    if (root == null || root.isNull() || playerName == null || playerName.isBlank() || maxCandidates <= 0) {
      return List.of();
    }

    String query = normalize(playerName);
    Map<String, AthleteCandidate> candidatesById = new HashMap<>();
    collectCandidateNodes(root, node -> {
      Optional<AthleteCandidate> candidate = toCandidate(node, query);
      candidate.ifPresent(
          athleteCandidate ->
              candidatesById.merge(
                  athleteCandidate.espnAthleteId(),
                  athleteCandidate,
                  (left, right) -> right.score() >= left.score() ? right : left));
    });

    return candidatesById.values().stream()
        .sorted(
            Comparator.comparingDouble(AthleteCandidate::score)
                .reversed()
                .thenComparing(AthleteCandidate::displayName, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(AthleteCandidate::espnAthleteId))
        .limit(maxCandidates)
        .toList();
  }

  public static PlayerSnapshot toSnapshot(JsonNode athleteNode, String sourceUrl, Instant fetchedAt) {
    JsonNode profileNode = athleteNode.hasNonNull("athlete") ? athleteNode.path("athlete") : athleteNode;
    JsonNode contextNode = athleteNode;
    String athleteId = requireText(profileNode, "id");
    String displayName =
        firstText(
                profileNode,
                "displayName",
                "fullName",
                "shortName",
                "name")
            .orElse(athleteId);

    String firstName = text(profileNode, "firstName");
    String lastName = text(profileNode, "lastName");
    String position =
        firstText(contextNode, "position")
            .orElse(nestedText(contextNode, "position", "abbreviation", "shortDisplayName", "displayName", "name"));
    String jerseyNumber = firstText(profileNode, "jersey", "jerseyNumber").orElse(null);
    String teamName = nestedText(contextNode, "team", "displayName", "shortDisplayName", "name", "location");
    String teamId = nestedText(contextNode, "team", "id");
    Boolean active = contextNode.hasNonNull("active") ? contextNode.path("active").asBoolean() : null;

    return new PlayerSnapshot(
        athleteId,
        displayName,
        firstName,
        lastName,
        position,
        jerseyNumber,
        teamName,
        teamId,
        active,
        sourceUrl,
        athleteNode.toString(),
        fetchedAt);
  }

  private static Optional<AthleteCandidate> toCandidate(JsonNode athleteNode, String query) {
    String athleteId = text(athleteNode, "id");
    if (athleteId == null) {
      return Optional.empty();
    }

    String displayName =
        firstText(athleteNode, "displayName", "fullName", "shortName", "name").orElse(null);
    String firstName = text(athleteNode, "firstName");
    String lastName = text(athleteNode, "lastName");
    String position = nestedText(athleteNode, "position", "displayName", "abbreviation", "name");
    String jerseyNumber = firstText(athleteNode, "jersey", "jerseyNumber").orElse(null);
    String teamName = nestedText(athleteNode, "team", "displayName", "shortDisplayName", "name");
    String teamId = nestedText(athleteNode, "team", "id");
    Boolean active = athleteNode.hasNonNull("active") ? athleteNode.path("active").asBoolean() : null;

    double score = scoreCandidate(athleteNode, query);
    if (score < MIN_CANDIDATE_SCORE) {
      return Optional.empty();
    }

    String resolvedDisplayName =
        displayName != null ? displayName : buildDisplayName(firstName, lastName, athleteId);

    return Optional.of(
        new AthleteCandidate(
            athleteId,
            resolvedDisplayName,
            firstName,
            lastName,
            position,
            jerseyNumber,
            teamName,
            teamId,
            active,
            score));
  }

  private static double scoreCandidate(JsonNode athleteNode, String query) {
    Set<String> candidateTerms = new HashSet<>();
    firstText(athleteNode, "displayName", "fullName", "shortName", "name")
        .ifPresent(candidateTerms::add);
    firstText(athleteNode, "firstName").ifPresent(candidateTerms::add);
    firstText(athleteNode, "lastName").ifPresent(candidateTerms::add);

    String firstName = text(athleteNode, "firstName");
    String lastName = text(athleteNode, "lastName");
    if (firstName != null && lastName != null) {
      candidateTerms.add(firstName + " " + lastName);
      candidateTerms.add(lastName + " " + firstName);
    }

    double bestScore = 0.0d;
    for (String candidateTerm : candidateTerms) {
      bestScore = Math.max(bestScore, scoreText(query, normalize(candidateTerm)));
    }

    return bestScore;
  }

  private static double scoreText(String query, String candidate) {
    if (candidate == null || candidate.isBlank()) {
      return 0.0d;
    }

    String normalizedQuery = compact(query);
    String normalizedCandidate = compact(candidate);
    if (normalizedQuery.isBlank() || normalizedCandidate.isBlank()) {
      return 0.0d;
    }

    if (normalizedCandidate.equals(normalizedQuery)) {
      return 1.0d;
    }

    if (candidate.contains(query) || query.contains(candidate)) {
      return 0.96d;
    }

    double tokenScore = tokenOverlapScore(query, candidate);
    double editScore = normalizedLevenshteinScore(normalizedQuery, normalizedCandidate);
    return Math.max(tokenScore, editScore);
  }

  private static double tokenOverlapScore(String query, String candidate) {
    String[] queryTokens = tokenize(query);
    String[] candidateTokens = tokenize(candidate);
    if (queryTokens.length == 0 || candidateTokens.length == 0) {
      return 0.0d;
    }

    Set<String> candidateSet = new HashSet<>(List.of(candidateTokens));
    int matches = 0;
    for (String token : queryTokens) {
      if (candidateSet.contains(token)) {
        matches++;
      }
    }

    return (double) matches / Math.max(queryTokens.length, candidateTokens.length);
  }

  private static double normalizedLevenshteinScore(String left, String right) {
    int maxLength = Math.max(left.length(), right.length());
    if (maxLength == 0) {
      return 0.0d;
    }

    int distance = levenshteinDistance(left, right);
    return 1.0d - ((double) distance / maxLength);
  }

  private static int levenshteinDistance(String left, String right) {
    int[] previous = new int[right.length() + 1];
    int[] current = new int[right.length() + 1];

    for (int j = 0; j <= right.length(); j++) {
      previous[j] = j;
    }

    for (int i = 1; i <= left.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= right.length(); j++) {
        int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
        current[j] =
            Math.min(
                Math.min(current[j - 1] + 1, previous[j] + 1),
                previous[j - 1] + cost);
      }

      int[] swap = previous;
      previous = current;
      current = swap;
    }

    return previous[right.length()];
  }

  private static String[] tokenize(String value) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      return new String[0];
    }

    return normalized.split("\\s+");
  }

  private static String compact(String value) {
    return value == null ? "" : value.replaceAll("\\s+", "");
  }

  private static String buildDisplayName(String firstName, String lastName, String fallback) {
    if (firstName != null && lastName != null) {
      return firstName + " " + lastName;
    }
    if (firstName != null) {
      return firstName;
    }
    if (lastName != null) {
      return lastName;
    }
    return fallback;
  }

  private static void collectCandidateNodes(JsonNode node, NodeConsumer consumer) {
    if (node == null || node.isNull()) {
      return;
    }

    if (node.isArray()) {
      for (JsonNode child : node) {
        collectCandidateNodes(child, consumer);
      }
      return;
    }

    if (node.isObject()) {
      if (looksLikeCandidate(node)) {
        consumer.accept(node);
      }

      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        JsonNode value = fields.next().getValue();
        if (value.isArray()) {
          collectCandidateNodes(value, consumer);
        }
      }
    }
  }

  private static boolean looksLikeCandidate(JsonNode node) {
    return text(node, "id") != null
        && firstText(node, "displayName", "fullName", "shortName", "name").isPresent();
  }

  private static Optional<JsonNode> findMatchingNode(JsonNode node, NodePredicate predicate) {
    if (node == null || node.isNull()) {
      return Optional.empty();
    }

    if (node.isObject()) {
      if (predicate.test(node)) {
        return Optional.of(node);
      }

      Iterator<JsonNode> fields = node.elements();
      while (fields.hasNext()) {
        Optional<JsonNode> match = findMatchingNode(fields.next(), predicate);
        if (match.isPresent()) {
          return match;
        }
      }
      return Optional.empty();
    }

    if (node.isArray()) {
      for (JsonNode child : node) {
        Optional<JsonNode> match = findMatchingNode(child, predicate);
        if (match.isPresent()) {
          return match;
        }
      }
    }

    return Optional.empty();
  }

  private static boolean matchesDisplayName(JsonNode node, String target) {
    return matchesAny(node, target, "displayName", "fullName", "shortName", "name");
  }

  private static boolean matchesAny(JsonNode node, String target, String... fields) {
    for (String field : fields) {
      String value = text(node, field);
      if (value != null && normalize(value).equals(target)) {
        return true;
      }
    }
    return false;
  }

  private static String nestedText(JsonNode node, String childField, String... nestedFields) {
    JsonNode child = node.path(childField);
    if (child.isMissingNode() || child.isNull()) {
      return null;
    }
    for (String nestedField : nestedFields) {
      String value = text(child, nestedField);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static Optional<String> firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      String value = text(node, field);
      if (value != null && !value.isBlank()) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.isMissingNode()) {
      return null;
    }
    String text = value.asText(null);
    return text == null || text.isBlank() ? null : text;
  }

  private static String requireText(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null) {
      throw new IllegalArgumentException("Missing required field '" + field + "'");
    }
    return value;
  }

  private static String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
  }

  @FunctionalInterface
  private interface NodePredicate {
    boolean test(JsonNode node);
  }

  @FunctionalInterface
  private interface NodeConsumer {
    void accept(JsonNode node);
  }
}

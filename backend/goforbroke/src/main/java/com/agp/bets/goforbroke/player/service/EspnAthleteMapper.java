package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.common.text.NameSimilarity;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    String position = resolvePosition(contextNode, profileNode);
    String jerseyNumber = firstText(profileNode, "jersey", "jerseyNumber").orElse(null);
    String teamName = resolveTeamName(contextNode, profileNode);
    String teamId = resolveTeamId(contextNode, profileNode);
    Boolean active = contextNode.hasNonNull("active") ? contextNode.path("active").asBoolean() : null;
    String injuryStatus = resolveInjuryStatus(profileNode);

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
        injuryStatus,
        sourceUrl,
        athleteNode.toString(),
        fetchedAt);
  }

  // ESPN's per-athlete "status" object (e.g. {"name": "Active", "type": "active", ...}) -
  // present on the full athlete-profile endpoint (fetchAthleteById), not the lighter search/
  // candidate list endpoints, so this is only resolved in toSnapshot(), not toCandidate().
  private static String resolveInjuryStatus(JsonNode profileNode) {
    return text(profileNode.path("status"), "name");
  }

  public static AthleteCandidate toCandidate(JsonNode athleteNode, double score) {
    if (athleteNode == null || athleteNode.isNull()) {
      throw new IllegalArgumentException("athleteNode must not be null");
    }

    JsonNode profileNode = athleteNode.hasNonNull("athlete") ? athleteNode.path("athlete") : athleteNode;
    String athleteId = requireText(profileNode, "id");
    String displayName =
        firstText(profileNode, "displayName", "fullName", "shortName", "name")
            .orElseGet(() -> buildDisplayName(text(profileNode, "firstName"), text(profileNode, "lastName"), athleteId));
    String firstName = text(profileNode, "firstName");
    String lastName = text(profileNode, "lastName");
    String position = resolvePosition(athleteNode, profileNode);
    String jerseyNumber = firstText(profileNode, "jersey", "jerseyNumber").orElse(null);
    String teamName = resolveTeamName(athleteNode, profileNode);
    String teamId = resolveTeamId(athleteNode, profileNode);
    Boolean active = athleteNode.hasNonNull("active") ? athleteNode.path("active").asBoolean() : null;

    return new AthleteCandidate(
        athleteId,
        displayName,
        firstName,
        lastName,
        position,
        jerseyNumber,
        teamName,
        teamId,
        active,
        score);
  }

  private static Optional<AthleteCandidate> toCandidate(JsonNode athleteNode, String query) {
    String athleteId = text(athleteNode, "id");
    if (athleteId == null) {
      return Optional.empty();
    }

    double score = scoreCandidate(athleteNode, query);
    if (score < MIN_CANDIDATE_SCORE) {
      return Optional.empty();
    }
    return Optional.of(toCandidate(athleteNode, score));
  }

  private static double scoreCandidate(JsonNode athleteNode, String query) {
    String firstName = text(athleteNode, "firstName");
    String lastName = text(athleteNode, "lastName");
    String displayName =
        firstText(athleteNode, "displayName", "fullName", "shortName", "name").orElse(null);

    double displayScore = NameSimilarity.scoreText(query, NameSimilarity.normalize(displayName));
    double tokenSortScore = NameSimilarity.tokenSortScore(query, displayName);
    double firstNameScore =
        NameSimilarity.scoreText(queryFirstToken(query), NameSimilarity.normalize(firstName));
    double lastNameScore =
        NameSimilarity.scoreText(queryLastToken(query), NameSimilarity.normalize(lastName));
    double namePairScore =
        NameSimilarity.scoreText(
            query,
            NameSimilarity.normalize(buildNamePair(firstName, lastName)));

    double weightedScore =
        (0.40d * displayScore)
            + (0.25d * tokenSortScore)
            + (0.20d * lastNameScore)
            + (0.10d * firstNameScore)
            + (0.05d * namePairScore);

    return Math.max(weightedScore, Math.max(displayScore, tokenSortScore));
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
        collectCandidateNodes(fields.next().getValue(), consumer);
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

  private static String resolvePosition(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node == null || node.isNull() || node.isMissingNode()) {
        continue;
      }

      JsonNode positionNode = node.path("position");
      if (!positionNode.isMissingNode() && !positionNode.isNull()) {
        String resolved = firstNonBlank(
            text(positionNode, "abbreviation"),
            text(positionNode, "shortDisplayName"),
            text(positionNode, "displayName"),
            text(positionNode, "name"),
            text(positionNode, "value"));
        if (resolved != null) {
          return resolved;
        }
      }

      String direct = firstNonBlank(text(node, "position"));
      if (direct != null) {
        return direct;
      }
    }

    return null;
  }

  private static String resolveTeamName(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node == null || node.isNull() || node.isMissingNode()) {
        continue;
      }

      JsonNode teamNode = node.path("team");
      if (!teamNode.isMissingNode() && !teamNode.isNull()) {
        String resolved =
            firstNonBlank(
                text(teamNode, "displayName"),
                text(teamNode, "shortDisplayName"),
                text(teamNode, "name"),
                text(teamNode, "location"),
                text(teamNode, "abbreviation"));
        if (resolved != null) {
          return resolved;
        }
      }

      String direct = firstNonBlank(text(node, "team"));
      if (direct != null) {
        return direct;
      }
    }

    return null;
  }

  private static String resolveTeamId(JsonNode... nodes) {
    for (JsonNode node : nodes) {
      if (node == null || node.isNull() || node.isMissingNode()) {
        continue;
      }

      JsonNode teamNode = node.path("team");
      if (!teamNode.isMissingNode() && !teamNode.isNull()) {
        String resolved = firstNonBlank(text(teamNode, "id"));
        if (resolved != null) {
          return resolved;
        }
      }

      String direct = firstNonBlank(text(node, "teamId"), text(node, "team_id"), text(node, "team"));
      if (direct != null) {
        return direct;
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

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String queryFirstToken(String query) {
    String[] tokens = NameSimilarity.tokenize(query);
    return tokens.length == 0 ? null : tokens[0];
  }

  private static String queryLastToken(String query) {
    String[] tokens = NameSimilarity.tokenize(query);
    return tokens.length == 0 ? null : tokens[tokens.length - 1];
  }

  private static String buildNamePair(String firstName, String lastName) {
    if (firstName == null && lastName == null) {
      return null;
    }
    if (firstName == null) {
      return lastName;
    }
    if (lastName == null) {
      return firstName;
    }
    return firstName + " " + lastName;
  }

  private static String requireText(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null) {
      throw new IllegalArgumentException("Missing required field '" + field + "'");
    }
    return value;
  }

  private static String normalize(String value) {
    return NameSimilarity.normalize(value);
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

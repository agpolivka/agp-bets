package com.agp.bets.goforbroke.player.service;

import com.agp.bets.goforbroke.player.domain.Player;
import com.agp.bets.goforbroke.player.domain.PlayerGameStat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EspnPlayerGameStatMapper {

  private EspnPlayerGameStatMapper() {}

  public static List<PlayerGameStatSnapshot> toSnapshots(
      Player player, JsonNode root, String sourceUrl, Instant fetchedAt) {
    if (player == null || root == null || root.isNull()) {
      return List.of();
    }

    List<JsonNode> entries = collectEntries(root);
    if (entries.isEmpty()) {
      return List.of();
    }

    JsonNode labels = root.path("labels");
    String playerPosition = player.getPosition();
    List<PlayerGameStatSnapshot> snapshots = new ArrayList<>();
    for (JsonNode entry : entries) {
      snapshots.add(toSnapshot(entry, labels, playerPosition, sourceUrl, fetchedAt));
    }
    return snapshots;
  }

  public static List<PlayerGameStatSnapshot> toSnapshotsFromGameLogPage(
      Player player,
      String html,
      String sourceUrl,
      Instant fetchedAt,
      ObjectMapper objectMapper) {
    if (player == null || html == null || html.isBlank()) {
      return List.of();
    }

    String gmlogJson = extractEmbeddedObject(html, "\"gmlog\":");
    if (gmlogJson == null || gmlogJson.isBlank()) {
      return List.of();
    }

    try {
      JsonNode gmlogRoot = objectMapper.readTree(gmlogJson);
      return toSnapshots(player, gmlogRoot, sourceUrl, fetchedAt);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to parse ESPN game log payload", exception);
    }
  }

  public static PlayerGameStat toEntity(
      Player player, PlayerGameStatSnapshot snapshot, Instant now) {
    PlayerGameStat entity = new PlayerGameStat();
    entity.setPlayer(player);
    entity.setGameDate(snapshot.gameDate());
    entity.setSeason(snapshot.season());
    entity.setWeek(snapshot.week());
    entity.setHomeAway(snapshot.homeAway());
    entity.setOpponentName(snapshot.opponentName());
    entity.setOpponentTeamId(snapshot.opponentTeamId());
    entity.setGamesPlayed(defaultInt(snapshot.gamesPlayed(), 1));
    entity.setPassingYards(snapshot.passingYards());
    entity.setRushingYards(snapshot.rushingYards());
    entity.setTotalYards(snapshot.totalYards());
    entity.setPassingTouchdowns(snapshot.passingTouchdowns());
    entity.setRushingTouchdowns(snapshot.rushingTouchdowns());
    entity.setReceivingTouchdowns(snapshot.receivingTouchdowns());
    entity.setTouchdowns(snapshot.touchdowns());
    entity.setTotalTouchdowns(snapshot.totalTouchdowns());
    entity.setInterceptions(snapshot.interceptions());
    entity.setFumbles(snapshot.fumbles());
    entity.setFumblesLost(snapshot.fumblesLost());
    entity.setTurnovers(snapshot.turnovers());
    entity.setSnapCount(snapshot.snapCount());
    entity.setCarries(snapshot.carries());
    entity.setReceivingTargets(snapshot.receivingTargets());
    entity.setReceptions(snapshot.receptions());
    entity.setReceivingYards(snapshot.receivingYards());
    entity.setDrops(snapshot.drops());
    entity.setSourceUrl(snapshot.sourceUrl());
    entity.setRawPayload(snapshot.rawPayload());
    entity.setFetchedAt(snapshot.fetchedAt());
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    return entity;
  }

  public static PlayerGameStatSnapshot toSnapshot(
      JsonNode entry,
      JsonNode labels,
      String playerPosition,
      String sourceUrl,
      Instant fetchedAt) {
    LocalDate gameDate =
        firstText(entry, "date", "dt", "gameDate", "playedOn", "startDate")
            .map(EspnPlayerGameStatMapper::parseDate)
            .orElse(null);

    Integer season = firstInt(entry, "season", "seasonYear").orElse(null);
    if (season == null) {
      season = deriveSeasonFromGameDate(gameDate);
    }
    Integer week = firstInt(entry, "week", "gameWeek", "period").orElse(null);
    String homeAway =
        translateHomeAway(
            firstText(entry, "homeAway", "location", "venue").orElse(null),
            nestedText(entry, "opp", "atVs")
                .orElseGet(() -> nestedText(entry, "event", "atVs").orElse(null)));
    String opponentName =
        firstText(entry, "opponentName", "opponent", "team", "opposingTeam")
            .orElseGet(
                () ->
                    nestedText(entry, "opp", "name", "displayName", "shortDisplayName", "abbreviation")
                        .orElseGet(
                            () ->
                                nestedText(
                                        entry,
                                        "opponent",
                                        "displayName",
                                        "shortDisplayName",
                                        "name",
                                        "abbreviation")
                                    .orElseGet(
                                        () ->
                                            nestedText(
                                                    entry,
                                                    "event",
                                                    "name",
                                                    "displayName",
                                                    "shortDisplayName",
                                                    "abbreviation")
                                                .orElse(null))));
    String opponentTeamId =
        firstText(entry, "opponentTeamId", "opponentId", "teamId")
            .orElseGet(
                () ->
                    nestedText(entry, "opp", "id")
                        .orElseGet(
                            () ->
                                nestedText(entry, "opponent", "id")
                                    .orElseGet(() -> nestedText(entry, "event", "id").orElse(null))));

    List<String> statValues = extractStatValues(entry.path("stats"));
    String inferredPosition = inferPosition(playerPosition, labels, statValues);

    Integer gamesPlayed = 1;
    Integer passingYards = null;
    Integer rushingYards = null;
    Integer receivingYards = null;
    Integer passingTouchdowns = null;
    Integer rushingTouchdowns = null;
    Integer receivingTouchdowns = null;
    Integer touchdowns = null;
    Integer interceptions = null;
    Integer fumbles = null;
    Integer fumblesLost = null;
    Integer snapCount = null;
    Integer carries = null;
    Integer receivingTargets = null;
    Integer receptions = null;
    Integer drops = null;

    if (!statValues.isEmpty()) {
      if ("QB".equals(inferredPosition)) {
        passingYards = statValue(labels, statValues, 1, "YDS");
        rushingYards = statValue(labels, statValues, 2, "YDS");
        passingTouchdowns = statValue(labels, statValues, 1, "TD");
        rushingTouchdowns = statValue(labels, statValues, 2, "TD");
        touchdowns = sumIntegers(passingTouchdowns, rushingTouchdowns);
        interceptions = statValue(labels, statValues, 1, "INT");
        carries = statValue(labels, statValues, 1, "CAR");
      } else if ("RB".equals(inferredPosition)) {
        carries = statValue(labels, statValues, 1, "CAR");
        rushingYards = statValue(labels, statValues, 1, "YDS");
        rushingTouchdowns = statValue(labels, statValues, 1, "TD");
        receptions = statValue(labels, statValues, 1, "REC", "RECEPTIONS");
        receivingTargets = statValue(labels, statValues, 1, "TGTS", "TARGETS");
        receivingYards = statValue(labels, statValues, 2, "YDS");
        receivingTouchdowns = statValue(labels, statValues, 2, "TD");
        fumbles = statValue(labels, statValues, 1, "FUM", "FUMBLES");
        fumblesLost = statValue(labels, statValues, 1, "LST", "FUM LOST", "FUMBLES LOST");
        touchdowns = sumIntegers(rushingTouchdowns, receivingTouchdowns);
      } else {
        receptions = statValue(labels, statValues, 1, "REC", "RECEPTIONS");
        receivingTargets = statValue(labels, statValues, 1, "TGTS", "TARGETS");
        receivingYards = statValue(labels, statValues, 1, "YDS");
        receivingTouchdowns = statValue(labels, statValues, 1, "TD");
        carries = statValue(labels, statValues, 1, "CAR", "CARRIES");
        rushingYards = statValue(labels, statValues, 2, "YDS");
        rushingTouchdowns = statValue(labels, statValues, 2, "TD");
        fumbles = statValue(labels, statValues, 1, "FUM", "FUMBLES");
        fumblesLost = statValue(labels, statValues, 1, "LST", "FUM LOST", "FUMBLES LOST");
        touchdowns = sumIntegers(receivingTouchdowns, rushingTouchdowns);
      }
    }

    if (passingYards == null) {
      passingYards = firstInt(entry, "passingYards", "passYards", "yardsPassing", "passing_yards").orElse(null);
    }
    if (rushingYards == null) {
      rushingYards = firstInt(entry, "rushingYards", "rushYards", "yardsRushing", "rushing_yards").orElse(null);
    }
    if (receivingYards == null) {
      receivingYards = firstInt(entry, "receivingYards", "recYards", "yardsReceiving", "receiving_yards").orElse(null);
    }
    if (passingTouchdowns == null) {
      passingTouchdowns = firstInt(entry, "passingTouchdowns", "passTouchdowns", "passingTDs", "passTDs").orElse(null);
    }
    if (rushingTouchdowns == null) {
      rushingTouchdowns = firstInt(entry, "rushingTouchdowns", "rushTouchdowns", "rushingTDs", "rushTDs").orElse(null);
    }
    if (receivingTouchdowns == null) {
      receivingTouchdowns = firstInt(entry, "receivingTouchdowns", "recTouchdowns", "receivingTDs").orElse(null);
    }
    if (touchdowns == null) {
      touchdowns = firstInt(entry, "touchdowns", "tds", "td").orElse(null);
    }
    if (interceptions == null) {
      interceptions = firstInt(entry, "interceptions", "ints", "interception").orElse(null);
    }
    if (fumbles == null) {
      fumbles = firstInt(entry, "fumbles").orElse(null);
    }
    if (fumblesLost == null) {
      fumblesLost = firstInt(entry, "fumblesLost", "lostFumbles").orElse(null);
    }
    if (snapCount == null) {
      snapCount = statValue(labels, statValues, 1, "SNAP", "SNAPS", "SNAPCOUNT", "SNAP COUNT", "SNP");
    }
    if (carries == null) {
      carries = firstInt(entry, "carries", "rushAttempts", "rushingAttempts", "attempts").orElse(null);
    }
    if (receivingTargets == null) {
      receivingTargets = firstInt(entry, "receivingTargets", "targets").orElse(null);
    }
    if (receptions == null) {
      receptions = firstInt(entry, "receptions", "catches").orElse(null);
    }
    if (drops == null) {
      drops = statValue(labels, statValues, 1, "DROP", "DROPS");
    }
    if (drops == null) {
      drops = firstInt(entry, "drops").orElse(null);
    }

    Integer totalYards =
        coalesce(
            sumIntegers(passingYards, rushingYards, receivingYards),
            firstInt(entry, "totalYards", "yards").orElse(null));

    Integer totalTouchdowns =
        coalesce(
            firstInt(entry, "totalTouchdowns").orElse(null),
            touchdowns,
            sumIntegers(passingTouchdowns, rushingTouchdowns, receivingTouchdowns));
    Integer turnovers =
        coalesce(firstInt(entry, "turnovers").orElse(null), sumIntegers(interceptions, fumblesLost));

    return new PlayerGameStatSnapshot(
        gameDate,
        season,
        week,
        homeAway,
        opponentName,
        opponentTeamId,
        gamesPlayed,
        passingYards,
        rushingYards,
        totalYards,
        passingTouchdowns,
        rushingTouchdowns,
        receivingTouchdowns,
        touchdowns,
        totalTouchdowns,
        interceptions,
        fumbles,
        fumblesLost,
        turnovers,
        snapCount,
        carries,
        receivingTargets,
        receptions,
        receivingYards,
        drops,
        sourceUrl,
        entry.toString(),
        fetchedAt);
  }

  private static List<JsonNode> collectEntries(JsonNode root) {
    List<JsonNode> entries = new ArrayList<>();
    collectEntriesRecursive(root, entries, 0);
    return entries;
  }

  private static void collectEntriesRecursive(JsonNode node, List<JsonNode> entries, int depth) {
    if (node == null || node.isNull() || depth > 12) {
      return;
    }

    if (looksLikeGameEntry(node)) {
      entries.add(node);
      return;
    }

    if (node.isArray()) {
      for (JsonNode child : node) {
        collectEntriesRecursive(child, entries, depth + 1);
      }
      return;
    }

    if (!node.isObject()) {
      return;
    }

    for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
      Map.Entry<String, JsonNode> field = fields.next();
      String name = field.getKey();
      JsonNode value = field.getValue();
      if (value == null || value.isNull()) {
        continue;
      }

      if (isEntryContainerField(name)) {
        collectEntriesRecursive(value, entries, depth + 1);
        continue;
      }

      if (value.isArray() || value.isObject()) {
        collectEntriesRecursive(value, entries, depth + 1);
      }
    }
  }

  private static boolean isEntryContainerField(String fieldName) {
    if (fieldName == null) {
      return false;
    }

    return switch (fieldName) {
      case "groups", "tbls", "events", "items", "splits", "statistics", "logs", "games", "entries" -> true;
      default -> false;
    };
  }

  private static boolean looksLikeGameEntry(JsonNode node) {
    if (node == null || !node.isObject()) {
      return false;
    }

    boolean hasStats = node.has("stats");
    if (!hasStats) {
      return false;
    }

    boolean hasDirectContext =
        node.has("date")
            || node.has("dt")
            || node.has("gameDate")
            || node.has("playedOn")
            || node.has("startDate")
            || node.has("opp")
            || node.has("opponent")
            || node.has("opponentName")
            || node.has("team")
            || node.has("week")
            || node.has("season");

    return hasDirectContext || hasNestedGameContext(node, 0);
  }

  private static boolean hasNestedGameContext(JsonNode node, int depth) {
    if (node == null || node.isNull() || depth > 4) {
      return false;
    }

    if (node.isObject()) {
      if (node.has("date")
          || node.has("dt")
          || node.has("gameDate")
          || node.has("playedOn")
          || node.has("startDate")
          || node.has("opp")
          || node.has("opponent")
          || node.has("opponentName")
          || node.has("team")
          || node.has("week")
          || node.has("season")) {
        return true;
      }

      for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
        Map.Entry<String, JsonNode> field = fields.next();
        if (hasNestedGameContext(field.getValue(), depth + 1)) {
          return true;
        }
      }
      return false;
    }

    if (node.isArray()) {
      for (JsonNode child : node) {
        if (hasNestedGameContext(child, depth + 1)) {
          return true;
        }
      }
    }

    return false;
  }

  private static Optional<String> firstText(JsonNode node, String... aliases) {
    for (String alias : aliases) {
      Optional<String> value = directText(node, alias);
      if (value.isPresent()) {
        return value;
      }

      value = findTextByAlias(node, alias);
      if (value.isPresent()) {
        return value;
      }
    }
    return Optional.empty();
  }

  private static String extractEmbeddedObject(String html, String marker) {
    int markerIndex = html.indexOf(marker);
    if (markerIndex < 0) {
      return null;
    }

    int start = html.indexOf('{', markerIndex);
    if (start < 0) {
      return null;
    }

    int depth = 0;
    boolean inString = false;
    boolean escape = false;
    for (int index = start; index < html.length(); index++) {
      char current = html.charAt(index);
      if (escape) {
        escape = false;
        continue;
      }
      if (current == '\\') {
        escape = true;
        continue;
      }
      if (current == '"') {
        inString = !inString;
        continue;
      }
      if (inString) {
        continue;
      }
      if (current == '{') {
        depth++;
      } else if (current == '}') {
        depth--;
        if (depth == 0) {
          return html.substring(start, index + 1);
        }
      }
    }

    return null;
  }

  private static Optional<String> directText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.isMissingNode()) {
      return Optional.empty();
    }

    String text = value.asText(null);
    return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  private static Optional<String> findTextByAlias(JsonNode node, String alias) {
    if (node == null || node.isNull()) {
      return Optional.empty();
    }

    if (node.isObject()) {
      Optional<String> matched = objectValueForAlias(node, alias);
      if (matched.isPresent()) {
        return matched;
      }

      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        JsonNode value = fields.next().getValue();
        if (value.isObject() || value.isArray()) {
          Optional<String> nested = findTextByAlias(value, alias);
          if (nested.isPresent()) {
            return nested;
          }
        }
      }
      return Optional.empty();
    }

    if (node.isArray()) {
      for (JsonNode child : node) {
        Optional<String> nested = findTextByAlias(child, alias);
        if (nested.isPresent()) {
          return nested;
        }
      }
    }

    return Optional.empty();
  }

  private static Optional<String> objectValueForAlias(JsonNode node, String alias) {
    for (String key : List.of("name", "abbreviation", "displayName", "shortDisplayName", "id")) {
      JsonNode keyNode = node.get(key);
      if (keyNode != null && !keyNode.isNull() && !keyNode.isMissingNode()) {
        String keyValue = keyNode.asText(null);
        if (keyValue != null && keyValue.equalsIgnoreCase(alias)) {
          return firstDirectText(node, "displayValue", "value", "text", "label");
        }
      }
    }

    JsonNode direct = node.get(alias);
    if (direct != null && !direct.isNull() && !direct.isMissingNode()) {
      return firstDirectText(direct, "displayValue", "value", "text", "label")
          .or(() -> nodeText(direct));
    }

    return Optional.empty();
  }

  private static Optional<Integer> firstInt(JsonNode node, String... aliases) {
    for (String alias : aliases) {
      Optional<Integer> value = directInt(node, alias);
      if (value.isPresent()) {
        return value;
      }

      value = findIntByAlias(node, alias);
      if (value.isPresent()) {
        return value;
      }
    }
    return Optional.empty();
  }

  private static Optional<Integer> directInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.isMissingNode()) {
      return Optional.empty();
    }
    return parseNumericNode(value);
  }

  private static Optional<Integer> findIntByAlias(JsonNode node, String alias) {
    if (node == null || node.isNull()) {
      return Optional.empty();
    }

    if (node.isObject()) {
      Optional<Integer> matched = objectIntForAlias(node, alias);
      if (matched.isPresent()) {
        return matched;
      }

      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        JsonNode value = fields.next().getValue();
        if (value.isObject() || value.isArray()) {
          Optional<Integer> nested = findIntByAlias(value, alias);
          if (nested.isPresent()) {
            return nested;
          }
        }
      }
      return Optional.empty();
    }

    if (node.isArray()) {
      for (JsonNode child : node) {
        Optional<Integer> nested = findIntByAlias(child, alias);
        if (nested.isPresent()) {
          return nested;
        }
      }
    }

    return Optional.empty();
  }

  private static Optional<Integer> objectIntForAlias(JsonNode node, String alias) {
    for (String key : List.of("name", "abbreviation", "displayName", "shortDisplayName", "id")) {
      JsonNode keyNode = node.get(key);
      if (keyNode != null && !keyNode.isNull() && !keyNode.isMissingNode()) {
        String keyValue = keyNode.asText(null);
        if (keyValue != null && keyValue.equalsIgnoreCase(alias)) {
          return firstDirectInt(node, "value", "displayValue", "amount", "count");
        }
      }
    }

    JsonNode direct = node.get(alias);
    if (direct != null && !direct.isNull() && !direct.isMissingNode()) {
      return parseNumericNode(direct);
    }

    return Optional.empty();
  }

  private static Optional<Integer> firstDirectInt(JsonNode node, String... fields) {
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value == null || value.isNull() || value.isMissingNode()) {
        continue;
      }
      Optional<Integer> parsed = parseNumericNode(value);
      if (parsed.isPresent()) {
        return parsed;
      }
    }
    return Optional.empty();
  }

  private static Optional<String> firstDirectText(JsonNode node, String... fields) {
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value == null || value.isNull() || value.isMissingNode()) {
        continue;
      }
      Optional<String> parsed = nodeText(value);
      if (parsed.isPresent()) {
        return parsed;
      }
    }
    return Optional.empty();
  }

  private static Optional<String> nestedText(JsonNode node, String childField, String... aliases) {
    JsonNode child = node.get(childField);
    if (child == null || child.isNull() || child.isMissingNode()) {
      return Optional.empty();
    }
    return firstText(child, aliases);
  }

  private static Optional<String> nodeText(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return Optional.empty();
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
  }

  private static Optional<Integer> parseNumericNode(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return Optional.empty();
    }
    if (node.isNumber()) {
      return Optional.of(node.asInt());
    }

    String text = node.asText(null);
    if (text == null || text.isBlank()) {
      return Optional.empty();
    }

    try {
      return Optional.of(Integer.parseInt(text.trim()));
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  private static LocalDate parseDate(String rawDate) {
    if (rawDate == null || rawDate.isBlank()) {
      return null;
    }

    try {
      return LocalDate.parse(rawDate);
    } catch (DateTimeParseException ignored) {
      try {
        return OffsetDateTime.parse(rawDate).toLocalDate();
      } catch (DateTimeParseException ignoredToo) {
        return null;
      }
    }
  }

  private static Integer sumIntegers(Integer... values) {
    int total = 0;
    boolean found = false;
    for (Integer value : values) {
      if (value != null) {
        total += value;
        found = true;
      }
    }
    return found ? total : null;
  }

  private static Integer coalesce(Integer... values) {
    for (Integer value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static Integer defaultInt(Integer value, int fallback) {
    return value == null ? fallback : value;
  }

  private static List<String> extractStatValues(JsonNode statsNode) {
    if (statsNode == null || statsNode.isMissingNode() || statsNode.isNull() || !statsNode.isArray()) {
      return List.of();
    }

    List<String> values = new ArrayList<>();
    for (JsonNode value : statsNode) {
      String text = value.asText(null);
      if (text == null || text.isBlank() || "-".equals(text)) {
        values.add(null);
      } else {
        values.add(text);
      }
    }
    return values;
  }

  private static Map<String, String> mapStatsByLabel(JsonNode labelsNode, List<String> values) {
    Map<String, String> map = new java.util.LinkedHashMap<>();
    if (labelsNode == null || !labelsNode.isArray()) {
      return map;
    }

    int limit = Math.min(labelsNode.size(), values.size());
    for (int index = 0; index < limit; index++) {
      JsonNode labelNode = labelsNode.get(index);
      String label = labelNode.path("data").asText(null);
      if (label == null || label.isBlank()) {
        continue;
      }
      String value = values.get(index);
      if (value != null && !value.isBlank()) {
        map.put(label.toUpperCase(java.util.Locale.ROOT) + ":" + index, value);
      }
    }
    return map;
  }

  private static Integer intValue(Map<String, String> statsByLabel, String label, int index) {
    String keyed = statsByLabel.get(label.toUpperCase(java.util.Locale.ROOT) + ":" + index);
    if (keyed == null) {
      return null;
    }
    try {
      return Integer.parseInt(keyed.replace(",", "").trim());
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static Integer statValue(
      JsonNode labelsNode, List<String> values, int occurrence, String... aliases) {
    if (labelsNode == null || !labelsNode.isArray() || values == null || occurrence <= 0) {
      return null;
    }

    int matches = 0;
    int limit = Math.min(labelsNode.size(), values.size());
    for (int index = 0; index < limit; index++) {
      JsonNode labelNode = labelsNode.get(index);
      if (!labelMatches(labelNode, aliases)) {
        continue;
      }

      matches++;
      if (matches != occurrence) {
        continue;
      }

      String rawValue = values.get(index);
      if (rawValue == null || rawValue.isBlank()) {
        return null;
      }

      try {
        return Integer.parseInt(rawValue.replace(",", "").trim());
      } catch (NumberFormatException exception) {
        return null;
      }
    }

    return null;
  }

  private static boolean labelMatches(JsonNode labelNode, String... aliases) {
    if (labelNode == null || labelNode.isNull() || aliases == null || aliases.length == 0) {
      return false;
    }

    for (String candidateField : List.of("data", "abbreviation", "shortDisplayName", "displayName", "name", "label", "text")) {
      String candidate = normalizeLabelText(labelNode.path(candidateField).asText(null));
      if (candidate.isBlank()) {
        continue;
      }

      for (String alias : aliases) {
        String normalizedAlias = normalizeLabelText(alias);
        if (matchesNormalizedLabel(candidate, normalizedAlias)) {
          return true;
        }
      }
    }

    return false;
  }

  private static boolean matchesNormalizedLabel(String candidate, String alias) {
    if (candidate == null || candidate.isBlank() || alias == null || alias.isBlank()) {
      return false;
    }

    if (candidate.equals(alias)) {
      return true;
    }

    if (candidate.endsWith("S") && candidate.substring(0, candidate.length() - 1).equals(alias)) {
      return true;
    }

    if (alias.endsWith("S") && alias.substring(0, alias.length() - 1).equals(candidate)) {
      return true;
    }

    return candidate.contains(alias) || alias.contains(candidate);
  }

  private static String normalizeLabelText(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replaceAll("[^A-Za-z0-9]", "").toUpperCase(java.util.Locale.ROOT);
  }

  private static Integer deriveSeasonFromGameDate(LocalDate gameDate) {
    if (gameDate == null) {
      return null;
    }

    int month = gameDate.getMonthValue();
    if (month <= 2) {
      return gameDate.getYear() - 1;
    }
    return gameDate.getYear();
  }

  private static String inferPosition(String playerPosition, JsonNode labelsNode, List<String> statValues) {
    String normalized = playerPosition == null ? "" : playerPosition.trim().toUpperCase(java.util.Locale.ROOT);
    if (!normalized.isBlank()) {
      return normalized;
    }

    if (labelsNode != null && labelsNode.isArray() && !labelsNode.isEmpty()) {
      String firstLabel = labelsNode.get(0).path("data").asText("");
      if ("CMP".equalsIgnoreCase(firstLabel)) {
        return "QB";
      }
      if ("CAR".equalsIgnoreCase(firstLabel)) {
        return "RB";
      }
      if ("REC".equalsIgnoreCase(firstLabel)) {
        return "WR";
      }
    }

    return null;
  }

  private static String translateHomeAway(String explicit, String atVs) {
    if (explicit != null && !explicit.isBlank()) {
      return explicit;
    }

    if (atVs == null || atVs.isBlank()) {
      return null;
    }

    if ("@".equals(atVs.trim())) {
      return "away";
    }

    if ("vs".equalsIgnoreCase(atVs.trim())) {
      return "home";
    }

    return atVs;
  }
}

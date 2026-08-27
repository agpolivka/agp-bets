package com.agp.bets.goforbroke.player.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@link EspnInjuryClient#fetchLeagueInjuries()}'s response into a flat list of {@link
 * InjuryReport}. The feed is grouped by team ({@code injuries[].injuries[]}), not by player, and -
 * unlike every other ESPN athlete payload this codebase reads - the nested {@code athlete} object
 * has no {@code id} field at all (confirmed directly against a real live pull); the athlete id has
 * to be regex-extracted from a link href instead, same trick {@link EspnPlayerClient} already uses
 * for its site-search path (just a different href shape here).
 */
final class EspnInjuryMapper {

  // Matches the numeric id out of a playercard link, e.g.
  // "https://www.espn.com/nfl/player/_/id/4428811/xavier-weaver" -> "4428811".
  private static final Pattern ATHLETE_ID_FROM_PLAYERCARD_HREF = Pattern.compile("/id/(\\d+)/");
  // Fallback for players missing a playercard link: the headshot href's own id, e.g.
  // "https://a.espncdn.com/i/headshots/nfl/players/full/4428811.png" -> "4428811".
  private static final Pattern ATHLETE_ID_FROM_HEADSHOT_HREF = Pattern.compile("/(\\d+)\\.png$");

  // ESPN's injury "date" field looks like "2026-08-19T12:07Z" (no seconds) - lenient about an
  // optional seconds component in case that ever changes.
  private static final DateTimeFormatter INJURY_DATE_FORMAT =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd'T'HH:mm")
          .optionalStart()
          .appendLiteral(':')
          .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
          .optionalEnd()
          .appendLiteral('Z')
          .toFormatter();

  private EspnInjuryMapper() {}

  static List<InjuryReport> parse(JsonNode root) {
    List<InjuryReport> reports = new ArrayList<>();
    if (root == null) {
      return reports;
    }

    for (JsonNode team : root.path("injuries")) {
      for (JsonNode injury : team.path("injuries")) {
        String athleteId = extractAthleteId(injury.path("athlete"));
        String status = textOrNull(injury, "status");
        if (athleteId == null || status == null) {
          continue;
        }

        reports.add(
            new InjuryReport(
                athleteId,
                status,
                textOrNull(injury, "shortComment"),
                textOrNull(injury, "longComment"),
                parseInstant(textOrNull(injury, "date"))));
      }
    }

    return reports;
  }

  private static String extractAthleteId(JsonNode athlete) {
    if (athlete == null || athlete.isMissingNode()) {
      return null;
    }

    for (JsonNode link : athlete.path("links")) {
      if (!hasPlayerCardRelation(link)) {
        continue;
      }
      String id = firstGroupMatch(ATHLETE_ID_FROM_PLAYERCARD_HREF, textOrNull(link, "href"));
      if (id != null) {
        return id;
      }
    }

    return firstGroupMatch(ATHLETE_ID_FROM_HEADSHOT_HREF, textOrNull(athlete.path("headshot"), "href"));
  }

  private static boolean hasPlayerCardRelation(JsonNode link) {
    for (JsonNode relValue : link.path("rel")) {
      if ("playercard".equals(relValue.asText())) {
        return true;
      }
    }
    return false;
  }

  private static String firstGroupMatch(Pattern pattern, String value) {
    if (value == null) {
      return null;
    }
    Matcher matcher = pattern.matcher(value);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static Instant parseInstant(String value) {
    if (value == null) {
      return null;
    }
    try {
      return java.time.LocalDateTime.parse(value, INJURY_DATE_FORMAT)
          .atZone(java.time.ZoneOffset.UTC)
          .toInstant();
    } catch (DateTimeParseException exception) {
      return null;
    }
  }

  private static String textOrNull(JsonNode node, String field) {
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
}

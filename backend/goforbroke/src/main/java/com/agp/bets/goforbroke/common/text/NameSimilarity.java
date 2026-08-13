package com.agp.bets.goforbroke.common.text;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure string-similarity helpers for matching human display names across data sources that don't
 * share a common ID (e.g. an ESPN athlete search result, or a betting odds provider's plain-text
 * player label) against a known name. Extracted from {@code EspnAthleteMapper} so the same scoring
 * logic can be reused outside ESPN's JSON shape.
 *
 * <p>Patterns are precompiled - {@code String.replaceAll(String, String)} recompiles its regex on
 * every call with no caching, which is fine for one-off use but turns into a real bottleneck under
 * an O(N*M) matching loop (confirmed directly: a ~9,400 x ~3,500 crosswalk pass took minutes before
 * this fix, dominated by redundant {@code Pattern.compile()} calls rather than the actual scoring
 * work).
 */
public final class NameSimilarity {

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9\\s]");
  private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

  private NameSimilarity() {}

  public static String normalize(String value) {
    if (value == null) {
      return "";
    }

    String lowered = value.trim().toLowerCase(Locale.ROOT);
    String stripped = NON_ALPHANUMERIC.matcher(lowered).replaceAll(" ");
    return WHITESPACE_RUN.matcher(stripped).replaceAll(" ").trim();
  }

  public static String[] tokenize(String value) {
    String normalized = normalize(value);
    if (normalized.isBlank()) {
      return new String[0];
    }

    return WHITESPACE_RUN.split(normalized);
  }

  /** Convenience wrapper that normalizes both raw inputs before scoring. */
  public static double similarity(String rawQuery, String rawCandidate) {
    return scoreText(normalize(rawQuery), normalize(rawCandidate));
  }

  /** Assumes both inputs are already normalize()'d - matches the historical scoreText contract. */
  public static double scoreText(String query, String candidate) {
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

  public static double tokenSortScore(String query, String candidate) {
    String sortedQuery = sortTokens(query);
    String sortedCandidate = sortTokens(candidate);
    if (sortedQuery.isBlank() || sortedCandidate.isBlank()) {
      return 0.0d;
    }
    return scoreText(sortedQuery, sortedCandidate);
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

  private static String sortTokens(String value) {
    String[] tokens = tokenize(value);
    if (tokens.length == 0) {
      return "";
    }

    List<String> sorted = new java.util.ArrayList<>(List.of(tokens));
    sorted.sort(String::compareTo);
    return String.join(" ", sorted);
  }

  private static String compact(String value) {
    return value == null ? "" : WHITESPACE_RUN.matcher(value).replaceAll("");
  }
}

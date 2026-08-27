package com.agp.bets.goforbroke.team.service;

/**
 * nflverse's raw team codes differ from ESPN's (which {@code Team.abbreviation} uses) for the Rams
 * ("LA" vs "LAR") and Washington ("WAS" vs "WSH"), plus three real franchise relocations
 * nflverse's historical data still codes under the old city (our {@code Team} table only has the
 * current identity, since it's a current-roster/branding snapshot, not a historical record) - the
 * Raiders (Oakland through 2019, "OAK" -> "LV"), the Chargers (San Diego through 2016, "SD" ->
 * "LAC"), and the Rams' earlier stint (St. Louis through 2015, "STL" -> "LAR", same target as
 * "LA" above). The relocation cases were found 2026-08-19 while backfilling {@code
 * team_strength_ratings} back to 2014 - stale data for years before that point never actually
 * exercised these codes, so the gap existed silently. Every table that stores an opponent/team
 * identifier sourced directly from nflverse (without going through the ESPN-facing sync) - {@code
 * PlayerGameStat.opponentTeamId}, {@code TeamStrengthRating.opponentTeamId} - uses the raw
 * nflverse code, so anything joining one of those back to {@code Team.abbreviation} needs this
 * crosswalk first. Mirrors {@code _common.R}'s {@code to_espn_team_abbreviation()} on the R side.
 *
 * <p>A real bug from exactly this mismatch (2026-08-17): {@code PredictionBacktestService} used to
 * key teams by {@code Team.espnTeamId} (a numeric ESPN id) instead of abbreviation entirely, so the
 * lookup never matched anything, for any team - see that class's git history / WORKPLAN.md for the
 * full story. Centralizing the crosswalk here instead of copy-pasting it per caller is meant to
 * make that specific mistake (or a divergent second copy) less likely next time.
 */
public final class NflverseTeamAbbreviations {

  private NflverseTeamAbbreviations() {}

  public static String toEspnAbbreviation(String nflverseAbbreviation) {
    return switch (nflverseAbbreviation) {
      case "LA" -> "LAR";
      case "WAS" -> "WSH";
      case "OAK" -> "LV";
      case "SD" -> "LAC";
      case "STL" -> "LAR";
      default -> nflverseAbbreviation;
    };
  }
}

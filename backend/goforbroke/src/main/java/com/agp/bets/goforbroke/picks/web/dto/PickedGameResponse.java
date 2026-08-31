package com.agp.bets.goforbroke.picks.web.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One game in a week's slate for the picks page - real schedule data plus whatever pick (if any)
 * exists for it. {@code homeScore}/{@code awayScore} are null until the real game finishes;
 * {@code correct} is null whenever there's nothing to grade yet (no pick made, or the game hasn't
 * finished) rather than defaulting to false, so the frontend can tell "not decided" apart from
 * "wrong" instead of guessing from a false-y value.
 *
 * <p>{@code locked} (2026-08-31) is a separate, earlier signal than "decided" - it flips true at
 * real kickoff, before a final score exists (which lags behind kickoff by however long until the
 * next stats refresh runs). A game can be locked without being decided (in progress or just
 * finished, score not synced yet); it's never decided without also being locked.
 */
public record PickedGameResponse(
    String gameId,
    Integer season,
    String gameType,
    Integer week,
    LocalDate gameday,
    Instant kickoffAt,
    boolean locked,
    String homeTeamAbbreviation,
    String homeTeamName,
    String homeTeamLogoUrl,
    String awayTeamAbbreviation,
    String awayTeamName,
    String awayTeamLogoUrl,
    Integer homeScore,
    Integer awayScore,
    String pickedTeamAbbreviation,
    Boolean correct) {}

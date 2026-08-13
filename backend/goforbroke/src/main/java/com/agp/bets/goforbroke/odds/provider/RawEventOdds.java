package com.agp.bets.goforbroke.odds.provider;

import java.time.Instant;
import java.util.List;

/** Full odds response for one event/bookmaker, plus the untouched raw JSON for archival. */
public record RawEventOdds(
    String externalEventId,
    String bookmaker,
    Instant eventDate,
    String homeTeam,
    String awayTeam,
    String sourceUrl,
    String rawJson,
    List<RawMarket> markets) {}

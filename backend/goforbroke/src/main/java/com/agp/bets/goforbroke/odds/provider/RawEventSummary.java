package com.agp.bets.goforbroke.odds.provider;

import java.time.Instant;

/** Provider-agnostic summary of one sporting event, as returned by an events-listing call. */
public record RawEventSummary(
    String externalEventId, String homeTeam, String awayTeam, Instant eventDate) {}

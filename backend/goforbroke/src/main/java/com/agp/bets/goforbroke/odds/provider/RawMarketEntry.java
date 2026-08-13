package com.agp.bets.goforbroke.odds.provider;

import java.time.Instant;

/**
 * One priced line within a market. {@code playerNameRaw} is null for game-level markets (moneyline,
 * spread, totals) that aren't tied to a specific player - callers ingesting player props should
 * skip entries where this is null.
 */
public record RawMarketEntry(
    String playerNameRaw,
    Double line,
    Double overPrice,
    Double underPrice,
    Instant sourceUpdatedAt) {}

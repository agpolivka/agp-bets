package com.agp.bets.goforbroke.odds.provider;

import java.util.List;

/** One named market (e.g. "Receiving Yards O/U") with one priced entry per player. */
public record RawMarket(String name, List<RawMarketEntry> entries) {}

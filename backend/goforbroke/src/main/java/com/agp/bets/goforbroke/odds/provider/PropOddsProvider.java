package com.agp.bets.goforbroke.odds.provider;

import java.time.Instant;
import java.util.List;

/**
 * A source of player-prop betting odds. Everything outside this package deals only in these
 * provider-agnostic types - swapping the concrete odds source (a different vendor, or an
 * in-house collector) means adding one new implementation here, with no change to storage,
 * crosswalking, or the backfill runner.
 */
public interface PropOddsProvider {

  /** Short identifier persisted alongside every row this provider produces (e.g. "odds-api.io"). */
  String providerName();

  /** Every event for {@code league} with a scheduled/actual kickoff in [{@code from}, {@code to}]. */
  List<RawEventSummary> listEvents(String league, Instant from, Instant to);

  /** Full odds for one event, from a single bookmaker. */
  RawEventOdds fetchEventOdds(String externalEventId, String bookmaker);
}

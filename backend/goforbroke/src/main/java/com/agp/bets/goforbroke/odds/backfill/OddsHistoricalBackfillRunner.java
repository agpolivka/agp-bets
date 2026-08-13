package com.agp.bets.goforbroke.odds.backfill;

import com.agp.bets.goforbroke.odds.provider.PropOddsProvider;
import com.agp.bets.goforbroke.odds.provider.RawEventSummary;
import com.agp.bets.goforbroke.odds.service.PlayerPropOddsIngestionService;
import com.agp.bets.goforbroke.odds.service.PlayerPropOddsIngestionService.IngestOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * One-off historical backfill: discovers every event for the configured league/date window, then
 * ingests odds for each event/bookmaker pair. Disabled by default
 * ({@code agp.odds-backfill.enabled:false}) so normal app startup is unaffected - enable via
 * {@code AGP_ODDS_BACKFILL_ENABLED=true} for a deliberate, one-time run.
 *
 * <p>Idempotent: {@link PlayerPropOddsIngestionService#ingest} skips any (event, bookmaker) pair
 * already stored, so re-running this is safe and spends no extra API budget on prior events.
 */
@Component
@ConditionalOnProperty(name = "agp.odds-backfill.enabled", havingValue = "true")
public class OddsHistoricalBackfillRunner implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(OddsHistoricalBackfillRunner.class);

  // odds-api.io's /historical/events caps each call at 31 days; stay a day under that to avoid
  // any boundary-inclusivity surprises.
  private static final int EVENTS_WINDOW_DAYS = 30;

  private final PropOddsProvider provider;
  private final PlayerPropOddsIngestionService ingestionService;
  private final String league;
  private final Instant from;
  private final Instant to;
  private final List<String> bookmakers;
  private final long requestDelayMs;

  public OddsHistoricalBackfillRunner(
      PropOddsProvider provider,
      PlayerPropOddsIngestionService ingestionService,
      @Value("${agp.odds-backfill.league:usa-nfl}") String league,
      @Value("${agp.odds-backfill.from:2025-12-14T00:00:00Z}") String from,
      @Value("${agp.odds-backfill.to:2026-02-09T00:00:00Z}") String to,
      @Value("${agp.odds-backfill.bookmakers:Bet365}") String bookmakers,
      @Value("${agp.odds-backfill.request-delay-ms:1500}") long requestDelayMs) {
    this.provider = provider;
    this.ingestionService = ingestionService;
    this.league = league;
    this.from = Instant.parse(from);
    this.to = Instant.parse(to);
    this.bookmakers =
        Arrays.stream(bookmakers.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    this.requestDelayMs = requestDelayMs;
  }

  @Override
  public void run(String... args) {
    log.info(
        "Starting odds historical backfill: league={} from={} to={} bookmakers={}",
        league,
        from,
        to,
        bookmakers);

    List<RawEventSummary> events = listAllEvents();
    int totalAttempts = events.size() * bookmakers.size();
    log.info("Discovered {} distinct events ({} event/bookmaker pairs)", events.size(), totalAttempts);

    int ingested = 0;
    int skipped = 0;
    int failed = 0;

    for (RawEventSummary event : events) {
      for (String bookmaker : bookmakers) {
        try {
          IngestOutcome outcome = ingestionService.ingest(event.externalEventId(), bookmaker);
          if (outcome == IngestOutcome.INGESTED) {
            ingested++;
            log.info(
                "Ingested event {} ({} @ {}) bookmaker={} [{} ingested so far]",
                event.externalEventId(),
                event.awayTeam(),
                event.homeTeam(),
                bookmaker,
                ingested);
            sleep();
          } else {
            skipped++;
          }
        } catch (RuntimeException exception) {
          failed++;
          log.warn(
              "Failed to ingest event {} bookmaker={}: {}",
              event.externalEventId(),
              bookmaker,
              exception.getMessage());
        }
      }
    }

    log.info("Odds historical backfill complete: ingested={} skipped={} failed={}", ingested, skipped, failed);
  }

  private List<RawEventSummary> listAllEvents() {
    List<RawEventSummary> all = new ArrayList<>();
    Instant windowStart = from;
    while (windowStart.isBefore(to)) {
      Instant windowEnd = windowStart.plus(Duration.ofDays(EVENTS_WINDOW_DAYS));
      if (windowEnd.isAfter(to)) {
        windowEnd = to;
      }
      all.addAll(provider.listEvents(league, windowStart, windowEnd));
      windowStart = windowEnd;
    }

    // Dedupe in case adjacent windows both include an event sitting exactly on the boundary.
    Map<String, RawEventSummary> byId = new LinkedHashMap<>();
    for (RawEventSummary event : all) {
      byId.putIfAbsent(event.externalEventId(), event);
    }
    return List.copyOf(byId.values());
  }

  private void sleep() {
    try {
      Thread.sleep(requestDelayMs);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}

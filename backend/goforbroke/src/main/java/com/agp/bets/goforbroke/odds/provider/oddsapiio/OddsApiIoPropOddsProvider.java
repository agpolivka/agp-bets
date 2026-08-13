package com.agp.bets.goforbroke.odds.provider.oddsapiio;

import com.agp.bets.goforbroke.odds.provider.PropOddsProvider;
import com.agp.bets.goforbroke.odds.provider.RawEventOdds;
import com.agp.bets.goforbroke.odds.provider.RawEventSummary;
import com.agp.bets.goforbroke.odds.provider.RawMarket;
import com.agp.bets.goforbroke.odds.provider.RawMarketEntry;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Translates odds-api.io's JSON shape into the provider-agnostic {@code odds.provider} types.
 * This is the only class in the codebase that should know odds-api.io's field names.
 */
@Component
public class OddsApiIoPropOddsProvider implements PropOddsProvider {

  private static final String PROVIDER = "odds-api.io";

  // odds-api.io labels player-level entries as "Player Name (teamIndex) (line)" or
  // "Player Name (First|Last|Anytime) (teamIndex)" - both end in one or more trailing "(...)"
  // groups that aren't part of the name itself.
  private static final Pattern TRAILING_PAREN_GROUP = Pattern.compile("\\s*\\([^()]*\\)\\s*$");

  // "Touchdown Scorers" includes a non-player "no scorer" outcome that survives the trailing-
  // paren strip (e.g. "No Touchdown Scorer (First)" -> "No Touchdown Scorer") and would otherwise
  // be stored/crosswalked as if it were a real player.
  private static final Set<String> NON_PLAYER_LABELS = Set.of("no touchdown scorer");

  private final OddsApiIoClient client;

  public OddsApiIoPropOddsProvider(OddsApiIoClient client) {
    this.client = client;
  }

  @Override
  public String providerName() {
    return PROVIDER;
  }

  @Override
  public List<RawEventSummary> listEvents(String league, Instant from, Instant to) {
    JsonNode events = client.fetchHistoricalEvents(league, from, to);
    List<RawEventSummary> summaries = new ArrayList<>();
    if (events == null || !events.isArray()) {
      return summaries;
    }

    for (JsonNode event : events) {
      String externalEventId = text(event, "id");
      if (externalEventId == null) {
        continue;
      }
      summaries.add(
          new RawEventSummary(
              externalEventId, text(event, "home"), text(event, "away"), parseInstant(event, "date")));
    }
    return summaries;
  }

  @Override
  public RawEventOdds fetchEventOdds(String externalEventId, String bookmaker) {
    JsonNode event = client.fetchHistoricalOdds(externalEventId, List.of(bookmaker));
    List<RawMarket> markets = parseMarkets(event.path("bookmakers").path(bookmaker));

    return new RawEventOdds(
        externalEventId,
        bookmaker,
        parseInstant(event, "date"),
        text(event, "home"),
        text(event, "away"),
        client.buildHistoricalOddsUrl(externalEventId, List.of(bookmaker)),
        event.toString(),
        markets);
  }

  private List<RawMarket> parseMarkets(JsonNode marketsNode) {
    List<RawMarket> markets = new ArrayList<>();
    if (marketsNode == null || !marketsNode.isArray()) {
      return markets;
    }

    for (JsonNode marketNode : marketsNode) {
      String marketName = text(marketNode, "name");
      if (marketName == null) {
        continue;
      }

      List<RawMarketEntry> entries = new ArrayList<>();
      JsonNode oddsNode = marketNode.path("odds");
      if (oddsNode.isArray()) {
        for (JsonNode entryNode : oddsNode) {
          String playerNameRaw = extractPlayerName(text(entryNode, "label"));
          // Game-level markets (ML/Spread/Totals) have no player label - the full response is
          // still preserved in PropOddsRawEvent.rawPayload, but only player-level entries are
          // worth deriving into PlayerPropLine rows.
          if (playerNameRaw == null) {
            continue;
          }

          entries.add(
              new RawMarketEntry(
                  playerNameRaw,
                  parseDouble(entryNode, "hdp"),
                  parseDouble(entryNode, "over"),
                  parseDouble(entryNode, "under"),
                  parseInstant(marketNode, "updatedAt")));
        }
      }

      if (!entries.isEmpty()) {
        markets.add(new RawMarket(marketName, entries));
      }
    }

    return markets;
  }

  private String extractPlayerName(String label) {
    if (label == null || label.isBlank()) {
      return null;
    }

    String result = label.trim();
    while (true) {
      java.util.regex.Matcher matcher = TRAILING_PAREN_GROUP.matcher(result);
      if (!matcher.find()) {
        break;
      }
      result = result.substring(0, matcher.start()).trim();
    }

    if (result.isBlank() || NON_PLAYER_LABELS.contains(result.toLowerCase(Locale.ROOT))) {
      return null;
    }
    return result;
  }

  private Instant parseInstant(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (java.time.format.DateTimeParseException exception) {
      return null;
    }
  }

  private Double parseDouble(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull() || value.isMissingNode()) {
      return null;
    }
    try {
      return value.isTextual() ? Double.parseDouble(value.asText()) : value.asDouble();
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private String text(JsonNode node, String field) {
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

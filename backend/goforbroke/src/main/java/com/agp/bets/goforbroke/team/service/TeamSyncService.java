package com.agp.bets.goforbroke.team.service;

import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.repository.TeamDefenseGameStatRepository;
import com.agp.bets.goforbroke.team.repository.TeamRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Team identity (name, logo, venue, record/standing summary, upcoming opponent) stays ESPN-
 * sourced here. Defensive game stats ({@code TeamDefenseGameStat}) are populated by
 * {@code etl/r/import_team_defense.R} instead (see {@link StatsRefreshWorker} in the etl
 * package) - nflverse gives the whole league in one vectorized pull rather than needing an ESPN
 * game-summary fetch per completed game per team, and this class no longer touches that data at
 * all.
 */
@Service
@Transactional
public class TeamSyncService {

  private static final int TEAM_HISTORY_BACKFILL_YEARS = 2;
  private static final long TEAM_REFRESH_TTL_HOURS = 12;

  private final TeamRepository teamRepository;
  private final TeamDefenseGameStatRepository teamDefenseGameStatRepository;
  private final PlayerRepository playerRepository;
  private final EspnTeamClient espnTeamClient;
  private final ObjectMapper objectMapper;
  private final Clock clock = Clock.systemUTC();

  public TeamSyncService(
      TeamRepository teamRepository,
      TeamDefenseGameStatRepository teamDefenseGameStatRepository,
      PlayerRepository playerRepository,
      EspnTeamClient espnTeamClient,
      ObjectMapper objectMapper) {
    this.teamRepository = teamRepository;
    this.teamDefenseGameStatRepository = teamDefenseGameStatRepository;
    this.playerRepository = playerRepository;
    this.espnTeamClient = espnTeamClient;
    this.objectMapper = objectMapper;
  }

  public Team syncTeamById(String teamId) {
    return syncTeamById(teamId, true);
  }

  public Team syncTeamByIdIfStale(String teamId) {
    return syncTeamById(teamId, false);
  }

  public boolean isTeamMissingOrStale(String teamId) {
    if (teamId == null || teamId.isBlank()) {
      return false;
    }

    return teamRepository
        .findByEspnTeamId(teamId)
        .map(team -> isStale(team.getFetchedAt()))
        .orElse(true);
  }

  private Team syncTeamById(String teamId, boolean forceRefresh) {
    if (!forceRefresh) {
      Optional<Team> existingTeam = teamRepository.findByEspnTeamId(teamId);
      if (existingTeam.isPresent() && !isStale(existingTeam.get().getFetchedAt())) {
        return existingTeam.get();
      }
    }

    JsonNode teamNode = espnTeamClient.fetchTeamById(teamId);
    Team team = upsertTeam(teamNode, clock.instant());
    hydrateSeasonContext(team);
    return team;
  }

  public TeamSyncResult syncReferencedTeams() {
    List<String> teamIds = playerRepository.findDistinctTeamIds();
    List<String> failedTeamIds = new ArrayList<>();
    int teamsSynced = 0;
    int defensiveGamesSynced = 0;

    for (String teamId : teamIds) {
      try {
        Team teamBeforeSync = teamRepository.findByEspnTeamId(teamId).orElse(null);
        Team syncedTeam = syncTeamByIdIfStale(teamId);
        if (teamBeforeSync == null || isStale(teamBeforeSync.getFetchedAt())) {
          teamsSynced++;
          defensiveGamesSynced +=
              teamDefenseGameStatRepository.findAllByTeam_IdOrderByGameDateDesc(syncedTeam.getId()).size();
        }
      } catch (RuntimeException exception) {
        failedTeamIds.add(teamId);
      }
    }

    return new TeamSyncResult(
        teamIds.size(), teamsSynced, defensiveGamesSynced, failedTeamIds, clock.instant());
  }

  // Record/standing summary and upcoming opponent - the identity-adjacent context that stays on
  // ESPN. hydrateUpcomingOpponent only mutates the in-memory team, so this ends with an explicit
  // save to persist whatever it set on the last iterated season.
  private void hydrateSeasonContext(Team team) {
    int currentYear = Year.now(clock).getValue();
    for (int season = currentYear - TEAM_HISTORY_BACKFILL_YEARS + 1; season <= currentYear; season++) {
      JsonNode schedule = espnTeamClient.fetchTeamSchedule(team.getEspnTeamId(), season);
      hydrateSeasonSummary(team, schedule);
      hydrateUpcomingOpponent(team, schedule);
    }
    teamRepository.save(team);
  }

  private Team upsertTeam(JsonNode teamNode, Instant now) {
    JsonNode teamData = teamNode.path("team");
    JsonNode franchise = teamData.path("franchise");
    JsonNode venue = franchise.path("venue");

    String teamId = teamData.path("id").asText();
    Team team = teamRepository.findByEspnTeamId(teamId).orElseGet(Team::new);
    boolean isNew = team.getId() == null;

    team.setEspnTeamId(teamId);
    team.setDisplayName(textValue(teamData, "displayName"));
    team.setLocation(textValue(teamData, "location"));
    team.setName(textValue(teamData, "name"));
    team.setAbbreviation(textValue(teamData, "abbreviation"));
    team.setSlug(textValue(teamData, "slug"));
    team.setLogoUrl(selectLogoUrl(teamData));
    team.setPrimaryColor(textValue(teamData, "color"));
    team.setAlternateColor(textValue(teamData, "alternateColor"));
    team.setStandingSummary(textValue(teamNode, "standingSummary"));
    team.setVenueId(textValue(venue, "id"));
    team.setVenueName(textValue(venue, "fullName"));
    team.setVenueCity(textValue(venue.path("address"), "city"));
    team.setVenueState(textValue(venue.path("address"), "state"));
    team.setVenueIndoor(booleanValue(venue, "indoor"));
    team.setVenueGrass(booleanValue(venue, "grass"));
    team.setActive(teamData.path("isActive").asBoolean());
    team.setSourceUrl(espnTeamClient.buildTeamUrl(teamId));
    team.setRawPayload(writeJson(teamNode));
    team.setFetchedAt(now);
    team.setCreatedAt(isNew ? now : team.getCreatedAt());
    team.setUpdatedAt(now);

    return teamRepository.save(team);
  }

  private void hydrateSeasonSummary(Team team, JsonNode schedule) {
    String recordSummary = textValue(schedule.path("team"), "recordSummary");
    if (recordSummary != null && !recordSummary.isBlank()) {
      team.setRecordSummary(recordSummary);
    }

    String standingSummary = textValue(schedule.path("team"), "standingSummary");
    if (standingSummary != null && !standingSummary.isBlank()) {
      team.setStandingSummary(standingSummary);
    }

    teamRepository.save(team);
  }

  private void hydrateUpcomingOpponent(Team team, JsonNode schedule) {
    LocalDate today = LocalDate.now(clock);
    UpcomingOpponent nextOpponent = null;

    for (JsonNode event : extractEventNodes(schedule)) {
      Optional<UpcomingOpponent> candidate = toUpcomingOpponent(team.getEspnTeamId(), event, today);
      if (candidate.isEmpty()) {
        continue;
      }

      if (nextOpponent == null || candidate.get().gameDate().isBefore(nextOpponent.gameDate())) {
        nextOpponent = candidate.get();
      }
    }

    if (nextOpponent == null) {
      team.setUpcomingOpponentTeamId(null);
      team.setUpcomingOpponentName(null);
      team.setUpcomingGameDate(null);
      team.setUpcomingGameTime(null);
      team.setUpcomingGameIsHome(null);
    } else {
      team.setUpcomingOpponentTeamId(nextOpponent.opponentTeamId());
      team.setUpcomingOpponentName(nextOpponent.opponentName());
      team.setUpcomingGameDate(nextOpponent.gameDate());
      team.setUpcomingGameTime(nextOpponent.gameTime());
      team.setUpcomingGameIsHome(nextOpponent.isHomeGame());
    }
  }

  private JsonNode findOpponentCompetitor(JsonNode competition, String teamId) {
    for (JsonNode competitor : competition.path("competitors")) {
      if (!teamId.equals(competitor.path("team").path("id").asText())) {
        return competitor;
      }
    }
    return competition.path("competitors").path(0);
  }

  private List<JsonNode> extractEventNodes(JsonNode schedule) {
    List<JsonNode> events = new ArrayList<>();
    JsonNode eventsNode = schedule.path("events");
    if (eventsNode.isArray()) {
      eventsNode.forEach(events::add);
    }
    return events;
  }

  private Optional<UpcomingOpponent> toUpcomingOpponent(
      String teamId, JsonNode event, LocalDate today) {
    String rawDate = event.path("date").asText(null);
    LocalDate gameDate = parseDate(rawDate);
    if (gameDate == null || gameDate.isBefore(today)) {
      return Optional.empty();
    }
    // ESPN's event date is a full kickoff timestamp (e.g. "2026-09-14T17:00Z"), not just a date -
    // kept alongside gameDate (rather than replacing it) since existing callers only need the date;
    // the weather forecast integration needs the actual kickoff time to pick the right forecast hour.
    Instant gameTime = parseInstant(rawDate);

    JsonNode competition = event.path("competitions").path(0);
    boolean completed =
        competition.path("status").path("type").path("completed").asBoolean(false)
            || event.path("status").path("type").path("completed").asBoolean(false);
    if (completed) {
      return Optional.empty();
    }

    JsonNode opponentCompetitor = findOpponentCompetitor(competition, teamId);
    // The opponent's own homeAway flag tells us where the game itself is played - needed for
    // weather (has to look up the *host* stadium, not necessarily this team's own).
    Boolean isHomeGame = "away".equalsIgnoreCase(opponentCompetitor.path("homeAway").asText(null));
    String opponentTeamId = opponentCompetitor.path("team").path("id").asText(null);
    String opponentName = opponentCompetitor.path("team").path("displayName").asText(null);
    if (opponentName == null || opponentName.isBlank()) {
      opponentName = opponentCompetitor.path("team").path("shortDisplayName").asText(null);
    }
    if (opponentName == null || opponentName.isBlank()) {
      return Optional.empty();
    }

    return Optional.of(new UpcomingOpponent(opponentTeamId, opponentName, gameDate, gameTime, isHomeGame));
  }

  private Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }

    try {
      return OffsetDateTime.parse(value).toInstant();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private LocalDate parseDate(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }

    try {
      return OffsetDateTime.parse(value).toLocalDate();
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private String textValue(JsonNode node, String fieldName) {
    JsonNode value = node.path(fieldName);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }

    String text = value.asText(null);
    return text == null || text.isBlank() ? null : text;
  }

  private Boolean booleanValue(JsonNode node, String fieldName) {
    JsonNode value = node.path(fieldName);
    if (value.isMissingNode() || value.isNull()) {
      return null;
    }
    return value.asBoolean();
  }

  private String selectLogoUrl(JsonNode teamData) {
    JsonNode logos = teamData.path("logos");
    if (!logos.isArray() || logos.isEmpty()) {
      return null;
    }

    for (JsonNode logo : logos) {
      for (JsonNode rel : logo.path("rel")) {
        if ("default".equalsIgnoreCase(rel.asText()) || "full".equalsIgnoreCase(rel.asText())) {
          return textValue(logo, "href");
        }
      }
    }

    return textValue(logos.path(0), "href");
  }

  private String writeJson(JsonNode node) {
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Failed to serialize ESPN team payload", exception);
    }
  }

  private boolean isStale(Instant fetchedAt) {
    if (fetchedAt == null) {
      return true;
    }

    return fetchedAt.isBefore(clock.instant().minus(TEAM_REFRESH_TTL_HOURS, ChronoUnit.HOURS));
  }
}

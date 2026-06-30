package com.agp.bets.goforbroke.team.service;

import com.agp.bets.goforbroke.player.repository.PlayerRepository;
import com.agp.bets.goforbroke.team.domain.Team;
import com.agp.bets.goforbroke.team.domain.TeamDefenseGameStat;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    syncTeamDefenseGames(team);
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

  private void syncTeamDefenseGames(Team team) {
    int currentYear = Year.now(clock).getValue();
    for (int season = currentYear - TEAM_HISTORY_BACKFILL_YEARS + 1; season <= currentYear; season++) {
      JsonNode schedule = espnTeamClient.fetchTeamSchedule(team.getEspnTeamId(), season);
      hydrateSeasonSummary(team, schedule);
      hydrateUpcomingOpponent(team, schedule);

      for (JsonNode event : schedule.path("events")) {
        if (!isCompletedEvent(event)) {
          continue;
        }

        upsertDefenseGame(team, event, espnTeamClient.fetchGameSummary(event.path("id").asText()), clock.instant());
      }
    }
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
    } else {
      team.setUpcomingOpponentTeamId(nextOpponent.opponentTeamId());
      team.setUpcomingOpponentName(nextOpponent.opponentName());
      team.setUpcomingGameDate(nextOpponent.gameDate());
    }
  }

  private void upsertDefenseGame(Team team, JsonNode event, JsonNode summary, Instant now) {
    LocalDate gameDate = parseDate(event.path("date").asText(null));
    if (gameDate == null) {
      return;
    }

    Optional<TeamDefenseGameStat> existing =
        teamDefenseGameStatRepository.findByTeam_IdAndGameDate(team.getId(), gameDate);
    TeamDefenseGameStat stat = existing.orElseGet(TeamDefenseGameStat::new);

    JsonNode competition = event.path("competitions").path(0);
    JsonNode teamCompetitor = findCompetitor(competition, team.getEspnTeamId());
    JsonNode opponentCompetitor = findOpponentCompetitor(competition, team.getEspnTeamId());
    Map<String, JsonNode> opponentStats = extractTeamBoxscoreStats(summary, opponentCompetitor.path("team").path("id").asText());

    stat.setTeam(team);
    stat.setGameDate(gameDate);
    stat.setSeason(event.path("season").path("year").asInt());
    stat.setSeasonType(event.path("seasonType").path("type").asInt());
    stat.setWeek(event.path("week").path("number").asInt());
    stat.setHomeAway(teamCompetitor.path("homeAway").asText(null));
    stat.setOpponentTeamId(opponentCompetitor.path("team").path("id").asText(null));
    stat.setOpponentName(opponentCompetitor.path("team").path("displayName").asText(null));
    stat.setPointsAllowed(parseInteger(opponentCompetitor.path("score").path("value")));
    stat.setTotalYardsAllowed(parseInteger(opponentStats.get("totalYards")));
    Integer netPassingAllowed = parseInteger(opponentStats.get("netPassingYards"));
    stat.setPassingYardsAllowed(netPassingAllowed);
    stat.setReceivingYardsAllowed(netPassingAllowed);
    stat.setRushingYardsAllowed(parseInteger(opponentStats.get("rushingYards")));
    stat.setTurnoversForced(parseInteger(opponentStats.get("turnovers")));
    stat.setInterceptions(parseInteger(opponentStats.get("interceptions")));
    stat.setFumbleRecoveries(parseInteger(opponentStats.get("fumblesLost")));
    stat.setSacks(parseSacks(opponentStats.get("sacksYardsLost")));
    stat.setSourceUrl(espnTeamClient.buildGameSummaryUrl(event.path("id").asText()));
    stat.setRawPayload(writeJson(summary));
    stat.setFetchedAt(now);
    stat.setCreatedAt(stat.getCreatedAt() == null ? now : stat.getCreatedAt());
    stat.setUpdatedAt(now);

    teamDefenseGameStatRepository.save(stat);
  }

  private Map<String, JsonNode> extractTeamBoxscoreStats(JsonNode summary, String teamId) {
    Map<String, JsonNode> statsByName = new HashMap<>();
    for (JsonNode teamBox : summary.path("boxscore").path("teams")) {
      if (!teamId.equals(teamBox.path("team").path("id").asText())) {
        continue;
      }

      for (JsonNode stat : teamBox.path("statistics")) {
        String name = stat.path("name").asText();
        if (!name.isBlank()) {
          statsByName.put(name, stat);
        }
      }
    }

    return statsByName;
  }

  private JsonNode findCompetitor(JsonNode competition, String teamId) {
    for (JsonNode competitor : competition.path("competitors")) {
      if (teamId.equals(competitor.path("team").path("id").asText())) {
        return competitor;
      }
    }
    return competition.path("competitors").path(0);
  }

  private JsonNode findOpponentCompetitor(JsonNode competition, String teamId) {
    for (JsonNode competitor : competition.path("competitors")) {
      if (!teamId.equals(competitor.path("team").path("id").asText())) {
        return competitor;
      }
    }
    return competition.path("competitors").path(0);
  }

  private boolean isCompletedEvent(JsonNode event) {
    return event.path("competitions").path(0).path("status").path("type").path("completed").asBoolean(false);
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
    LocalDate gameDate = parseDate(event.path("date").asText(null));
    if (gameDate == null || gameDate.isBefore(today)) {
      return Optional.empty();
    }

    JsonNode competition = event.path("competitions").path(0);
    boolean completed =
        competition.path("status").path("type").path("completed").asBoolean(false)
            || event.path("status").path("type").path("completed").asBoolean(false);
    if (completed) {
      return Optional.empty();
    }

    JsonNode opponentCompetitor = findOpponentCompetitor(competition, teamId);
    String opponentTeamId = opponentCompetitor.path("team").path("id").asText(null);
    String opponentName = opponentCompetitor.path("team").path("displayName").asText(null);
    if (opponentName == null || opponentName.isBlank()) {
      opponentName = opponentCompetitor.path("team").path("shortDisplayName").asText(null);
    }
    if (opponentName == null || opponentName.isBlank()) {
      return Optional.empty();
    }

    return Optional.of(new UpcomingOpponent(opponentTeamId, opponentName, gameDate));
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

  private Integer parseInteger(JsonNode statNode) {
    if (statNode == null || statNode.isMissingNode() || statNode.isNull()) {
      return null;
    }

    if (statNode.isNumber()) {
      return statNode.asInt();
    }

    JsonNode valueNode = statNode.path("value");
    if (valueNode.isNumber()) {
      return valueNode.asInt();
    }

    String displayValue = statNode.path("displayValue").asText(null);
    if (displayValue == null || displayValue.isBlank()) {
      return null;
    }

    String normalized = displayValue.replace(",", "").trim();
    if (normalized.matches("-?\\d+")) {
      return Integer.parseInt(normalized);
    }

    return null;
  }

  private Integer parseSacks(JsonNode statNode) {
    if (statNode == null || statNode.isMissingNode() || statNode.isNull()) {
      return null;
    }

    String displayValue = statNode.path("displayValue").asText(null);
    if (displayValue == null || displayValue.isBlank()) {
      return parseInteger(statNode);
    }

    String[] parts = displayValue.split("-");
    String firstPart = parts[0].trim();
    return firstPart.matches("\\d+") ? Integer.parseInt(firstPart) : null;
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

#!/usr/bin/env Rscript
#
# Backfills team defensive game stats from nflverse, replacing the ESPN per-game-summary scraping
# previously done in TeamSyncService.syncTeamDefenseGames. One season per invocation; covers
# every NFL team in a single vectorized pull - unlike the per-player scripts, this isn't scoped
# to one entity, since nflverse's team stats already cover the whole league per season at no
# extra cost.
#
# "Yards allowed" isn't a direct nflverse column - it's derived by looking up the opponent's own
# offensive production in the same game (team A's rushing yards allowed = team B's rushing yards
# gained, when A and B played each other that week). Points allowed comes from nfl_schedules
# (home_score/away_score), which must already be imported for this season (see
# import_schedules.R) for game_date/home_away/points_allowed to populate - falls back to NA
# otherwise, same degrade-gracefully pattern as the player pipeline.
#
# Team identity (name, logo, location) intentionally stays on ESPN (see TeamSyncService) - this
# script only ever reads the `teams` table (to resolve team_id by abbreviation), never writes to
# it.

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "_common.R"))

args <- commandArgs(trailingOnly = TRUE)
season_arg <- if (length(args) >= 1) as.integer(args[[1]]) else nflreadr::most_recent_season()

con <- connect_db()
on.exit(dbDisconnect(con), add = TRUE)

dbBegin(con)
on.exit({
  if (dbIsValid(con)) {
    try(dbRollback(con), silent = TRUE)
  }
}, add = TRUE)

log_info("Loading nflverse team defense stats for season ", season_arg, "...")

team_stats <- nflreadr::load_team_stats(seasons = season_arg)

schedules <- tryCatch(
  dbGetQuery(
    con,
    "select game_id, week, home_team, away_team, home_score, away_score, gameday from nfl_schedules where season = $1",
    params = list(season_arg)
  ),
  error = function(err) {
    data.frame(
      game_id = character(), week = integer(), home_team = character(), away_team = character(),
      home_score = integer(), away_score = integer(), gameday = as.Date(character())
    )
  }
)

# Each team's own offensive production per game - joined back in below as "what the opponent
# allowed."
offense_by_game <- team_stats |>
  transmute(game_id, team, passing_yards, rushing_yards, receiving_yards)

team_directory <- dbGetQuery(con, "select espn_team_id, abbreviation, display_name from teams")
opponent_directory <- team_directory |>
  transmute(opponent_abbreviation = abbreviation, opponent_team_id = espn_team_id, opponent_name = display_name)

stats <- team_stats |>
  transmute(
    game_id, team, opponent_team, week, season, season_type,
    def_sacks, def_interceptions, fumble_recovery_opp
  ) |>
  left_join(offense_by_game, by = c("game_id" = "game_id", "opponent_team" = "team")) |>
  left_join(schedules, by = c("game_id", "week")) |>
  mutate(
    team_abbreviation = to_espn_team_abbreviation(team),
    opponent_abbreviation = to_espn_team_abbreviation(opponent_team),
    game_date = gameday,
    home_away = case_when(
      team == home_team ~ "home",
      team == away_team ~ "away",
      TRUE ~ NA_character_
    ),
    points_allowed = case_when(
      team == home_team ~ away_score,
      team == away_team ~ home_score,
      TRUE ~ NA_integer_
    ),
    passing_yards_allowed = coalesce(passing_yards, 0L),
    receiving_yards_allowed = coalesce(receiving_yards, 0L),
    rushing_yards_allowed = coalesce(rushing_yards, 0L),
    total_yards_allowed = passing_yards_allowed + rushing_yards_allowed,
    interceptions = coalesce(def_interceptions, 0L),
    fumble_recoveries = coalesce(fumble_recovery_opp, 0L),
    turnovers_forced = interceptions + fumble_recoveries,
    sacks = coalesce(def_sacks, 0L),
    season_type = case_when(
      season_type == "PRE" ~ 1L,
      season_type == "REG" ~ 2L,
      season_type == "POST" ~ 3L,
      TRUE ~ NA_integer_
    ),
    source_url = paste0("https://nflverse.nflverse.com/team-stats/", season_arg),
    fetched_at = Sys.time(),
    created_at = Sys.time(),
    updated_at = Sys.time()
  ) |>
  left_join(opponent_directory, by = "opponent_abbreviation") |>
  rowwise() |>
  mutate(raw_payload = to_json_text(as.list(pick(everything())))) |>
  ungroup()

row_count <- write_team_defense_stats(
  con,
  stats,
  "import_team_defense",
  paste0("nflverse team defense import for season ", season_arg)
)

dbCommit(con)
log_info("Imported/updated ", row_count, " team defense rows.")

#!/usr/bin/env Rscript
#
# Backfills team offensive tendency stats - the counterpart TeamDefenseGameStat never had
# (Priority 3's long-open "build a cleaner team offense model" gap). Built for Priority 5's
# style-vs-style matchup work: a single Elo number can't represent "team A struggles specifically
# against team C's style while beating team B" (Elo is transitive by construction), so testing
# that needs each team's own offensive tendency alongside the opponent's own defensive tendency
# (TeamDefenseGameStat's pressures/missedTacklePct/zoneCoverageRate/etc.).
#
# Two sources, two different reliability windows, combined in one script since both need to run
# for the same season anyway:
#   1. pass_rate (nflreadr::load_team_stats(), attempts / (attempts + carries)) - a clean,
#      unambiguous ratio with no parsing risk, real back to 2018 (matching PFR advanced defense's
#      own floor). This is the primary INSERT step - every row this script ever writes starts here.
#   2. shotgun_rate (nflreadr::load_participation(), offense_formation == "SHOTGUN" share of plays,
#      grouped by possession_team - the offense - mirroring how import_participation_defense.R
#      derives defense_team from the same column) - only reliably populated 2023 onward, same
#      caveat as the defense-side participation columns. This is a second, UPDATE-only enrichment
#      step against rows step 1 already wrote.
#
# IMPORTANT: step 1 uses INSERT ... ON CONFLICT DO UPDATE, deliberately excluding shotgun_rate
# from the DO UPDATE SET clause - a delete-then-insert or a full-column-overwrite upsert would
# silently wipe shotgun_rate on every later season-1/season-2 rerun, the exact data-loss bug class
# already found and fixed for player_game_stats (see WORKPLAN.md). Postgres leaves any column not
# named in DO UPDATE SET untouched on conflict, so this is structurally guaranteed, not dependent
# on script run order.

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

dbExecute(
  con,
  "
  create table if not exists etl_import_runs (
    id bigserial primary key,
    job_name text not null,
    started_at timestamptz not null,
    finished_at timestamptz,
    row_count integer not null default 0,
    notes text
  )
  "
)

run_id <- dbGetQuery(
  con,
  "insert into etl_import_runs (job_name, started_at, row_count, notes) values ($1, $2, 0, $3) returning id",
  params = list("import_team_offense", Sys.time(), paste0("Team offense import for season ", season_arg))
)$id[[1]]

log_info("Loading nflverse team stats (pass rate) for season ", season_arg, "...")

team_stats <- nflreadr::load_team_stats(seasons = season_arg)

schedules <- tryCatch(
  dbGetQuery(
    con,
    "select game_id, week, home_team, away_team, gameday from nfl_schedules where season = $1",
    params = list(season_arg)
  ),
  error = function(err) {
    data.frame(
      game_id = character(), week = integer(), home_team = character(), away_team = character(),
      gameday = as.Date(character())
    )
  }
)

team_directory <- dbGetQuery(con, "select espn_team_id, abbreviation, display_name from teams")
opponent_directory <- team_directory |>
  transmute(opponent_abbreviation = abbreviation, opponent_team_id = espn_team_id, opponent_name = display_name)

offense_stats <- team_stats |>
  transmute(
    game_id, team, opponent_team, week, season, season_type,
    pass_rate = if_else((attempts + carries) > 0, attempts / (attempts + carries), NA_real_)
  ) |>
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
  mutate(raw_payload = to_json_text(as.list(pick(team, opponent_team, week, season, pass_rate)))) |>
  ungroup() |>
  filter(!is.na(game_date))

written <- 0L
for (i in seq_len(nrow(offense_stats))) {
  row <- offense_stats[i, ]

  team_id <- dbGetQuery(con, "select id from teams where abbreviation = $1", params = list(row$team_abbreviation[[1]]))
  if (nrow(team_id) == 0 || is.na(team_id$id[[1]])) {
    next
  }
  team_id <- team_id$id[[1]]

  updated <- dbExecute(
    con,
    "
    insert into team_offense_game_stats (
      team_id, game_date, season, season_type, week, home_away, opponent_name, opponent_team_id,
      pass_rate, source_url, raw_payload, fetched_at, created_at, updated_at
    ) values (
      $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14
    )
    on conflict (team_id, game_date) do update set
      season = excluded.season,
      season_type = excluded.season_type,
      week = excluded.week,
      home_away = excluded.home_away,
      opponent_name = excluded.opponent_name,
      opponent_team_id = excluded.opponent_team_id,
      pass_rate = excluded.pass_rate,
      source_url = excluded.source_url,
      raw_payload = excluded.raw_payload,
      fetched_at = excluded.fetched_at,
      updated_at = excluded.updated_at
    ",
    params = list(
      team_id,
      row$game_date[[1]],
      row$season[[1]],
      row$season_type[[1]],
      row$week[[1]],
      row$home_away[[1]],
      row$opponent_name[[1]],
      row$opponent_team_id[[1]],
      row$pass_rate[[1]],
      row$source_url[[1]],
      row$raw_payload[[1]],
      row$fetched_at[[1]],
      row$created_at[[1]],
      row$updated_at[[1]]
    )
  )
  written <- written + updated
}

log_info("Wrote ", written, " team_offense_game_stats rows (pass_rate) for season ", season_arg, ".")

log_info("Loading participation charting (shotgun rate) for season ", season_arg, "...")

# offense_formation's taxonomy changed in 2023, confirmed live (not assumed): 2018-2022 uses a
# granular scheme (EMPTY/I_FORM/JUMBO/PISTOL/SHOTGUN/SINGLEBACK/WILDCAT) where formations that are
# still snapped from shotgun (EMPTY, SINGLEBACK) get their own separate labels instead of being
# folded into "SHOTGUN" - so a naive `== "SHOTGUN"` share undercounts real shotgun usage for those
# seasons relative to 2023+, which collapsed everything down to a clean PISTOL/SHOTGUN/UNDER CENTER
# scheme. Real numbers, not a bug in the aggregation: league-wide computed shotgun rate sits at a
# suspiciously flat ~53-55% for 2018-2022 then jumps to 62-69% in 2023-2025, and the underlying
# value distributions confirm the taxonomy itself changed, not real play-calling behavior. Gating
# to season >= 2023 so a stale, taxonomy-inconsistent number never sits in this column silently -
# same discipline as every other "only trust this as far as the real data supports" caveat in this
# codebase (NGS/PFR/participation charting floors, etc.).
part <- if (season_arg < 2023) {
  NULL
} else {
  tryCatch(
    nflreadr::load_participation(seasons = season_arg),
    error = function(err) {
      log_info("No participation data available for season ", season_arg, " (", conditionMessage(err), ").")
      NULL
    }
  )
}

shotgun_written <- 0L
if (!is.null(part) && nrow(part) > 0) {
  part_schedules <- tryCatch(
    dbGetQuery(
      con,
      "select game_id, gameday from nfl_schedules where season = $1",
      params = list(season_arg)
    ),
    error = function(err) {
      data.frame(game_id = character(), gameday = as.Date(character()))
    }
  )

  offense_shotgun <- part |>
    inner_join(part_schedules, by = c("nflverse_game_id" = "game_id")) |>
    filter(!is.na(possession_team)) |>
    group_by(nflverse_game_id, possession_team, gameday) |>
    summarise(
      formation_plays = sum(!is.na(offense_formation)),
      shotgun_rate = if_else(
        formation_plays > 0,
        mean(offense_formation == "SHOTGUN", na.rm = TRUE),
        NA_real_
      ),
      .groups = "drop"
    ) |>
    mutate(team_abbreviation = to_espn_team_abbreviation(possession_team)) |>
    filter(!is.na(gameday), !is.na(shotgun_rate))

  for (i in seq_len(nrow(offense_shotgun))) {
    row <- offense_shotgun[i, ]

    team_id <- dbGetQuery(con, "select id from teams where abbreviation = $1", params = list(row$team_abbreviation[[1]]))
    if (nrow(team_id) == 0 || is.na(team_id$id[[1]])) {
      next
    }
    team_id <- team_id$id[[1]]

    updated <- dbExecute(
      con,
      "
      update team_offense_game_stats
      set shotgun_rate = $1, updated_at = $2
      where team_id = $3 and game_date = $4
      ",
      params = list(row$shotgun_rate[[1]], Sys.time(), team_id, row$gameday[[1]])
    )
    shotgun_written <- shotgun_written + updated
  }
}

log_info("Updated ", shotgun_written, " team_offense_game_stats rows with shotgun_rate for season ", season_arg, ".")

dbExecute(
  con,
  "update etl_import_runs set finished_at = $1, row_count = $2 where id = $3",
  params = list(Sys.time(), written, run_id)
)

dbCommit(con)
log_info("Done. Wrote/updated ", written, " pass-rate rows and ", shotgun_written, " shotgun-rate rows for season ", season_arg, ".")

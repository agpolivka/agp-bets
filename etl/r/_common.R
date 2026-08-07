user_lib <- "C:/Users/agpol/Documents/R/win-library/4.6"
if (dir.exists(user_lib)) {
  .libPaths(c(user_lib, .libPaths()))
}

suppressPackageStartupMessages({
  library(DBI)
  library(RPostgres)
  library(nflreadr)
  library(dplyr)
})

log_info <- function(...) {
  message(format(Sys.time(), "%Y-%m-%d %H:%M:%S"), " | ", paste(..., collapse = ""))
}

connect_db <- function() {
  host <- Sys.getenv("AGP_PGHOST", "localhost")
  port <- as.integer(Sys.getenv("AGP_PGPORT", "5432"))
  dbname <- Sys.getenv("AGP_PGDATABASE", "agp_bets")
  user <- Sys.getenv("AGP_PGUSER", "agp_bets")
  password <- Sys.getenv("AGP_PGPASSWORD", "agp_bets")

  dbConnect(
    RPostgres::Postgres(),
    host = host,
    port = port,
    dbname = dbname,
    user = user,
    password = password
  )
}

to_json_text <- function(value) {
  jsonlite::toJSON(value, auto_unbox = TRUE, null = "null", pretty = FALSE)
}

# Loads nflverse weekly player stats for one season, joined to espn_athlete_id and to
# nfl_schedules so game_date/home_away are populated correctly. Falls back to NA game_date/
# home_away if nfl_schedules has no rows for the season (e.g. import_schedules.R hasn't run yet).
fetch_and_shape_player_week_stats <- function(con, season_arg, espn_athlete_ids = NULL) {
  players <- nflreadr::load_players() |>
    select(gsis_id, espn_id) |>
    mutate(espn_athlete_id = as.character(espn_id))

  schedules <- tryCatch(
    dbGetQuery(
      con,
      "select week, home_team, away_team, gameday from nfl_schedules where season = $1",
      params = list(season_arg)
    ),
    error = function(err) {
      data.frame(week = integer(), home_team = character(), away_team = character(), gameday = as.Date(character()))
    }
  )

  schedules_home <- schedules |>
    transmute(week, team = home_team, game_date_home = as.Date(gameday))
  schedules_away <- schedules |>
    transmute(week, team = away_team, game_date_away = as.Date(gameday))

  shaped <- nflreadr::load_player_stats(seasons = season_arg) |>
    filter(season == season_arg) |>
    left_join(players, by = c("player_id" = "gsis_id")) |>
    filter(!is.na(espn_athlete_id), espn_athlete_id != "")

  if (!is.null(espn_athlete_ids)) {
    shaped <- shaped |> filter(espn_athlete_id %in% espn_athlete_ids)
  }

  shaped |>
    left_join(schedules_home, by = c("week", "team")) |>
    left_join(schedules_away, by = c("week", "team")) |>
    mutate(
      game_date = coalesce(game_date_home, game_date_away),
      home_away = case_when(
        !is.na(game_date_home) ~ "home",
        !is.na(game_date_away) ~ "away",
        TRUE ~ NA_character_
      ),
      opponent_name = opponent_team,
      opponent_team_id = opponent_team,
      games_played = 1L,
      passing_touchdowns = coalesce(passing_tds, 0L),
      rushing_touchdowns = coalesce(rushing_tds, 0L),
      receiving_touchdowns = coalesce(receiving_tds, 0L),
      touchdowns = passing_touchdowns + rushing_touchdowns + receiving_touchdowns,
      total_touchdowns = touchdowns,
      interceptions = coalesce(passing_interceptions, 0L),
      fumbles = coalesce(rushing_fumbles, 0L) + coalesce(receiving_fumbles, 0L) + coalesce(sack_fumbles, 0L),
      fumbles_lost = coalesce(rushing_fumbles_lost, 0L) + coalesce(receiving_fumbles_lost, 0L) + coalesce(sack_fumbles_lost, 0L),
      turnovers = interceptions + fumbles_lost,
      carries = coalesce(carries, 0L),
      receiving_targets = coalesce(targets, 0L),
      receptions = coalesce(receptions, 0L),
      receiving_yards = coalesce(receiving_yards, 0L),
      total_yards = coalesce(passing_yards, 0L) + coalesce(rushing_yards, 0L) + coalesce(receiving_yards, 0L),
      snap_count = NA_integer_,
      drops = NA_integer_,
      source_url = paste0("https://nflverse.nflverse.com/player-stats/", season_arg),
      fetched_at = Sys.time(),
      created_at = Sys.time(),
      updated_at = Sys.time()
    ) |>
    # rowwise() makes pick(everything()) capture one row at a time; without it, pick() sees the
    # whole table and to_json_text() serializes every row/column into one giant blob repeated on
    # every row.
    rowwise() |>
    mutate(raw_payload = to_json_text(as.list(pick(everything())))) |>
    ungroup()
}

# Upserts (via delete-then-insert on player_id/season/week) a shaped stats data frame produced by
# fetch_and_shape_player_week_stats() into player_game_stats, and records the run in
# etl_import_runs. Rows for players not found in the local `players` table are skipped. Returns
# the number of rows written.
write_player_game_stats <- function(con, stats, job_name, notes) {
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
    params = list(job_name, Sys.time(), notes)
  )$id[[1]]

  written <- 0L
  for (i in seq_len(nrow(stats))) {
    row <- stats[i, ]
    player_id <- dbGetQuery(
      con,
      "select id from players where espn_athlete_id = $1",
      params = list(row$espn_athlete_id[[1]])
    )

    if (nrow(player_id) == 0 || is.na(player_id$id[[1]])) {
      next
    }
    player_id <- player_id$id[[1]]

    dbExecute(
      con,
      "delete from player_game_stats where player_id = $1 and season = $2 and week = $3",
      params = list(player_id, row$season[[1]], row$week[[1]])
    )

    dbExecute(
      con,
      "
      insert into player_game_stats (
        player_id, game_date, season, week, home_away, opponent_name, opponent_team_id,
        games_played, passing_yards, rushing_yards, total_yards, passing_touchdowns,
        rushing_touchdowns, receiving_touchdowns, touchdowns, total_touchdowns, interceptions,
        fumbles, fumbles_lost, turnovers, snap_count, carries, receiving_targets, receptions,
        receiving_yards, drops, source_url, raw_payload, fetched_at, created_at, updated_at
      ) values (
        $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25,$26,$27,$28,$29,$30,$31
      )
      ",
      params = list(
        player_id,
        row$game_date[[1]],
        row$season[[1]],
        row$week[[1]],
        row$home_away[[1]],
        row$opponent_name[[1]],
        row$opponent_team_id[[1]],
        row$games_played[[1]],
        row$passing_yards[[1]],
        row$rushing_yards[[1]],
        row$total_yards[[1]],
        row$passing_touchdowns[[1]],
        row$rushing_touchdowns[[1]],
        row$receiving_touchdowns[[1]],
        row$touchdowns[[1]],
        row$total_touchdowns[[1]],
        row$interceptions[[1]],
        row$fumbles[[1]],
        row$fumbles_lost[[1]],
        row$turnovers[[1]],
        row$snap_count[[1]],
        row$carries[[1]],
        row$receiving_targets[[1]],
        row$receptions[[1]],
        row$receiving_yards[[1]],
        row$drops[[1]],
        row$source_url[[1]],
        row$raw_payload[[1]],
        row$fetched_at[[1]],
        row$created_at[[1]],
        row$updated_at[[1]]
      )
    )
    written <- written + 1L
  }

  dbExecute(
    con,
    "update etl_import_runs set finished_at = $1, row_count = $2 where id = $3",
    params = list(Sys.time(), written, run_id)
  )

  written
}

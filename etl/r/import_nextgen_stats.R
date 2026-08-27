#!/usr/bin/env Rscript
#
# Enriches already-stored player_game_stats rows with nflverse Next Gen Stats
# (nflreadr::load_nextgen_stats) - completion percentage above expectation (CPOE) for passing,
# YAC above expectation for receiving, and rush yards over expected per attempt for rushing
# (stored for reference only - see PlayerPredictionService.advancedMetricAdjustment's doc for why
# it isn't wired into the live heuristic). A player only appears in NGS data if they already have
# a box-score row for that game, so this is an UPDATE against existing rows (keyed on
# player_id/season/week), same pattern as import_pfr_advanced_rushing.R.
#
# Player identity here reuses the same gsis_id -> espn_id crosswalk already used for the box-score
# fetch (NGS's player_gsis_id lines up directly with nflreadr::load_players()$gsis_id) - no new
# crosswalk needed, unlike PFR's pfr_player_id.
#
# NGS publishes a week = 0 row per player per season that's a season-to-date aggregate, not a real
# per-game row - filtered out below so it never gets matched against a specific week's box score.

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
  params = list("import_nextgen_stats", Sys.time(), paste0("NGS enrichment for season ", season_arg))
)$id[[1]]

log_info("Loading Next Gen Stats for season ", season_arg, "...")

players <- nflreadr::load_players() |>
  select(gsis_id, espn_id) |>
  filter(!is.na(gsis_id), gsis_id != "") |>
  mutate(espn_athlete_id = as.character(espn_id))

resolve_player_id <- function(con, espn_athlete_id) {
  result <- dbGetQuery(con, "select id from players where espn_athlete_id = $1", params = list(espn_athlete_id))
  if (nrow(result) == 0 || is.na(result$id[[1]])) NA_integer_ else result$id[[1]]
}

update_column <- function(con, stat_type, target_column, value_column) {
  ngs <- tryCatch(
    nflreadr::load_nextgen_stats(seasons = season_arg, stat_type = stat_type),
    error = function(err) {
      log_info("No NGS ", stat_type, " data available for season ", season_arg, " (", conditionMessage(err), ").")
      NULL
    }
  )

  if (is.null(ngs) || nrow(ngs) == 0) {
    return(0L)
  }

  enriched <- ngs |>
    filter(week > 0) |>
    inner_join(players, by = c("player_gsis_id" = "gsis_id")) |>
    filter(!is.na(espn_athlete_id), espn_athlete_id != "", !is.na(.data[[value_column]]))

  written <- 0L
  for (i in seq_len(nrow(enriched))) {
    row <- enriched[i, ]
    player_id <- resolve_player_id(con, row$espn_athlete_id[[1]])
    if (is.na(player_id)) {
      next
    }

    updated <- dbExecute(
      con,
      paste0(
        "update player_game_stats set ", target_column, " = $1, updated_at = $2 ",
        "where player_id = $3 and season = $4 and week = $5"
      ),
      params = list(row[[value_column]][[1]], Sys.time(), player_id, row$season[[1]], row$week[[1]])
    )
    written <- written + updated
  }

  written
}

written_cpoe <- update_column(con, "passing", "passing_cpoe", "completion_percentage_above_expectation")
log_info("Season ", season_arg, ": updated ", written_cpoe, " rows with passing CPOE.")

written_yac <- update_column(con, "receiving", "receiving_yac_above_expectation", "avg_yac_above_expectation")
log_info("Season ", season_arg, ": updated ", written_yac, " rows with receiving YAC-over-expectation.")

written_separation <- update_column(con, "receiving", "receiving_separation_avg", "avg_separation")
log_info("Season ", season_arg, ": updated ", written_separation, " rows with receiving separation.")

written_ryoe <- update_column(con, "rushing", "rushing_yards_over_expected_per_att", "rush_yards_over_expected_per_att")
log_info("Season ", season_arg, ": updated ", written_ryoe, " rows with rushing yards-over-expected/att.")

total_written <- written_cpoe + written_yac + written_separation + written_ryoe

dbExecute(
  con,
  "update etl_import_runs set finished_at = $1, row_count = $2 where id = $3",
  params = list(Sys.time(), total_written, run_id)
)

dbCommit(con)
log_info("Done. Updated ", total_written, " total NGS column values for season ", season_arg, ".")

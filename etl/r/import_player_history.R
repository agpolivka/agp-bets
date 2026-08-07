#!/usr/bin/env Rscript
#
# Backfills one player's full game-log history from nflverse, triggered on-demand by the backend
# the first time a player with no stored game stats is searched/loaded (see
# PlayerHistoryBackfillService.java). Always checks the most recent 10 seasons; beyond that,
# keeps walking further back only while the player still has data, so a long career gets fully
# backfilled instead of being truncated at the 10-year default.

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "_common.R"))

args <- commandArgs(trailingOnly = TRUE)
if (length(args) < 1 || is.na(args[[1]]) || args[[1]] == "") {
  stop("Usage: import_player_history.R <espn_athlete_id>")
}
espn_athlete_id_arg <- args[[1]]

MIN_BACKFILL_SEASONS <- 10L
EARLIEST_SEASON <- 1999L

# nflverse's most recently published season, not the calendar year - e.g. still 2025 for a while
# after the 2026 calendar year starts but before the 2026 season has been played/published.
current_season <- nflreadr::most_recent_season()

con <- connect_db()
on.exit(dbDisconnect(con), add = TRUE)

dbBegin(con)
on.exit({
  if (dbIsValid(con)) {
    try(dbRollback(con), silent = TRUE)
  }
}, add = TRUE)

log_info("Backfilling player history for ESPN athlete ", espn_athlete_id_arg, "...")

total_written <- 0L
seasons_checked <- 0L
season <- current_season

repeat {
  stats <- fetch_and_shape_player_week_stats(con, season, espn_athlete_ids = espn_athlete_id_arg)

  seasons_checked <- seasons_checked + 1L
  has_data <- nrow(stats) > 0

  if (has_data) {
    total_written <- total_written + write_player_game_stats(
      con,
      stats,
      "import_player_history",
      paste0("nflverse per-player backfill for espn_athlete_id ", espn_athlete_id_arg, ", season ", season)
    )
  }

  season <- season - 1L

  if (season < EARLIEST_SEASON) {
    break
  }

  if (seasons_checked >= MIN_BACKFILL_SEASONS && !has_data) {
    break
  }
}

dbCommit(con)
log_info(
  "Backfilled ", total_written, " player stat rows across ", seasons_checked,
  " seasons for espn_athlete_id ", espn_athlete_id_arg, "."
)

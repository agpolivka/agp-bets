#!/usr/bin/env Rscript
#
# Backfills one player's full game-log history from nflverse, triggered on-demand by the backend
# the first time a player with no stored game stats is searched/loaded (see
# PlayerHistoryBackfillService.java). Always checks the most recent 10 seasons; beyond that,
# keeps walking further back only while the player still has data, so a long career gets fully
# backfilled instead of being truncated at the 10-year default.
#
# Fetches in batches of up to 10 seasons per nflverse call (fetch_and_shape_player_week_stats_multi
# in _common.R) rather than one call per season - a single player's first-ever page load pays this
# script's runtime directly, and nflverse's per-call overhead dominates over per-season data
# volume, so batching is the difference between ~5s and ~11s+ on a cold backfill.

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
BATCH_SIZE <- 10L

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
batch_end <- current_season

repeat {
  batch_start <- max(EARLIEST_SEASON, batch_end - BATCH_SIZE + 1L)
  batch_seasons <- batch_start:batch_end

  stats <- fetch_and_shape_player_week_stats_multi(con, batch_seasons, espn_athlete_ids = espn_athlete_id_arg)

  seasons_checked <- seasons_checked + length(batch_seasons)
  seasons_with_data <- if (nrow(stats) > 0) sort(unique(stats$season)) else integer(0)

  if (nrow(stats) > 0) {
    total_written <- total_written + write_player_game_stats(
      con,
      stats,
      "import_player_history",
      paste0(
        "nflverse per-player backfill for espn_athlete_id ", espn_athlete_id_arg,
        ", seasons ", batch_start, "-", batch_end
      )
    )
  }

  if (batch_start <= EARLIEST_SEASON) {
    break
  }

  # Only fetch an older batch if the oldest season we just checked still had data for this
  # player - that's the signal their career might extend further back.
  has_data_at_oldest <- batch_start %in% seasons_with_data
  if (!has_data_at_oldest) {
    break
  }

  batch_end <- batch_start - 1L
}

dbCommit(con)
log_info(
  "Backfilled ", total_written, " player stat rows across ", seasons_checked,
  " seasons for espn_athlete_id ", espn_athlete_id_arg, "."
)

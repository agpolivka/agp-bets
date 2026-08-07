#!/usr/bin/env Rscript
#
# Recurring freshness job: re-pulls the current season's latest weekly stats for players already
# stored in Postgres. Intentionally scoped to stored players only, not the full nflverse catalog
# - initial history for a new player is handled by import_player_history.R on first search.
# Intended to run on a schedule (see StatsRefreshScheduler.java) after each week's games complete.

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "_common.R"))

# nflverse's most recently published season, not the calendar year - see import_player_history.R.
current_season <- nflreadr::most_recent_season()

con <- connect_db()
on.exit(dbDisconnect(con), add = TRUE)

dbBegin(con)
on.exit({
  if (dbIsValid(con)) {
    try(dbRollback(con), silent = TRUE)
  }
}, add = TRUE)

log_info("Refreshing current-season stats for stored players (season ", current_season, ")...")

stored_athlete_ids <- dbGetQuery(
  con,
  "select espn_athlete_id from players where espn_athlete_id is not null and espn_athlete_id != ''"
)$espn_athlete_id

if (length(stored_athlete_ids) == 0) {
  log_info("No stored players found; nothing to refresh.")
  dbCommit(con)
  quit(status = 0)
}

stats <- fetch_and_shape_player_week_stats(con, current_season, espn_athlete_ids = stored_athlete_ids)

row_count <- write_player_game_stats(
  con,
  stats,
  "refresh_stored_players_weekly",
  paste0("weekly refresh of current-season stats for ", length(stored_athlete_ids), " stored players")
)

dbCommit(con)
log_info("Refreshed ", row_count, " player stat rows for ", length(stored_athlete_ids), " stored players.")

#!/usr/bin/env Rscript
#
# One-off backfill: re-fetches every already-stored player's full season range so existing
# player_game_stats rows pick up the new roof/surface/temp_fahrenheit/wind_mph/team_implied_spread/
# game_total_line columns (added for the weather/Vegas heuristic terms). write_player_game_stats
# does a delete-then-insert per player_id/season/week, so this is a safe upsert over rows that
# already exist - not a duplicate-inserting job. Run once after the column migration; ongoing
# freshness for the current season is already covered by refresh_stored_players_weekly.R, and new
# players get full history via import_player_history.R on first search.

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "_common.R"))

con <- connect_db()
on.exit(dbDisconnect(con), add = TRUE)

dbBegin(con)
on.exit({
  if (dbIsValid(con)) {
    try(dbRollback(con), silent = TRUE)
  }
}, add = TRUE)

stored_athlete_ids <- dbGetQuery(
  con,
  "select espn_athlete_id from players where espn_athlete_id is not null and espn_athlete_id != ''"
)$espn_athlete_id

if (length(stored_athlete_ids) == 0) {
  log_info("No stored players found; nothing to backfill.")
  dbCommit(con)
  quit(status = 0)
}

season_range <- dbGetQuery(con, "select min(season) as min_season, max(season) as max_season from player_game_stats")
min_season <- season_range$min_season[[1]]
max_season <- season_range$max_season[[1]]

log_info(
  "Backfilling weather/Vegas columns for ", length(stored_athlete_ids), " stored players, seasons ",
  min_season, "-", max_season, "..."
)

total_written <- 0L
for (season in min_season:max_season) {
  stats <- fetch_and_shape_player_week_stats(con, season, espn_athlete_ids = stored_athlete_ids)
  if (nrow(stats) == 0) {
    next
  }

  written <- write_player_game_stats(
    con,
    stats,
    "backfill_weather_vegas_columns",
    paste0("one-off weather/Vegas column backfill, season ", season)
  )
  total_written <- total_written + written
  log_info("Season ", season, ": backfilled ", written, " rows.")
}

dbCommit(con)
log_info("Done. Backfilled ", total_written, " total player stat rows across ", min_season, "-", max_season, ".")

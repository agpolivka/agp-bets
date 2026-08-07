#!/usr/bin/env Rscript

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "_common.R"))

args <- commandArgs(trailingOnly = TRUE)
season_arg <- if (length(args) >= 1) as.integer(args[[1]]) else as.integer(format(Sys.Date(), "%Y"))
limit_arg <- if (length(args) >= 2) suppressWarnings(as.integer(args[[2]])) else NA_integer_

con <- connect_db()
on.exit(dbDisconnect(con), add = TRUE)

dbBegin(con)
on.exit({
  if (dbIsValid(con)) {
    try(dbRollback(con), silent = TRUE)
  }
}, add = TRUE)

log_info("Loading nflverse player weekly stats for season ", season_arg, "...")
stats <- fetch_and_shape_player_week_stats(con, season_arg)

if (!is.na(limit_arg) && limit_arg > 0) {
  stats <- dplyr::slice_head(stats, n = limit_arg)
}

row_count <- write_player_game_stats(
  con,
  stats,
  "import_player_weekly_stats",
  paste0("nflverse player stats import for season ", season_arg)
)

dbCommit(con)
log_info("Imported/updated ", row_count, " player stat rows.")

#!/usr/bin/env Rscript
#
# Enriches already-stored team_defense_game_stats rows with nflverse/PFR advanced defense
# (nflreadr::load_pfr_advstats(stat_type = "def")) - pass-rush pressure and missed-tackle rate.
# This source is per-defender (one row per individual player per game), not team-level like the
# other stats already flowing into team_defense_game_stats (see import_team_defense.R) - so this
# script aggregates every defender's raw counts up to a team-game total/rate BEFORE writing,
# rather than plugging in a per-defender number directly. Summing counts first (then deriving the
# rate from the summed numerator/denominator) is the statistically correct way to combine rates
# across players with very different snap counts - naively averaging each defender's own
# percentage would let a player with 2 tackle attempts count as much as one with 12.
#
# A team only appears in this data if the game already has a team_defense_game_stats row (written
# by import_team_defense.R), so this is an UPDATE against existing rows keyed on team_id/game_date
# (matching that table's actual unique index), not another delete-then-insert of a full row shape.
#
# PFR's game_id matches nflverse's own game_id format (e.g. "2025_01_DAL_PHI") directly, so this
# joins straight to nfl_schedules on game_id - no season/week resolution needed, unlike scripts
# that only have season/week/team to work with.

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
  params = list("import_pfr_defense_advanced", Sys.time(), paste0("PFR advanced defense aggregation for season ", season_arg))
)$id[[1]]

log_info("Loading PFR advanced defense charting for season ", season_arg, "...")

def <- tryCatch(
  nflreadr::load_pfr_advstats(seasons = season_arg, stat_type = "def", summary_level = "week"),
  error = function(err) {
    log_info("No PFR advanced defense data available for season ", season_arg, " (", conditionMessage(err), ").")
    NULL
  }
)

if (is.null(def) || nrow(def) == 0) {
  log_info("No PFR advanced defense rows for season ", season_arg, "; nothing to update.")
  dbExecute(
    con,
    "update etl_import_runs set finished_at = $1, row_count = 0 where id = $2",
    params = list(Sys.time(), run_id)
  )
  dbCommit(con)
  quit(status = 0)
}

schedules <- tryCatch(
  dbGetQuery(con, "select game_id, gameday from nfl_schedules where season = $1", params = list(season_arg)),
  error = function(err) {
    data.frame(game_id = character(), gameday = as.Date(character()))
  }
)

team_game_agg <- def |>
  filter(week > 0) |>
  group_by(game_id, team) |>
  summarise(
    total_pressures = sum(def_pressures, na.rm = TRUE),
    total_missed_tackles = sum(def_missed_tackles, na.rm = TRUE),
    total_tackles_combined = sum(def_tackles_combined, na.rm = TRUE),
    .groups = "drop"
  ) |>
  mutate(
    team_abbreviation = to_espn_team_abbreviation(team),
    missed_tackle_pct = if_else(
      (total_missed_tackles + total_tackles_combined) > 0,
      total_missed_tackles / (total_missed_tackles + total_tackles_combined),
      NA_real_
    )
  ) |>
  left_join(schedules, by = "game_id") |>
  filter(!is.na(gameday))

written <- 0L
for (i in seq_len(nrow(team_game_agg))) {
  row <- team_game_agg[i, ]

  team_id <- dbGetQuery(con, "select id from teams where abbreviation = $1", params = list(row$team_abbreviation[[1]]))
  if (nrow(team_id) == 0 || is.na(team_id$id[[1]])) {
    next
  }
  team_id <- team_id$id[[1]]

  updated <- dbExecute(
    con,
    "
    update team_defense_game_stats
    set pressures = $1, missed_tackle_pct = $2, updated_at = $3
    where team_id = $4 and game_date = $5
    ",
    params = list(
      row$total_pressures[[1]],
      row$missed_tackle_pct[[1]],
      Sys.time(),
      team_id,
      row$gameday[[1]]
    )
  )
  written <- written + updated
}

dbExecute(
  con,
  "update etl_import_runs set finished_at = $1, row_count = $2 where id = $3",
  params = list(Sys.time(), written, run_id)
)

dbCommit(con)
log_info("Updated ", written, " team_defense_game_stats rows with PFR advanced defense data for season ", season_arg, ".")

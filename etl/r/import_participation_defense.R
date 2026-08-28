#!/usr/bin/env Rscript
#
# Enriches already-stored team_defense_game_stats rows with nflverse's play-by-play participation
# charting (nflreadr::load_participation()) - man/zone coverage rate, pass-rushers sent, and
# defenders in the box, averaged up to a team-game level. Confirmed live before writing this:
# reliably populated (0% NA) for 2023-2025, ~62% NA 2018-2022 (not trustworthy at that rate), 100%
# NA 2017 and earlier - so this script is only meaningfully run for season >= 2023, though it will
# still execute (and mostly no-op) for older seasons rather than hard-blocking them.
#
# Unlike import_pfr_defense_advanced.R's source (one row per defender, already labeled with that
# defender's own team), participation is one row per PLAY with only the offense's team
# (possession_team) - so this script first derives the defending team via a join to
# nfl_schedules' home_team/away_team, then averages every defensive play's charting for that
# team-game. Averaging (not summing, unlike the PFR script's pressure/tackle counts) is correct
# here since these are already per-play rates/counts, not accumulating totals across defenders.
#
# Real data quirk, confirmed live (not assumed): number_of_pass_rushers and defenders_in_box both
# use 0 as a "not applicable to this play" sentinel (e.g. a run play has no pass-rush count to
# speak of), not a genuine zero - naively averaging them in drags both numbers down to implausible
# league-wide levels (2.1 pass rushers, 4.9 defenders in box, confirmed directly against a live
# pull) versus the real ~4.3/~6.1 once those sentinel zeros are excluded (they overlap almost
# entirely - 9,214 of 9,219 zero-box rows are also zero-rushers rows in a 2024 sample, consistent
# with "not tracked for this play type" rather than two independent real zeros). Both are treated
# as missing (not zero) before averaging, same as any other NA. defense_man_zone_type doesn't have
# this problem - it's a real category (MAN_COVERAGE/ZONE_COVERAGE/NA), no zero-as-sentinel issue.
#
# A team only appears in this data if the game already has a team_defense_game_stats row (written
# by import_team_defense.R), so this is an UPDATE against existing rows keyed on team_id/game_date
# (matching that table's actual unique index), not another delete-then-insert of a full row shape.
#
# participation's nflverse_game_id matches nflverse's own schedule game_id format directly (e.g.
# "2023_01_ARI_WAS"), so this joins straight to nfl_schedules on game_id - no season/week
# resolution needed, same as import_pfr_defense_advanced.R.

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
  params = list("import_participation_defense", Sys.time(), paste0("Participation defense aggregation for season ", season_arg))
)$id[[1]]

log_info("Loading participation charting for season ", season_arg, "...")

part <- tryCatch(
  nflreadr::load_participation(seasons = season_arg),
  error = function(err) {
    log_info("No participation data available for season ", season_arg, " (", conditionMessage(err), ").")
    NULL
  }
)

if (is.null(part) || nrow(part) == 0) {
  log_info("No participation rows for season ", season_arg, "; nothing to update.")
  dbExecute(
    con,
    "update etl_import_runs set finished_at = $1, row_count = 0 where id = $2",
    params = list(Sys.time(), run_id)
  )
  dbCommit(con)
  quit(status = 0)
}

schedules <- tryCatch(
  dbGetQuery(
    con,
    "select game_id, gameday, home_team, away_team from nfl_schedules where season = $1",
    params = list(season_arg)
  ),
  error = function(err) {
    data.frame(game_id = character(), gameday = as.Date(character()), home_team = character(), away_team = character())
  }
)

team_game_agg <- part |>
  inner_join(schedules, by = c("nflverse_game_id" = "game_id")) |>
  filter(!is.na(possession_team), !is.na(home_team), !is.na(away_team)) |>
  mutate(
    defense_team = if_else(possession_team == home_team, away_team, home_team),
    number_of_pass_rushers = na_if(number_of_pass_rushers, 0),
    defenders_in_box = na_if(defenders_in_box, 0)
  ) |>
  group_by(nflverse_game_id, defense_team, gameday) |>
  summarise(
    zone_plays = sum(!is.na(defense_man_zone_type)),
    zone_coverage_rate = if_else(
      zone_plays > 0,
      mean(defense_man_zone_type == "ZONE_COVERAGE", na.rm = TRUE),
      NA_real_
    ),
    avg_pass_rushers = if_else(
      sum(!is.na(number_of_pass_rushers)) > 0,
      mean(number_of_pass_rushers, na.rm = TRUE),
      NA_real_
    ),
    avg_defenders_in_box = if_else(
      sum(!is.na(defenders_in_box)) > 0,
      mean(defenders_in_box, na.rm = TRUE),
      NA_real_
    ),
    .groups = "drop"
  ) |>
  mutate(team_abbreviation = to_espn_team_abbreviation(defense_team)) |>
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
    set zone_coverage_rate = $1, avg_pass_rushers = $2, avg_defenders_in_box = $3, updated_at = $4
    where team_id = $5 and game_date = $6
    ",
    params = list(
      row$zone_coverage_rate[[1]],
      row$avg_pass_rushers[[1]],
      row$avg_defenders_in_box[[1]],
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
log_info("Updated ", written, " team_defense_game_stats rows with participation defense data for season ", season_arg, ".")

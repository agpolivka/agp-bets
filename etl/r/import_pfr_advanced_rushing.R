#!/usr/bin/env Rscript
#
# Enriches already-stored player_game_stats rows with PFR's advanced rushing charting
# (nflreadr::load_pfr_advstats(stat_type = "rush")) - yards before/after contact and broken
# tackles. A player only appears in this data if they already have a box-score row for that game,
# so this is an UPDATE against existing rows (keyed on player_id/season/week), not another
# delete-then-insert of a full row shape like write_player_game_stats. PFR's advanced charting
# only goes back to 2018 and isn't published for every game/player - rows with no match are left
# with their existing (likely NULL) values, not zeroed out.
#
# Player identity here is different from the box-score path: PFR keys on pfr_player_id, not
# gsis_id. nflreadr::load_players() carries both, so the crosswalk is
# pfr_player_id -> load_players()$pfr_id -> espn_id, same shape as the existing gsis_id -> espn_id
# crosswalk in _common.R, just a different source column.

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
  params = list(
    "import_pfr_advanced_rushing", Sys.time(), paste0("PFR advanced rushing enrichment for season ", season_arg)
  )
)$id[[1]]

log_info("Loading PFR advanced rushing charting for season ", season_arg, "...")

players <- nflreadr::load_players() |>
  select(pfr_id, espn_id) |>
  filter(!is.na(pfr_id), pfr_id != "") |>
  mutate(espn_athlete_id = as.character(espn_id))

advstats <- tryCatch(
  nflreadr::load_pfr_advstats(seasons = season_arg, stat_type = "rush", summary_level = "week"),
  error = function(err) {
    log_info("No PFR advanced rushing data available for season ", season_arg, " (", conditionMessage(err), ").")
    NULL
  }
)

if (is.null(advstats) || nrow(advstats) == 0) {
  log_info("No PFR advanced rushing rows for season ", season_arg, "; nothing to update.")
  dbExecute(
    con,
    "update etl_import_runs set finished_at = $1, row_count = 0 where id = $2",
    params = list(Sys.time(), run_id)
  )
  dbCommit(con)
  quit(status = 0)
}

enriched <- advstats |>
  inner_join(players, by = c("pfr_player_id" = "pfr_id")) |>
  filter(!is.na(espn_athlete_id), espn_athlete_id != "")

written <- 0L
for (i in seq_len(nrow(enriched))) {
  row <- enriched[i, ]

  player_id <- dbGetQuery(
    con,
    "select id from players where espn_athlete_id = $1",
    params = list(row$espn_athlete_id[[1]])
  )
  if (nrow(player_id) == 0 || is.na(player_id$id[[1]])) {
    next
  }
  player_id <- player_id$id[[1]]

  updated <- dbExecute(
    con,
    "
    update player_game_stats
    set rushing_yards_before_contact = $1,
        rushing_yards_after_contact = $2,
        rushing_broken_tackles = $3,
        updated_at = $4
    where player_id = $5 and season = $6 and week = $7
    ",
    params = list(
      row$rushing_yards_before_contact[[1]],
      row$rushing_yards_after_contact[[1]],
      row$rushing_broken_tackles[[1]],
      Sys.time(),
      player_id,
      row$season[[1]],
      row$week[[1]]
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
log_info("Updated ", written, " player_game_stats rows with PFR advanced rushing data for season ", season_arg, ".")

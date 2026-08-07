#!/usr/bin/env Rscript

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "_common.R"))

log_info("Loading nflverse player catalog...")

args <- commandArgs(trailingOnly = TRUE)
limit_arg <- if (length(args) >= 1) suppressWarnings(as.integer(args[[1]])) else NA_integer_

players <- nflreadr::load_players() |>
  mutate(
    espn_athlete_id = as.character(espn_id),
    display_name = display_name,
    first_name = first_name,
    last_name = last_name,
    position = position,
    jersey_number = as.character(jersey_number),
    team_name = latest_team,
    team_id = NA_character_,
    active = status %in% c("Active", "ACT", "Injured Reserve", "Reserve/Injured"),
    source_url = ifelse(
      !is.na(espn_athlete_id) & espn_athlete_id != "",
      paste0("https://site.web.api.espn.com/apis/common/v3/sports/football/nfl/athletes/", espn_athlete_id),
      NA_character_
    ),
    raw_payload = to_json_text(as.list(pick(everything()))),
    fetched_at = Sys.time(),
    created_at = Sys.time(),
    updated_at = Sys.time()
  ) |>
  transmute(
    espn_athlete_id,
    display_name,
    first_name,
    last_name,
    position,
    jersey_number,
    team_name,
    team_id,
    active,
    source_url,
    raw_payload,
    fetched_at,
    created_at,
    updated_at
  ) |>
  filter(!is.na(espn_athlete_id), espn_athlete_id != "")

if (!is.na(limit_arg) && limit_arg > 0) {
  players <- dplyr::slice_head(players, n = limit_arg)
}

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
  params = list("import_players", Sys.time(), "nflverse players import")
)$id[[1]]

for (i in seq_len(nrow(players))) {
  row <- players[i, ]
  dbExecute(
    con,
    "
    insert into players (
      espn_athlete_id, display_name, first_name, last_name, position, jersey_number,
      team_name, team_id, active, source_url, raw_payload, fetched_at, created_at, updated_at
    ) values (
      $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14
    )
    on conflict (espn_athlete_id) do update set
      display_name = excluded.display_name,
      first_name = excluded.first_name,
      last_name = excluded.last_name,
      position = excluded.position,
      jersey_number = excluded.jersey_number,
      team_name = excluded.team_name,
      team_id = excluded.team_id,
      active = excluded.active,
      source_url = excluded.source_url,
      raw_payload = excluded.raw_payload,
      fetched_at = excluded.fetched_at,
      updated_at = excluded.updated_at
    ",
    params = list(
      row$espn_athlete_id[[1]],
      row$display_name[[1]],
      row$first_name[[1]],
      row$last_name[[1]],
      row$position[[1]],
      row$jersey_number[[1]],
      row$team_name[[1]],
      row$team_id[[1]],
      row$active[[1]],
      row$source_url[[1]],
      row$raw_payload[[1]],
      row$fetched_at[[1]],
      row$created_at[[1]],
      row$updated_at[[1]]
    )
  )
}

dbExecute(
  con,
  "update etl_import_runs set finished_at = $1, row_count = $2 where id = $3",
  params = list(Sys.time(), nrow(players), run_id)
)

dbCommit(con)
log_info("Imported/updated ", nrow(players), " players.")

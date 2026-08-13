#!/usr/bin/env Rscript

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "_common.R"))

log_info("Loading nflverse player catalog...")

args <- commandArgs(trailingOnly = TRUE)
limit_arg <- if (length(args) >= 1) suppressWarnings(as.integer(args[[1]])) else NA_integer_

con <- connect_db()
on.exit(dbDisconnect(con), add = TRUE)

# team_id/team_name need to come from our own `teams` table (ESPN-sourced), since nflverse's
# player catalog carries a team abbreviation but no ESPN team ID. Both columns already exist for
# all 32 teams (populated by the existing ESPN team sync), so this is a plain lookup join - no new
# mapping table needed. Prefixed names avoid colliding with the player-level `display_name` column
# below when joined.
teams_lookup <- dbGetQuery(con, "select abbreviation, espn_team_id, display_name from teams") |>
  transmute(
    team_abbreviation = abbreviation,
    team_espn_id = espn_team_id,
    team_display_name = display_name
  )

# nflverse's `load_players()` is an all-time catalog (25,000+ rows going back decades) - and its
# `status` field is not a reliable "currently rostered" signal (confirmed directly: players whose
# `last_season` is 1988 still show status "ACT"). `last_season` is the real recency signal. Cutoff
# is computed at runtime (current year minus 1) so it never needs a manual yearly update, and stays
# wide enough to include a player who last appeared in the most recently completed season even
# during the following offseason.
min_last_season <- as.integer(format(Sys.Date(), "%Y")) - 1L
log_info("Filtering to players with last_season >= ", min_last_season, "...")

players <- nflreadr::load_players() |>
  filter(!is.na(last_season), last_season >= min_last_season) |>
  mutate(
    espn_athlete_id = as.character(espn_id),
    display_name = display_name,
    first_name = first_name,
    last_name = last_name,
    position = position,
    jersey_number = as.character(jersey_number),
    team_abbreviation = to_espn_team_abbreviation(latest_team),
    # nflverse's real status codes are short abbreviations, not the long-form strings this used to
    # check for (confirmed directly: "Active"/"Injured Reserve"/"Reserve/Injured" never actually
    # appear, only "ACT" did) - IR/PUP/suspended/practice-squad players were incorrectly showing
    # active=false. Treats anyone still on a team's books in some capacity as active; only players
    # genuinely gone from the league (cut, released, retired) are not.
    active = status %in% c("ACT", "RES", "PUP", "SUS", "DEV", "EXE", "RSN", "RSR"),
    source_url = ifelse(
      !is.na(espn_athlete_id) & espn_athlete_id != "",
      paste0("https://site.web.api.espn.com/apis/common/v3/sports/football/nfl/athletes/", espn_athlete_id),
      NA_character_
    ),
    fetched_at = Sys.time(),
    created_at = Sys.time(),
    updated_at = Sys.time()
  ) |>
  # rowwise() makes pick(everything()) capture one row at a time; without it, pick() sees the
  # whole table and to_json_text() serializes every row/column into one giant blob repeated on
  # every row - confirmed directly (this bloated raw_payload to ~1.8MB per player and OOM'd the
  # backend reading it back). Same fix already applied to shape_player_week_stats() below.
  rowwise() |>
  mutate(raw_payload = to_json_text(as.list(pick(everything())))) |>
  ungroup() |>
  left_join(teams_lookup, by = "team_abbreviation") |>
  mutate(
    team_id = team_espn_id,
    # Falls back to the raw nflverse abbreviation for players with no current team match (e.g.
    # free agents) rather than losing the value entirely.
    team_name = coalesce(team_display_name, latest_team)
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

log_info("Upserting ", nrow(players), " players...")

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

# Chunked multi-row upsert instead of one dbExecute() per player - each chunk is a single INSERT
# with up to CHUNK_SIZE VALUES rows, cutting network round-trips by the same factor.
CHUNK_SIZE <- 500
UPDATE_COLUMNS <- c(
  "display_name", "first_name", "last_name", "position", "jersey_number", "team_name", "team_id",
  "active", "source_url", "raw_payload", "fetched_at", "updated_at"
)
UPDATE_CLAUSE <- paste0(UPDATE_COLUMNS, " = excluded.", UPDATE_COLUMNS, collapse = ",\n      ")

row_count <- nrow(players)
for (chunk_start in seq(1, row_count, by = CHUNK_SIZE)) {
  chunk_end <- min(chunk_start + CHUNK_SIZE - 1, row_count)
  chunk <- players[chunk_start:chunk_end, ]
  chunk_rows <- nrow(chunk)

  value_groups <- vapply(seq_len(chunk_rows), function(i) {
    base <- (i - 1L) * 14L
    paste0("($", paste(base + 1:14, collapse = ",$"), ")")
  }, character(1))

  params <- unlist(lapply(seq_len(chunk_rows), function(i) {
    row <- chunk[i, ]
    list(
      row$espn_athlete_id[[1]], row$display_name[[1]], row$first_name[[1]], row$last_name[[1]],
      row$position[[1]], row$jersey_number[[1]], row$team_name[[1]], row$team_id[[1]],
      row$active[[1]], row$source_url[[1]], row$raw_payload[[1]], row$fetched_at[[1]],
      row$created_at[[1]], row$updated_at[[1]]
    )
  }), recursive = FALSE)

  dbExecute(
    con,
    paste0(
      "
      insert into players (
        espn_athlete_id, display_name, first_name, last_name, position, jersey_number,
        team_name, team_id, active, source_url, raw_payload, fetched_at, created_at, updated_at
      ) values ", paste(value_groups, collapse = ",\n      "), "
      on conflict (espn_athlete_id) do update set
      ", UPDATE_CLAUSE
    ),
    params = params
  )

  log_info("Upserted ", chunk_end, "/", row_count, " players...")
}

dbExecute(
  con,
  "update etl_import_runs set finished_at = $1, row_count = $2 where id = $3",
  params = list(Sys.time(), nrow(players), run_id)
)

dbCommit(con)
log_info("Imported/updated ", nrow(players), " players.")

#!/usr/bin/env Rscript

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "run_etl_job.R"))

args <- commandArgs(trailingOnly = TRUE)
limit_arg <- if (length(args) >= 1) suppressWarnings(as.integer(args[[1]])) else NA_integer_

run_job("import_teams", function() {
  log_info("Loading nflverse team catalog...")

  teams <- nflreadr::load_teams() |>
    mutate(
      team_ref_id = as.character(team_id),
      display_name = team_name,
      location = NA_character_,
      name = team_nick,
      abbreviation = team_abbr,
      slug = tolower(team_abbr),
      logo_url = team_logo_espn,
      primary_color = team_color,
      alternate_color = team_color2,
      active = TRUE,
      source_url = ifelse(
        !is.na(team_id) & team_id != "",
        paste0("https://site.web.api.espn.com/apis/common/v3/sports/football/nfl/teams/", team_id),
        NA_character_
      ),
      raw_payload = to_json_text(as.list(pick(everything()))),
      fetched_at = Sys.time(),
      created_at = Sys.time(),
      updated_at = Sys.time()
    ) |>
    transmute(
      team_ref_id,
      display_name,
      location,
      name,
      abbreviation,
      slug,
      logo_url,
      primary_color,
      alternate_color,
      active,
      source_url,
      raw_payload,
      fetched_at,
      created_at,
      updated_at
    )

  if (!is.na(limit_arg) && limit_arg > 0) {
    teams <- dplyr::slice_head(teams, n = limit_arg)
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

  dbExecute(
    con,
    "
    create table if not exists nfl_teams (
      team_ref_id text primary key,
      display_name text not null,
      location text,
      name text,
      abbreviation text,
      slug text,
      logo_url text,
      primary_color text,
      alternate_color text,
      active boolean,
      source_url text not null,
      raw_payload text not null,
      fetched_at timestamptz not null,
      created_at timestamptz not null,
      updated_at timestamptz not null
    )
    "
  )

  run_id <- dbGetQuery(
    con,
    "insert into etl_import_runs (job_name, started_at, row_count, notes) values ($1, $2, 0, $3) returning id",
    params = list("import_teams", Sys.time(), "nflverse team reference import")
  )$id[[1]]

  for (i in seq_len(nrow(teams))) {
    row <- teams[i, ]
    dbExecute(
      con,
      "
      insert into nfl_teams (
        team_ref_id, display_name, location, name, abbreviation, slug, logo_url,
        primary_color, alternate_color, active, source_url, raw_payload, fetched_at,
        created_at, updated_at
      ) values (
        $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15
      )
      on conflict (team_ref_id) do update set
        display_name = excluded.display_name,
        location = excluded.location,
        name = excluded.name,
        abbreviation = excluded.abbreviation,
        slug = excluded.slug,
        logo_url = excluded.logo_url,
        primary_color = excluded.primary_color,
        alternate_color = excluded.alternate_color,
        active = excluded.active,
        source_url = excluded.source_url,
        raw_payload = excluded.raw_payload,
        fetched_at = excluded.fetched_at,
        updated_at = excluded.updated_at
      ",
      params = list(
        row$team_ref_id[[1]],
        row$display_name[[1]],
        row$location[[1]],
        row$name[[1]],
        row$abbreviation[[1]],
        row$slug[[1]],
        row$logo_url[[1]],
        row$primary_color[[1]],
        row$alternate_color[[1]],
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
    params = list(Sys.time(), nrow(teams), run_id)
  )

  dbCommit(con)
  log_info("Imported/updated ", nrow(teams), " teams.")
})

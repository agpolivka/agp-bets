#!/usr/bin/env Rscript

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "run_etl_job.R"))

args <- commandArgs(trailingOnly = TRUE)
season_arg <- if (length(args) >= 1) as.integer(args[[1]]) else as.integer(format(Sys.Date(), "%Y"))
limit_arg <- if (length(args) >= 2) suppressWarnings(as.integer(args[[2]])) else NA_integer_

run_job("import_schedules", function() {
  log_info("Loading nflverse schedules for season ", season_arg, "...")

  schedules <- nflreadr::load_schedules() |>
    filter(season == season_arg) |>
    mutate(
      game_id = as.character(game_id),
      gameday = as.Date(gameday),
      home_team = as.character(home_team),
      away_team = as.character(away_team),
      stadium = ifelse(is.na(stadium), NA_character_, as.character(stadium)),
      source_url = paste0("https://nflverse.nflverse.com/schedules/", season_arg),
      raw_payload = to_json_text(as.list(pick(everything()))),
      fetched_at = Sys.time(),
      created_at = Sys.time(),
      updated_at = Sys.time()
    )

  if (!is.na(limit_arg) && limit_arg > 0) {
    schedules <- dplyr::slice_head(schedules, n = limit_arg)
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
    create table if not exists nfl_schedules (
      game_id text primary key,
      season integer not null,
      game_type text,
      week integer,
      gameday date,
      weekday text,
      gametime text,
      away_team text,
      away_score integer,
      home_team text,
      home_score integer,
      location text,
      result text,
      total integer,
      overtime boolean,
      old_game_id text,
      gsis text,
      nfl_detail_id text,
      pfr text,
      pff text,
      espn text,
      ftn text,
      away_rest integer,
      home_rest integer,
      away_moneyline numeric,
      home_moneyline numeric,
      spread_line numeric,
      away_spread_odds integer,
      home_spread_odds integer,
      total_line numeric,
      under_odds integer,
      over_odds integer,
      div_game boolean,
      roof text,
      surface text,
      temp numeric,
      wind numeric,
      away_qb_id text,
      home_qb_id text,
      away_qb_name text,
      home_qb_name text,
      away_coach text,
      home_coach text,
      referee text,
      stadium_id text,
      stadium text,
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
    params = list("import_schedules", Sys.time(), "nflverse schedule import")
  )$id[[1]]

  for (i in seq_len(nrow(schedules))) {
    row <- schedules[i, ]
    dbExecute(
      con,
      "
      insert into nfl_schedules (
        game_id, season, game_type, week, gameday, weekday, gametime, away_team, away_score,
        home_team, home_score, location, result, total, overtime, old_game_id, gsis, nfl_detail_id,
        pfr, pff, espn, ftn, away_rest, home_rest, away_moneyline, home_moneyline, spread_line,
        away_spread_odds, home_spread_odds, total_line, under_odds, over_odds, div_game, roof,
        surface, temp, wind, away_qb_id, home_qb_id, away_qb_name, home_qb_name, away_coach,
        home_coach, referee, stadium_id, stadium, source_url, raw_payload, fetched_at, created_at,
        updated_at
      ) values (
        $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25,
        $26,$27,$28,$29,$30,$31,$32,$33,$34,$35,$36,$37,$38,$39,$40,$41,$42,$43,$44,$45,$46,$47,
        $48,$49,$50,$51
      )
      on conflict (game_id) do update set
        season = excluded.season,
        game_type = excluded.game_type,
        week = excluded.week,
        gameday = excluded.gameday,
        weekday = excluded.weekday,
        gametime = excluded.gametime,
        away_team = excluded.away_team,
        away_score = excluded.away_score,
        home_team = excluded.home_team,
        home_score = excluded.home_score,
        location = excluded.location,
        result = excluded.result,
        total = excluded.total,
        overtime = excluded.overtime,
        old_game_id = excluded.old_game_id,
        gsis = excluded.gsis,
        nfl_detail_id = excluded.nfl_detail_id,
        pfr = excluded.pfr,
        pff = excluded.pff,
        espn = excluded.espn,
        ftn = excluded.ftn,
        away_rest = excluded.away_rest,
        home_rest = excluded.home_rest,
        away_moneyline = excluded.away_moneyline,
        home_moneyline = excluded.home_moneyline,
        spread_line = excluded.spread_line,
        away_spread_odds = excluded.away_spread_odds,
        home_spread_odds = excluded.home_spread_odds,
        total_line = excluded.total_line,
        under_odds = excluded.under_odds,
        over_odds = excluded.over_odds,
        div_game = excluded.div_game,
        roof = excluded.roof,
        surface = excluded.surface,
        temp = excluded.temp,
        wind = excluded.wind,
        away_qb_id = excluded.away_qb_id,
        home_qb_id = excluded.home_qb_id,
        away_qb_name = excluded.away_qb_name,
        home_qb_name = excluded.home_qb_name,
        away_coach = excluded.away_coach,
        home_coach = excluded.home_coach,
        referee = excluded.referee,
        stadium_id = excluded.stadium_id,
        stadium = excluded.stadium,
        source_url = excluded.source_url,
        raw_payload = excluded.raw_payload,
        fetched_at = excluded.fetched_at,
        updated_at = excluded.updated_at
      ",
      params = list(
        row$game_id[[1]],
        row$season[[1]],
        row$game_type[[1]],
        row$week[[1]],
        row$gameday[[1]],
        row$weekday[[1]],
        row$gametime[[1]],
        row$away_team[[1]],
        row$away_score[[1]],
        row$home_team[[1]],
        row$home_score[[1]],
        row$location[[1]],
        row$result[[1]],
        row$total[[1]],
        row$overtime[[1]],
        row$old_game_id[[1]],
        row$gsis[[1]],
        row$nfl_detail_id[[1]],
        row$pfr[[1]],
        row$pff[[1]],
        row$espn[[1]],
        row$ftn[[1]],
        row$away_rest[[1]],
        row$home_rest[[1]],
        row$away_moneyline[[1]],
        row$home_moneyline[[1]],
        row$spread_line[[1]],
        row$away_spread_odds[[1]],
        row$home_spread_odds[[1]],
        row$total_line[[1]],
        row$under_odds[[1]],
        row$over_odds[[1]],
        row$div_game[[1]],
        row$roof[[1]],
        row$surface[[1]],
        row$temp[[1]],
        row$wind[[1]],
        row$away_qb_id[[1]],
        row$home_qb_id[[1]],
        row$away_qb_name[[1]],
        row$home_qb_name[[1]],
        row$away_coach[[1]],
        row$home_coach[[1]],
        row$referee[[1]],
        row$stadium_id[[1]],
        row$stadium[[1]],
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
    params = list(Sys.time(), nrow(schedules), run_id)
  )

  dbCommit(con)
  log_info("Imported/updated ", nrow(schedules), " schedule rows.")
})

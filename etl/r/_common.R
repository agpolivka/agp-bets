user_lib <- "C:/Users/agpol/Documents/R/win-library/4.6"
if (dir.exists(user_lib)) {
  .libPaths(c(user_lib, .libPaths()))
}

suppressPackageStartupMessages({
  library(DBI)
  library(RPostgres)
  library(nflreadr)
  library(dplyr)
})

log_info <- function(...) {
  message(format(Sys.time(), "%Y-%m-%d %H:%M:%S"), " | ", paste(..., collapse = ""))
}

connect_db <- function() {
  host <- Sys.getenv("AGP_PGHOST", "localhost")
  port <- as.integer(Sys.getenv("AGP_PGPORT", "5432"))
  dbname <- Sys.getenv("AGP_PGDATABASE", "agp_bets")
  user <- Sys.getenv("AGP_PGUSER", "agp_bets")
  password <- Sys.getenv("AGP_PGPASSWORD", "agp_bets")

  dbConnect(
    RPostgres::Postgres(),
    host = host,
    port = port,
    dbname = dbname,
    user = user,
    password = password
  )
}

to_json_text <- function(value) {
  jsonlite::toJSON(value, auto_unbox = TRUE, null = "null", pretty = FALSE)
}

# Shared final shaping step for both fetchers below. Expects a data frame that already has
# nflverse's raw player-stats columns (including its own `season` column) plus game_date_home/
# game_date_away and the weather/Vegas columns (roof/surface/temp/wind/spread_line_home/
# spread_line_away/total_line) joined in from nfl_schedules.
shape_player_week_stats <- function(joined) {
  joined |>
    mutate(
      game_date = coalesce(game_date_home, game_date_away),
      home_away = case_when(
        !is.na(game_date_home) ~ "home",
        !is.na(game_date_away) ~ "away",
        TRUE ~ NA_character_
      ),
      opponent_name = opponent_team,
      opponent_team_id = opponent_team,
      # roof/surface/temp/wind/total are the same regardless of home/away perspective.
      roof = coalesce(roof_home, roof_away),
      surface = coalesce(surface_home, surface_away),
      temp_fahrenheit = coalesce(temp_home, temp_away),
      wind_mph = coalesce(wind_home, wind_away),
      game_total_line = coalesce(total_line_home, total_line_away),
      # spread_line is already the home team's own implied margin directly - positive means home
      # favored, confirmed empirically 2026-08-20 (not the traditional bookmaker "favorite gets a
      # minus sign" notation this was originally written to assume - real spread_line correlates
      # +0.43 with real home margin, its negation correlates -0.43, so the sign here was backwards
      # from when this column was first added). Flip the sign only for the away team's own row,
      # since a positive home spread means the away team is the underdog by that same amount.
      team_implied_spread = case_when(
        !is.na(game_date_home) ~ spread_line_home,
        !is.na(game_date_away) ~ -spread_line_away,
        TRUE ~ NA_real_
      ),
      games_played = 1L,
      passing_touchdowns = coalesce(passing_tds, 0L),
      rushing_touchdowns = coalesce(rushing_tds, 0L),
      receiving_touchdowns = coalesce(receiving_tds, 0L),
      touchdowns = passing_touchdowns + rushing_touchdowns + receiving_touchdowns,
      total_touchdowns = touchdowns,
      interceptions = coalesce(passing_interceptions, 0L),
      fumbles = coalesce(rushing_fumbles, 0L) + coalesce(receiving_fumbles, 0L) + coalesce(sack_fumbles, 0L),
      fumbles_lost = coalesce(rushing_fumbles_lost, 0L) + coalesce(receiving_fumbles_lost, 0L) + coalesce(sack_fumbles_lost, 0L),
      turnovers = interceptions + fumbles_lost,
      carries = coalesce(carries, 0L),
      receiving_targets = coalesce(targets, 0L),
      receptions = coalesce(receptions, 0L),
      receiving_yards = coalesce(receiving_yards, 0L),
      total_yards = coalesce(passing_yards, 0L) + coalesce(rushing_yards, 0L) + coalesce(receiving_yards, 0L),
      # target_share/air_yards_share/wopr already come through unchanged from load_player_stats()
      # (nothing upstream drops them) - no coalesce needed here, they're just passed through so
      # write_player_game_stats can select them by name. snap_count stays NA here on purpose -
      # box-score data has no real snap counts; import_snap_counts.R enriches it separately (same
      # UPDATE-after-the-fact shape as import_pfr_advanced_rushing.R), same reason drops stays NA.
      snap_count = NA_integer_,
      drops = NA_integer_,
      source_url = paste0("https://nflverse.nflverse.com/player-stats/", season),
      fetched_at = Sys.time(),
      created_at = Sys.time(),
      updated_at = Sys.time()
    ) |>
    # rowwise() makes pick(everything()) capture one row at a time; without it, pick() sees the
    # whole table and to_json_text() serializes every row/column into one giant blob repeated on
    # every row.
    rowwise() |>
    mutate(raw_payload = to_json_text(as.list(pick(everything())))) |>
    ungroup()
}

# Loads nflverse weekly player stats for one season, joined to espn_athlete_id and to
# nfl_schedules so game_date/home_away are populated correctly. Falls back to NA game_date/
# home_away if nfl_schedules has no rows for the season (e.g. import_schedules.R hasn't run yet).
fetch_and_shape_player_week_stats <- function(con, season_arg, espn_athlete_ids = NULL) {
  players <- nflreadr::load_players() |>
    select(gsis_id, espn_id) |>
    mutate(espn_athlete_id = as.character(espn_id))

  schedules <- tryCatch(
    dbGetQuery(
      con,
      "select week, home_team, away_team, gameday, roof, surface, temp, wind, spread_line, total_line
       from nfl_schedules where season = $1",
      params = list(season_arg)
    ),
    error = function(err) {
      data.frame(
        week = integer(), home_team = character(), away_team = character(), gameday = as.Date(character()),
        roof = character(), surface = character(), temp = numeric(), wind = numeric(),
        spread_line = numeric(), total_line = numeric()
      )
    }
  )

  schedules_home <- schedules |>
    transmute(
      week, team = home_team, game_date_home = as.Date(gameday), roof_home = roof, surface_home = surface,
      temp_home = temp, wind_home = wind, spread_line_home = spread_line, total_line_home = total_line
    )
  schedules_away <- schedules |>
    transmute(
      week, team = away_team, game_date_away = as.Date(gameday), roof_away = roof, surface_away = surface,
      temp_away = temp, wind_away = wind, spread_line_away = spread_line, total_line_away = total_line
    )

  shaped <- nflreadr::load_player_stats(seasons = season_arg) |>
    filter(season == season_arg) |>
    left_join(players, by = c("player_id" = "gsis_id")) |>
    filter(!is.na(espn_athlete_id), espn_athlete_id != "")

  if (!is.null(espn_athlete_ids)) {
    shaped <- shaped |> filter(espn_athlete_id %in% espn_athlete_ids)
  }

  shaped |>
    left_join(schedules_home, by = c("week", "team")) |>
    left_join(schedules_away, by = c("week", "team")) |>
    shape_player_week_stats()
}

# Same idea as fetch_and_shape_player_week_stats(), but fetches a whole RANGE of seasons in one
# nflverse call instead of one call per season. A single load_player_stats(seasons = 2016:2025)
# call was measured at ~5s total; the equivalent 10 separate single-season calls took roughly
# twice as long combined, for barely more data - most of the per-call cost is fixed overhead, not
# proportional to data volume. Use this for the per-player career backfill
# (import_player_history.R), which otherwise pays that per-call overhead up to 10x for one
# player - that's the dominant cost in a new player's first-ever page load.
fetch_and_shape_player_week_stats_multi <- function(con, seasons, espn_athlete_ids = NULL) {
  players <- nflreadr::load_players() |>
    select(gsis_id, espn_id) |>
    mutate(espn_athlete_id = as.character(espn_id))

  schedules <- tryCatch(
    dbGetQuery(
      con,
      "select season, week, home_team, away_team, gameday, roof, surface, temp, wind, spread_line, total_line
       from nfl_schedules where season between $1 and $2",
      params = list(min(seasons), max(seasons))
    ),
    error = function(err) {
      data.frame(
        season = integer(), week = integer(), home_team = character(), away_team = character(),
        gameday = as.Date(character()), roof = character(), surface = character(), temp = numeric(),
        wind = numeric(), spread_line = numeric(), total_line = numeric()
      )
    }
  )

  schedules_home <- schedules |>
    transmute(
      season, week, team = home_team, game_date_home = as.Date(gameday), roof_home = roof,
      surface_home = surface, temp_home = temp, wind_home = wind, spread_line_home = spread_line,
      total_line_home = total_line
    )
  schedules_away <- schedules |>
    transmute(
      season, week, team = away_team, game_date_away = as.Date(gameday), roof_away = roof,
      surface_away = surface, temp_away = temp, wind_away = wind, spread_line_away = spread_line,
      total_line_away = total_line
    )

  shaped <- nflreadr::load_player_stats(seasons = seasons) |>
    left_join(players, by = c("player_id" = "gsis_id")) |>
    filter(!is.na(espn_athlete_id), espn_athlete_id != "")

  if (!is.null(espn_athlete_ids)) {
    shaped <- shaped |> filter(espn_athlete_id %in% espn_athlete_ids)
  }

  if (nrow(shaped) == 0) {
    return(shaped)
  }

  shaped |>
    left_join(schedules_home, by = c("season", "week", "team")) |>
    left_join(schedules_away, by = c("season", "week", "team")) |>
    shape_player_week_stats()
}

# Upserts (real INSERT ... ON CONFLICT, backed by the uq_player_game_stats_player_season_week
# constraint on player_id/season/week - see PlayerGameStat.java) a shaped stats data frame produced
# by fetch_and_shape_player_week_stats() into player_game_stats, and records the run in
# etl_import_runs. Rows for players not found in the local `players` table are skipped. Returns
# the number of rows written.
#
# Was DELETE-then-INSERT until 2026-08-20, when that approach was found to silently wipe every
# enrichment column this function doesn't itself set (snap_count, and everything import_pfr_
# advanced_rushing.R/import_nextgen_stats.R/import_snap_counts.R write via UPDATE afterward)
# whenever a box-score refresh ran for an already-enriched player/season/week - real data loss,
# discovered when a coefficient-calibration regression found passing_cpoe/receiving_yac_above_
# expectation/etc. completely empty despite having been populated days earlier. The ON CONFLICT DO
# UPDATE SET clause below deliberately excludes every enrichment-only column (and created_at, so a
# refresh doesn't reset a row's original creation timestamp) - anything not listed there is simply
# left alone by Postgres on conflict, which is the actual fix: this function can now only ever
# touch the columns it owns, instead of relying on script run order to self-heal afterward.
write_player_game_stats <- function(con, stats, job_name, notes) {
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
    params = list(job_name, Sys.time(), notes)
  )$id[[1]]

  written <- 0L
  for (i in seq_len(nrow(stats))) {
    row <- stats[i, ]
    player_id <- dbGetQuery(
      con,
      "select id from players where espn_athlete_id = $1",
      params = list(row$espn_athlete_id[[1]])
    )

    if (nrow(player_id) == 0 || is.na(player_id$id[[1]])) {
      next
    }
    player_id <- player_id$id[[1]]

    dbExecute(
      con,
      "
      insert into player_game_stats (
        player_id, game_date, season, week, home_away, opponent_name, opponent_team_id,
        games_played, passing_yards, rushing_yards, total_yards, passing_touchdowns,
        rushing_touchdowns, receiving_touchdowns, touchdowns, total_touchdowns, interceptions,
        fumbles, fumbles_lost, turnovers, snap_count, carries, receiving_targets, receptions,
        receiving_yards, drops, roof, surface, temp_fahrenheit, wind_mph, team_implied_spread,
        game_total_line, target_share, air_yards_share, wopr, source_url, raw_payload, fetched_at,
        created_at, updated_at
      ) values (
        $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25,
        $26,$27,$28,$29,$30,$31,$32,$33,$34,$35,$36,$37,$38,$39,$40
      )
      on conflict (player_id, season, week) do update set
        game_date = excluded.game_date,
        home_away = excluded.home_away,
        opponent_name = excluded.opponent_name,
        opponent_team_id = excluded.opponent_team_id,
        games_played = excluded.games_played,
        passing_yards = excluded.passing_yards,
        rushing_yards = excluded.rushing_yards,
        total_yards = excluded.total_yards,
        passing_touchdowns = excluded.passing_touchdowns,
        rushing_touchdowns = excluded.rushing_touchdowns,
        receiving_touchdowns = excluded.receiving_touchdowns,
        touchdowns = excluded.touchdowns,
        total_touchdowns = excluded.total_touchdowns,
        interceptions = excluded.interceptions,
        fumbles = excluded.fumbles,
        fumbles_lost = excluded.fumbles_lost,
        turnovers = excluded.turnovers,
        carries = excluded.carries,
        receiving_targets = excluded.receiving_targets,
        receptions = excluded.receptions,
        receiving_yards = excluded.receiving_yards,
        drops = excluded.drops,
        roof = excluded.roof,
        surface = excluded.surface,
        temp_fahrenheit = excluded.temp_fahrenheit,
        wind_mph = excluded.wind_mph,
        team_implied_spread = excluded.team_implied_spread,
        game_total_line = excluded.game_total_line,
        target_share = excluded.target_share,
        air_yards_share = excluded.air_yards_share,
        wopr = excluded.wopr,
        source_url = excluded.source_url,
        raw_payload = excluded.raw_payload,
        fetched_at = excluded.fetched_at,
        updated_at = excluded.updated_at
      ",
      params = list(
        player_id,
        row$game_date[[1]],
        row$season[[1]],
        row$week[[1]],
        row$home_away[[1]],
        row$opponent_name[[1]],
        row$opponent_team_id[[1]],
        row$games_played[[1]],
        row$passing_yards[[1]],
        row$rushing_yards[[1]],
        row$total_yards[[1]],
        row$passing_touchdowns[[1]],
        row$rushing_touchdowns[[1]],
        row$receiving_touchdowns[[1]],
        row$touchdowns[[1]],
        row$total_touchdowns[[1]],
        row$interceptions[[1]],
        row$fumbles[[1]],
        row$fumbles_lost[[1]],
        row$turnovers[[1]],
        row$snap_count[[1]],
        row$carries[[1]],
        row$receiving_targets[[1]],
        row$receptions[[1]],
        row$receiving_yards[[1]],
        row$drops[[1]],
        row$roof[[1]],
        row$surface[[1]],
        row$temp_fahrenheit[[1]],
        row$wind_mph[[1]],
        row$team_implied_spread[[1]],
        row$game_total_line[[1]],
        row$target_share[[1]],
        row$air_yards_share[[1]],
        row$wopr[[1]],
        row$source_url[[1]],
        row$raw_payload[[1]],
        row$fetched_at[[1]],
        row$created_at[[1]],
        row$updated_at[[1]]
      )
    )
    written <- written + 1L
  }

  dbExecute(
    con,
    "update etl_import_runs set finished_at = $1, row_count = $2 where id = $3",
    params = list(Sys.time(), written, run_id)
  )

  written
}

# nflverse team abbreviations differ from ESPN's (which our `teams` table uses) for the Rams
# ("LA" vs "LAR") and Washington ("WAS" vs "WSH"), plus three real franchise relocations nflverse's
# historical data still codes under the old city (our `teams` table only has the current identity,
# since Team is a current-roster/branding snapshot, not a historical record) - the Raiders
# (Oakland through 2019, "OAK" -> "LV"), the Chargers (San Diego through 2016, "SD" -> "LAC"), and
# the Rams' earlier stint (St. Louis through 2015, "STL" -> "LAR", same target as "LA" above).
# Found 2026-08-19 while backfilling team_strength_ratings back to 2014 - none of the box-score/
# defense scripts had hit these codes before since they hadn't pulled data that far back. Every
# other abbreviation matches. Apply this before looking up a team by abbreviation against Postgres.
to_espn_team_abbreviation <- function(nflverse_abbreviation) {
  dplyr::case_when(
    nflverse_abbreviation == "LA" ~ "LAR",
    nflverse_abbreviation == "WAS" ~ "WSH",
    nflverse_abbreviation == "OAK" ~ "LV",
    nflverse_abbreviation == "SD" ~ "LAC",
    nflverse_abbreviation == "STL" ~ "LAR",
    TRUE ~ nflverse_abbreviation
  )
}

# Upserts (via delete-then-insert on team_id/game_date, matching the table's actual unique index)
# a shaped team-defense stats data frame into team_defense_game_stats, and records the run in
# etl_import_runs. Rows for teams not found in the local `teams` table are skipped (a team only
# exists there once it's been synced via ESPN at least once). Returns the number of rows written.
write_team_defense_stats <- function(con, stats, job_name, notes) {
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
    params = list(job_name, Sys.time(), notes)
  )$id[[1]]

  written <- 0L
  for (i in seq_len(nrow(stats))) {
    row <- stats[i, ]
    if (is.na(row$game_date[[1]])) {
      next
    }

    team_id <- dbGetQuery(
      con,
      "select id from teams where abbreviation = $1",
      params = list(row$team_abbreviation[[1]])
    )

    if (nrow(team_id) == 0 || is.na(team_id$id[[1]])) {
      next
    }
    team_id <- team_id$id[[1]]

    dbExecute(
      con,
      "delete from team_defense_game_stats where team_id = $1 and game_date = $2",
      params = list(team_id, row$game_date[[1]])
    )

    dbExecute(
      con,
      "
      insert into team_defense_game_stats (
        team_id, game_date, season, season_type, week, home_away, opponent_name, opponent_team_id,
        points_allowed, total_yards_allowed, passing_yards_allowed, receiving_yards_allowed,
        rushing_yards_allowed, turnovers_forced, interceptions, fumble_recoveries, sacks,
        source_url, raw_payload, fetched_at, created_at, updated_at
      ) values (
        $1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22
      )
      ",
      params = list(
        team_id,
        row$game_date[[1]],
        row$season[[1]],
        row$season_type[[1]],
        row$week[[1]],
        row$home_away[[1]],
        row$opponent_name[[1]],
        row$opponent_team_id[[1]],
        row$points_allowed[[1]],
        row$total_yards_allowed[[1]],
        row$passing_yards_allowed[[1]],
        row$receiving_yards_allowed[[1]],
        row$rushing_yards_allowed[[1]],
        row$turnovers_forced[[1]],
        row$interceptions[[1]],
        row$fumble_recoveries[[1]],
        row$sacks[[1]],
        row$source_url[[1]],
        row$raw_payload[[1]],
        row$fetched_at[[1]],
        row$created_at[[1]],
        row$updated_at[[1]]
      )
    )
    written <- written + 1L
  }

  dbExecute(
    con,
    "update etl_import_runs set finished_at = $1, row_count = $2 where id = $3",
    params = list(Sys.time(), written, run_id)
  )

  written
}

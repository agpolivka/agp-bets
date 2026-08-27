#!/usr/bin/env Rscript
#
# Computes a simple Elo rating (with a margin-of-victory adjustment) for every team from real,
# already-stored game results (nfl_schedules - already fully ingested by import_schedules.R, no
# new external data needed). This is the foundation for Priority 5's team head-to-head model:
# unlike the player-prop heuristic, this can be validated directly against real final scores we
# already have at real season-spanning scale, without depending on Priority 1's paid odds decision
# at all.
#
# Elo (with a margin-of-victory multiplier, same shape FiveThirtyEight's public NFL model uses) was
# picked over a simpler "average recent point differential" because raw point-differential
# averaging can't tell "beat bad teams by a lot" from "beat good teams by a little" - Elo naturally
# accounts for opponent strength through the same expected-score mechanism chess ratings use, while
# still being a small, explainable, hand-tuned system rather than a trained black box (consistent
# with this project's existing heuristic philosophy - see PlayerPredictionService's own class doc).
#
# Every team starts at 1500. After each completed game, both teams' ratings update based on how
# much the actual result beat/missed the pre-game expectation, scaled by how lopsided the game was.
# A 25% reversion toward 1500 is applied at each season boundary (standard practice - a team's true
# strength carries over between seasons, but not fully, since rosters/coaching change).
#
# This is a full recompute every run (Elo is sequential/stateful - not sensibly done as an
# incremental per-season UPDATE the way the box-score/PFR/NGS scripts are), but cheap: ~3,300 games
# across 12 seasons is trivial to replay in full each time.

script_args <- commandArgs(trailingOnly = FALSE)
script_file <- sub("^--file=", "", script_args[grep("^--file=", script_args)])
source(file.path(dirname(normalizePath(script_file)), "_common.R"))

START_RATING <- 1500
K_FACTOR <- 20
HOME_FIELD_ADVANTAGE_ELO <- 55
SEASON_REVERSION_FRACTION <- 0.25

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
  params = list("compute_team_strength_ratings", Sys.time(), "Full Elo recompute from nfl_schedules")
)$id[[1]]

log_info("Loading completed games from nfl_schedules...")

games <- dbGetQuery(
  con,
  "
  select game_id, season, week, gameday, home_team, away_team, home_score, away_score
  from nfl_schedules
  where home_score is not null and away_score is not null
  order by gameday asc, game_id asc
  "
)

log_info("Replaying Elo across ", nrow(games), " completed games...")

team_directory <- dbGetQuery(con, "select id, abbreviation from teams")
team_id_by_abbreviation <- setNames(team_directory$id, team_directory$abbreviation)

ratings <- new.env()
get_rating <- function(team) {
  if (is.null(ratings[[team]])) START_RATING else ratings[[team]]
}

output_rows <- list()
current_season <- NA_integer_

for (i in seq_len(nrow(games))) {
  game <- games[i, ]

  if (!is.na(current_season) && game$season[[1]] != current_season) {
    for (team in ls(ratings)) {
      ratings[[team]] <- START_RATING + (1 - SEASON_REVERSION_FRACTION) * (ratings[[team]] - START_RATING)
    }
  }
  current_season <- game$season[[1]]

  home_team <- game$home_team[[1]]
  away_team <- game$away_team[[1]]
  home_score <- game$home_score[[1]]
  away_score <- game$away_score[[1]]

  home_rating_before <- get_rating(home_team)
  away_rating_before <- get_rating(away_team)

  expected_home <- 1 / (1 + 10^((away_rating_before - home_rating_before - HOME_FIELD_ADVANTAGE_ELO) / 400))
  actual_home <- if (home_score > away_score) 1 else if (home_score < away_score) 0 else 0.5

  margin <- abs(home_score - away_score)
  mov_multiplier <- log(margin + 1)

  home_rating_after <- home_rating_before + K_FACTOR * mov_multiplier * (actual_home - expected_home)
  away_rating_after <- away_rating_before - (home_rating_after - home_rating_before)

  ratings[[home_team]] <- home_rating_after
  ratings[[away_team]] <- away_rating_after

  output_rows[[length(output_rows) + 1]] <- data.frame(
    team = home_team, opponent_team = away_team, home_away = "home",
    game_date = game$gameday[[1]], season = game$season[[1]], week = game$week[[1]],
    points_scored = home_score, points_allowed = away_score,
    rating_before = home_rating_before, rating_after = home_rating_after
  )
  output_rows[[length(output_rows) + 1]] <- data.frame(
    team = away_team, opponent_team = home_team, home_away = "away",
    game_date = game$gameday[[1]], season = game$season[[1]], week = game$week[[1]],
    points_scored = away_score, points_allowed = home_score,
    rating_before = away_rating_before, rating_after = away_rating_after
  )
}

ratings_df <- bind_rows(output_rows) |>
  mutate(team_abbreviation = to_espn_team_abbreviation(team))

log_info("Writing ", nrow(ratings_df), " team-game rating rows...")

dbExecute(con, "delete from team_strength_ratings")

written <- 0L
skipped_abbreviations <- character()
for (i in seq_len(nrow(ratings_df))) {
  row <- ratings_df[i, ]
  # Single-bracket indexing on a named vector returns NA (not an error) for a name that isn't
  # present - unlike `[[`, which throws "subscript out of bounds" and would crash the whole run
  # on the first unmapped abbreviation instead of just skipping that one row.
  team_id <- team_id_by_abbreviation[row$team_abbreviation[[1]]]
  if (is.na(team_id)) {
    skipped_abbreviations <- c(skipped_abbreviations, row$team_abbreviation[[1]])
    next
  }

  dbExecute(
    con,
    "
    insert into team_strength_ratings (
      team_id, game_date, season, week, opponent_team_id, home_away,
      points_scored, points_allowed, rating_before, rating_after, created_at, updated_at
    ) values ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
    ",
    params = list(
      team_id, row$game_date[[1]], row$season[[1]], row$week[[1]], row$opponent_team[[1]], row$home_away[[1]],
      row$points_scored[[1]], row$points_allowed[[1]], row$rating_before[[1]], row$rating_after[[1]],
      Sys.time(), Sys.time()
    )
  )
  written <- written + 1L
}

dbExecute(
  con,
  "update etl_import_runs set finished_at = $1, row_count = $2 where id = $3",
  params = list(Sys.time(), written, run_id)
)

dbCommit(con)
if (length(skipped_abbreviations) > 0) {
  log_info(
    "Skipped ", length(skipped_abbreviations), " rows with unmapped team abbreviations: ",
    paste(sort(unique(skipped_abbreviations)), collapse = ", ")
  )
}
log_info("Done. Wrote ", written, " team strength rating rows.")

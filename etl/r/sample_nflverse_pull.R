#!/usr/bin/env Rscript

user_lib <- "C:/Users/agpol/Documents/R/win-library/4.6"
if (dir.exists(user_lib)) {
  .libPaths(c(user_lib, .libPaths()))
}

suppressPackageStartupMessages({
  library(nflreadr)
  library(dplyr)
})

message("Loading nflverse sample...")

sample_players <- tryCatch(
  {
    nflreadr::load_players() |>
      dplyr::select(espn_id, display_name, first_name, last_name, position, latest_team, headshot) |>
      dplyr::slice_head(n = 5)
  },
  error = function(err) {
    message("Failed to load nflverse players data: ", conditionMessage(err))
    quit(status = 1)
  }
)

message("Loaded ", nrow(sample_players), " players.")
print(sample_players)

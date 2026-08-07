# ETL

This directory is the home for offline data ingestion, backfill, and transformation work.

## Purpose

- Keep batch data work separate from the live Java application.
- Make room for R-based nflverse jobs and any supporting SQL or helper scripts.
- Store repeatable import/backfill logic here instead of mixing it into request-time code.

## Intended Layout

- `etl/r/` - R scripts or small R helpers for NFL data pulls and backfills.
- `etl/sql/` - SQL helpers, migration-adjacent scripts, or one-off data transforms.
- `etl/jobs/` - future job wrappers or orchestration scripts if we need them.

## Working Rule

Anything that can run offline, on a schedule, or as a backfill belongs here rather than
inside the live API surface.

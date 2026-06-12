# AGP Bets Work Plan

This document tracks the active product direction and the next implementation targets.
It is intentionally lightweight and should evolve as the project matures.

## Current Focus

The backend and basic player workflow are in a good place. The next work should improve
the historical data depth, make derived stats more useful, and start shaping the real app UI.

## Priority 1: Expand Stat Backfill

Goal: store more than the most recent slice of player game logs whenever ESPN exposes it.

Why this matters:

- Derived insights are only as good as the historical sample behind them.
- A single season or five-game window is useful for display, but not enough for deeper trend work.
- Better history unlocks rolling averages, splits, opponent trends, and future prediction features.

Desired behavior:

- Backfill all available historical game logs for a player when possible.
- Prefer a complete historical dataset over a short recent window.
- Keep raw game logs as the source of truth and derive summaries from them.
- Make backfill incremental so we can resume or refresh without duplicating work.

Implementation ideas:

- Add a historical sync path that walks older seasons or pages of logs.
- Track the last successfully ingested game date per player.
- Allow manual and background refreshes to fill gaps over time.
- Keep the raw game stat table normalized and append-only where possible.

## Priority 2: Expand Derived Stats

Goal: make player insights more useful than basic averages.

Useful derived views:

- last 3, last 5, and season-to-date averages
- home vs away splits
- opponent splits
- recent trend direction
- usage-based views like targets, carries, snaps, and share of offense
- position-aware summaries for QB, RB, WR, and TE

Principles:

- Derive from stored game logs instead of duplicating data.
- Keep the raw storage model separate from the analytical model.
- Make derived summaries easy to recompute if the rules change later.

## Priority 3: UI Direction

Goal: shape both the current data-heavy view and a more polished browser experience.

Track A: operational / inspection UI

- Keep the current database-style player browser.
- Use it to inspect raw player records and derived stats.
- Make it easy to verify backend correctness during development.

Track B: consumer-facing app UI

- Start shaping the real user experience for searching players and viewing insights.
- Make the layout feel intentional and easier to scan.
- Add a clearer player detail flow with summary cards, charts, and stat history.
- Separate “admin/debug” workflows from “browse and analyze” workflows when needed.

## Longer-Term Direction

- Add better historical analysis views.
- Introduce caching for common lookups.
- Build prediction-ready features from the stat history.
- Support team-level analysis after player history is solid.

## Working Agreement

- Store raw data once.
- Derive insights from stored data.
- Keep player ingestion reliable.
- Prefer incremental improvements that make future betting/model work easier.


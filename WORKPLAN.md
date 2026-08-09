# AGP Bets Work Plan

This document tracks the active product direction and the next implementation targets.
It should stay lightweight and evolve as the project matures.

## Current Focus

The project now has a real React/Vite frontend, dedicated player pages, automatic player
hydration, automatic stat sync on page load, player headshots on the detail screen, and a
better search flow that favors stored players first and ESPN fallback second.

Historical player game-log data now actually comes from R/nflverse instead of ESPN scraping
(see Priority 2 and Priority 5 below), and is the real source of truth for `player_game_stats`.

**Product direction as of 2026-08-07:** the goal is a player page that confidently tells a user
what a stat is going to be - one decisive projected number per stat (e.g. "127 rushing yards"),
not a hedged range. The prediction endpoint already exists and is wired end-to-end
(`PlayerPredictionService` -> `PlayerPredictionController` -> the frontend's prediction cards);
the work now is making the number itself trustworthy, not building the feature from scratch. The
backend still computes a confidence interval/score internally (kept for potential future use),
but the player page intentionally does not display it.

A **"sleeper picks"** feature - surfacing predictions where our model diverges meaningfully from
a market betting line - is an explicit future addition, not near-term. It's a hard blocker on
data we don't have at all yet (no betting-line/odds ingestion exists in this codebase), not just
a "later" UI feature, so it needs a real data-source decision before any implementation work.

Since the last update: the opponent-adjustment baseline now uses real computed league averages
(done), `nfl_schedules` has been backfilled for 2014-2024 in addition to 2025 (done), and team
defensive stats (`TeamDefenseGameStat`) moved from ESPN per-game scraping to nflverse, matching
the player game-log migration's architecture - see Recently Completed. Next session should pick
up, roughly in order:

1. Continue derived-insights cleanup (Priority 3) beyond what's done - the summary cards and
   game log table now read cleanly (see Recently Completed), but there's likely more to trim/
   reorganize as real usage surfaces what's actually noisy. The FAQ page (`/faq`) is a natural
   place to keep growing a "data model breakdown" if more explanatory content needs a home.
2. Consider backtesting the prediction model's blend weights (0.65/0.35) and opponent-adjustment
   coefficients against real completed-game outcomes, now that there's meaningfully more
   populated history to compare against.
3. Keep an eye on whether more players end up with orphaned/incomplete rows the way the old ESPN
   game-log leftovers did (see Recently Completed) - there's no automated check for this today.
4. Team identity/branding (name, logo, location, venue) intentionally stays ESPN-sourced - it's a
   deliberate product call (UI-only, not model-critical), not an oversight to revisit.

The premium or special-user layer should remain a future phase, after the derived-stat
experience is strong enough to justify it.

Current data-source direction:

- Use `nflverse` as the main historical NFL data source for modeling and backfill work.
- Keep ESPN as a supplemental source for player lookup, headshots, and any missing live
  metadata that nflverse does not provide cleanly.
- Treat Java/Spring Boot as the application runtime and API layer.
- Treat R as an offline ingestion/backfill utility - triggered async by the backend (per-player
  backfill on first search, data-driven scheduled refresh for stored players) or run manually,
  never something the frontend waits on synchronously.
- Keep Postgres as the shared source of truth for stored raw rows and derived summaries.
- Run nflverse batch imports from the `etl/` folder rather than request-time Java calls.

## Recently Completed

- Moved the frontend to React + Vite.
- Reworked the UI into a red, black, and orange visual direction.
- Added a landing page with search, featured players, and a future login area.
- Added dedicated player detail pages with routing.
- Wired player detail pages to backend player and insight endpoints.
- Enabled automatic player hydration when a searched player is not already stored.
- Enabled automatic stat sync on player page load.
- Removed the manual user-facing stat sync flow from the main player experience.
- Improved candidate matching behavior in the UI:
  - top 5 results
  - hidden confidence score
  - no-result guidance when no strong match is found
- Added ESPN player headshots with a safe initials fallback.
- Started the backend team-data foundation:
  - new `Team` storage for identity, branding, venue, and record context
  - new `TeamDefenseGameStat` storage for game-level defensive results
  - admin sync endpoints for direct team sync and syncing teams linked to stored players
  - upcoming opponent lookup support now feeding player insights
- Improved player search behavior:
  - local database search runs first
  - ESPN candidate search is limited to offensive positions
  - search results are cached with a short TTL
  - search ranking now handles typos better while keeping the list tight
- Fixed a bug in ESPN candidate traversal so valid players are not skipped by nested payload shapes.
- Moved historical player game-log stats from ESPN scraping to R/nflverse as the source of truth:
  - new `etl/r/import_player_history.R` backfills one player's full career on-demand, triggered
    automatically the first time they're searched with no stored history (mirrors the existing
    ESPN auto-hydration UX rather than batch-backfilling every player up front)
  - new `etl/r/refresh_stored_players_weekly.R` refreshes already-stored players' current-season
    stats, triggered by a data-driven check instead of a fixed cron slot - the backend looks at
    whether `nfl_schedules` shows a completed game since the last successful refresh, so
    Thursday/Saturday/Sunday/Monday games are all covered without hardcoding game days, and it
    also runs on app startup so local dev catches up without needing to run continuously
  - added a concurrency cap on R script execution after multiple simultaneous backfills were
    found competing for memory/CPU and making every request slow together
  - fixed several real bugs found while wiring this up: `game_date`/`home_away` were never being
    populated (missing join to `nfl_schedules`), `raw_payload` was serializing the entire
    season's table into every row instead of one row each, and `nflreadr::load_player_stats()`
    was silently returning only the current season regardless of what season was requested
  - confirmed via real stored data (a full 7-season player history, all rows sourced from
    nflverse) that the ESPN game-log path was genuinely dead, then deleted it entirely:
    `EspnPlayerGameStatMapper`, `PlayerGameStatSnapshot`, the ESPN season-loop in
    `PlayerGameStatService`, and the now-unused game-log-specific methods on `EspnPlayerClient`
    (`fetchAthleteStatisticsLog`, `buildAthleteStatisticsLogUrl`, `fetchAthleteGameLogPage`,
    `buildAthleteGameLogUrl`) and their now-orphaned private helpers
- Fixed a Hibernate/Postgres bug where `@Lob` on `rawPayload` string fields (across `Player`,
  `PlayerGameStat`, `Team`, `TeamDefenseGameStat`) made the driver try to read plain text columns
  as CLOB-via-OID and crash on any non-null read - removed `@Lob` from all four entities.
- Fixed player game-stat recency ordering: it was sorting by `game_date DESC`, and Postgres sorts
  NULLs first on DESC, so any season without imported schedule data sorted ahead of real recent
  games. Now orders by `season DESC, week DESC`, which is always populated regardless of
  schedule-import state.
- Fixed `stop-dev.sh` so a Docker Desktop connectivity failure doesn't abort the script (under
  `set -e`) before it reaches the step that kills stray backend/frontend processes.
- Confirmed the ESPN game-log path was truly dead (a full 7-season player history sourced
  entirely from nflverse, zero ESPN URLs), then deleted it: `EspnPlayerGameStatMapper`,
  `PlayerGameStatSnapshot`, the ESPN season-loop in `PlayerGameStatService`, the now-dead
  game-log-specific `EspnPlayerClient` methods, and their orphaned private helpers.
- Deleted the dead `player/derived/*` scaffolding (two unimplemented interfaces,
  `PlayerPredictionService`/`PlayerDerivedStatsService`, plus matching unused model records and
  an unused DTO) - nothing implemented or wired them; the real, live prediction path is
  `player/service/PlayerPredictionService`.
- Simplified the player page's prediction cards to lead with the single projected number per
  stat; dropped the confidence-interval range and confidence-score badge from the UI (product
  decision - one decisive number to act on, not a hedge). The backend still computes and returns
  both in case a future feature needs them.
- Thoroughly documented `PlayerPredictionService` (class + method-level) - what each constant
  means, which numbers are hand-picked vs. real, and what "not currently displayed" fields are
  kept for.
- Root-caused and fixed the team-sync staleness bug flagged last session: `EspnTeamClient`
  (`site.api.espn.com`) was 403ing on every single request - confirmed via direct testing that
  this ESPN host blocks a bare `Mozilla/5.0` User-Agent and Java's own default
  (`Java-http-client/*`) alike, but allows a value like `curl/8.16.0`. Team sync (identity +
  upcoming opponent, which the prediction model's opponent adjustment depends on) now actually
  works. Separately, `TeamRefreshWorker`/`PlayerRefreshService`'s async refresh methods were
  silently swallowing any exception (the caller discards the returned future without inspecting
  it) - this is exactly why the ESPN failure went unnoticed for over a month. Both now catch and
  log failures explicitly.
- Added `start-backend.ps1`/`start-backend.sh`/`stop-backend.sh` for running the backend (+
  Postgres) without the frontend - useful for API-only iteration/testing.
- Replaced the prediction model's hardcoded opponent-adjustment baselines (225/110/125/21) with
  real league-average figures computed from stored `TeamDefenseGameStat` rows across every team
  (22 of 32 teams synced as of this session), cached 6h; falls back to the old hardcoded numbers
  only if no team-defense data exists at all yet.
- Found and cleaned up 314 orphaned `player_game_stats` rows (across 15 players, several
  starters - Justin Herbert, Dak Prescott, Tua Tagovailoa, A.J. Brown, etc.) left over from the
  deleted ESPN game-log scraper: they had `season = NULL`, which hit the exact same
  Postgres-sorts-NULLs-first-on-DESC issue already fixed for `game_date`, so these unusable rows
  were sorting as each player's "most recent" game and silently feeding their predictions. Worse,
  because the sync flow only checks "are there zero stored rows" before triggering a backfill,
  these players could never self-heal - they'd have been stuck on broken data indefinitely.
  Deleted the orphaned rows and re-backfilled all 15 players via R.
- Backfilled `nfl_schedules` for seasons 2014-2024 (2025 was already done), matching the full
  range of seasons actually present in stored `player_game_stats`, then re-ran the per-player R
  backfill for all 44 currently-stored players so their historical rows pick up real
  `game_date`/`home_away` instead of NULL.
- Moved team defensive stats (`TeamDefenseGameStat`) from ESPN per-game-summary scraping to
  nflverse (`etl/r/import_team_defense.R`), mirroring the player game-log migration's
  architecture. `nflreadr::load_team_stats()` gives the whole league in one vectorized pull per
  season (`def_sacks`/`def_interceptions`/`fumble_recovery_opp` map directly to
  `sacks`/`interceptions`/`fumbleRecoveries`; yards/points allowed are derived by looking up the
  opponent's own offensive output in the same game via `nfl_schedules`). Handles the two
  franchises where nflverse's team abbreviation differs from ESPN's ("LA"->"LAR", "WAS"->"WSH")
  via a crosswalk in `_common.R`. Synced all 32 teams via ESPN first (10 had never been synced,
  so had no `team_id` row for defense stats to link to) - team identity/branding stays ESPN-
  sourced, per explicit product direction (UI-only, not model-critical). Triggered on the same
  data-driven schedule as the player stats refresh (`StatsRefreshWorker` now runs both scripts).
  Also fixed a real bug found along the way: `opponentAdjustment`/`leagueDefenseAverages`
  (predictions) and `TeamDefenseSummaryService` were blending every stored season together
  unfiltered, so an opponent's years-old defense weighed the same as their current one - both now
  scoped to a ~20-game/~1-season recency window. Removed the now-dead ESPN game-summary scraping
  code (`EspnTeamClient.fetchGameSummary`/`buildGameSummaryUrl`, `TeamSyncService.upsertDefenseGame`
  and its private helpers) and the write-only `Team.seasonOffensive*` fields (confirmed nothing
  ever read them).
- Redesigned the prediction cards (`PredictionCard` in `frontend/src/App.jsx`): metrics now show
  a real label + unit ("Rushing Yards" / "yds") instead of the raw camelCase field name
  (`rushingYards`), yardage rounds to whole numbers while count stats (receptions/TDs/turnovers)
  keep one decimal, the number is the visual hero (larger, gradient-accented), and the old raw
  "Sample: 58 | Opponent adjustment -1.56" line was replaced with "58 games on record" plus a
  sign-based "Favorable matchup"/"Tough matchup" pill (no raw adjustment number shown - it wasn't
  meaningful to a casual reader). Also tightened the section copy/tagline to a decisive framing
  consistent with dropping the confidence-interval display.
- Stripped the explanatory paragraph text from every section header on the player detail page
  (Predictions, Player snapshot, Game log, Derived splits) - just kicker + title now, per user
  direction that "the data should read for itself." Moved the removed "how predictions work"
  content to a new `/faq` page (`FaqPage` in `App.jsx`, linked from both the top nav and a small
  "How this works" link next to the Predictions header) instead of deleting it outright, so
  there's a home to grow a fuller data-model breakdown later if needed.
- Visually verified all of the above with a real headless-browser pass (Playwright, screenshots
  taken and reviewed) against the live dev app with real data - prediction cards, trimmed section
  headers, and the new FAQ page all render correctly with zero console errors. This closes out
  the "not yet checked in an actual browser" caveat from the previous entry.
- Removed the "Ready to load" / "Stored already" text from player search result cards
  (`SearchResultCard` in `App.jsx`) - the end user shouldn't see internal storage-state details;
  that's now FAQ-only territory if it ever needs explaining.
- Fixed two real bugs behind slow first-time player loads (Priority 9):
  - `etl/r/import_player_history.R` was fetching one nflverse season at a time in a loop (up to
    10 separate `nflreadr::load_player_stats()` calls for a new player's default backfill depth).
    Verified via direct timing that nflverse's per-call overhead is largely fixed cost, not
    proportional to data volume - a single batched call for 10 seasons took ~5s versus roughly
    double that for 10 separate calls. Added `fetch_and_shape_player_week_stats_multi()` to
    `_common.R` (shares the shaping logic with the existing single-season fetcher via a new
    `shape_player_week_stats()` helper) and rewrote the script to fetch in one batched call per
    "hop" back through a player's career instead of one call per season.
  - Found a real correctness bug while building the batched fetcher: passing an R vector as a
    `season = ANY($1)` query parameter doesn't bind as a Postgres array the way it looks like it
    should - RPostgres treats it as scalar batch-parameters instead, so the query failed with
    "malformed array literal" every time. Worse, because this ran inside the backfill's DB
    transaction, the failed query aborted the whole transaction, and every later query failed
    with "current transaction is aborted" - the R script always errored out. Fixed by using
    `season between $1 and $2` instead (the caller always passes a contiguous range, so this is
    both correct and simpler than a properly-escaped array literal).
  - Found and fixed the actual dominant bottleneck, a real correctness bug in the async wiring:
    `PlayerHistoryBackfillService.requestBackfillIfNeeded` called `this.backfillAsync(...)` on
    itself - same-class self-invocation bypasses Spring's `@Async` proxy entirely, so the
    "background" backfill was actually running synchronously and blocking the `stats/sync` HTTP
    request for the full R script runtime (measured ~10-12s). Merged the two methods so
    `@Async` sits directly on the method that's actually called cross-bean (from
    `PlayerGameStatService`), which is what the proxy can actually intercept. Verified end-to-end
    against a genuinely fresh, never-before-stored player (hydrate -> `stats/sync` -> poll
    `stats`): the sync call itself now returns in under 100ms instead of blocking for ~10s+, and
    the backfilled rows land in the database within a couple seconds in the background.

## Priority 1: Derived Stats and Insights Cleanup

Goal: make the derived insight layer more trustworthy, more useful, and easier to build on.

Desired behavior:

- Tighten the formulas and output for the derived player stats we care about most.
- Make sure the top summary cards are meaningful across QB, RB, WR, and TE.
- Add stronger last-X-game views, home/away splits, opponent splits, and trend summaries.
- Keep raw game stats as the source of truth and derive everything cleanly on top.
- Add more matchup-aware derived views that can later support premium-only insights.
- Keep the prediction layer honest by tracking the missing inputs that impact quality most:
  - incomplete snap counts and drops
  - missing team offense model
  - incomplete defensive matchup features
  - missing injury, weather, Vegas, and projected-usage inputs

Done: the first player prediction endpoint exists and is live end-to-end
(`player/service/PlayerPredictionService`, weighted-average + opponent adjustment, documented in
the class itself). Product direction as of 2026-08-07: show one decisive projected number per
stat, not a confidence interval/score - the player page dropped that display even though the
backend still computes it. See "Current Focus" above.

Also done (2026-08-07): the opponent-adjustment baseline (previously hardcoded 225/110/125/21
league-average guesses) is now computed from every stored team's `TeamDefenseGameStat` history
(`leagueDefenseAverages()`, cached 6h, falls back to the old hardcoded numbers only if no
team-defense data is stored at all). The per-metric coefficients (0.08-0.10 yardage, 0.02-0.03
receptions/touchdowns) are still hand-picked, not backtested.

Implementation ideas:

- Audit each derived field against the stored game stat rows.
- Prioritize offensive betting views first:
  - QB passing/rushing/turnover summaries
  - RB rushing/receiving opportunity summaries
  - WR/TE target, reception, and yardage summaries
- Add clearer role-aware insight cards instead of one generic layout for every position.
- Expose a `PlayerPrediction` response model from the backend so the frontend can render it directly.
- Add a small prediction service layer that can later swap from weighted averages to a more advanced model.

## Priority 2: Historical Backfill and Stat Freshness

Goal: keep expanding historical player data and keep it current without overloading ESPN.

Desired behavior:

- Backfill more player game history when available.
- Make sure player stats are updated reliably over time.
- Keep automatic refresh behavior safe and predictable.
- Continue improving the historical sample for future derived insights.

The season/game-log backfill strategy and scheduled refresh path described above are now
implemented via R/nflverse - see Priority 5 and Recently Completed for details. What's left:

Implementation ideas:

- Done (2026-08-07): `nfl_schedules` backfilled for 2014-2024 (2025 was already done) and all
  currently-stored players re-backfilled, so `game_date`/`home_away` are populated across their
  full stored history, not just the current season. Note this is a one-time catch-up, not
  automatic - a newly backfilled player only gets `game_date`/`home_away` for seasons that
  `nfl_schedules` already covers at the time they're searched; if a future season range is
  needed, `import_schedules.R` needs to be run for it first. Known residual gap: seasons before
  2014 are still uncovered (currently only affects Russell Wilson's 2010-2013 rows, ~95 rows) -
  extend the schedule backfill further back if a long-career player needs it.
- Preserve raw game stats as the source of truth and derive insights from them.
- Add lightweight search-result caching and consider broader preload jobs only after the public player experience needs them.
- Fill in stat fields that materially improve prediction quality, especially:
  - snap counts
  - drops
  - any missing team/game metadata that reduces matchup quality

## Priority 3: Clean Up Displayed Information

Goal: make the player page show the right information in the right places without noise.

Desired behavior:

- The most important stats should be obvious at a glance.
- Position-specific players should not see misleading or empty top-box values.
- Internal or overly technical wording should be removed from the user-facing screen.
- Error and loading states should stay polished and simple.

Done (2026-08-09): removed the explanatory paragraph text under every section header on the
player page and moved it to a dedicated `/faq` page instead - see Recently Completed.

Implementation ideas:

- Rework the top summary cards by position group.
- Decide which stats belong in the hero area, summary cards, and game log table.
- Trim fields that are technically present but not useful yet.
- Continue moving the app away from a database-viewer feel.

Not started: the player detail page's intro blurb ("Loaded from search and matched to this
player profile.") is static and dull. User wants either real, non-fabricated useful info (injury
designation, news suggesting a usage bump) or something less boring in its place - explicitly
not made-up content. Investigated what ESPN actually exposes (2026-08-09), confirmed via direct
API calls, not guessed:

- The existing athlete-profile endpoint (`EspnPlayerClient.fetchAthleteById`,
  `site.web.api.espn.com/.../athletes/{id}`) already returns `athlete.status` - a real,
  always-present field (`{"name": "Active", "type": "active", ...}`, or "Questionable"/"Out"/etc.
  for an injured player). Cheap to surface: no new external call needed, just a new field on the
  existing player DTO.
- ESPN's league-wide injuries feed (`site.api.espn.com/apis/site/v2/sports/football/nfl/injuries`)
  has real, ESPN-sourced injury notes per player when one exists: a `status` (Questionable/Out/IR/
  etc.), a `shortComment` (e.g. "The Cardinals placed Blount (neck) on injured reserve
  Saturday."), and a fuller sourced `longComment`. This is genuinely useful, real content - not
  something we'd be inventing - but it's a single ~9MB payload covering all 32 teams, so it can't
  be called per player-page-load; it needs to be pulled periodically (daily, since injury
  statuses change more often than weekly game stats) and cached in Postgres keyed by
  `espn_athlete_id`, mirroring the existing `TeamRefreshScheduler`/`StatsRefreshScheduler`
  pattern rather than a new synchronous per-request call.
- Most players (like the two tested during this investigation) simply have no injury note at all
  - that's the common case, not the exception - so the UI needs a sensible default for a healthy,
  unremarkable player, not just a good state for the rare injured one.

Proposed shape (not yet built, needs a product decision on scope before implementing): show a
real status pill (Active/Questionable/Out/etc. from `athlete.status`) in place of the current
static sentence for every player, and when a stored injury note exists, surface its
`shortComment` next to it. This is a real feature (new DB table or column, a new scheduled
refresh job, a new backend field, and a frontend change) rather than a copy tweak, so it's being
logged here rather than implemented inline.

## Priority 4: Prediction Inputs and Confidence

Goal: make the prediction layer realistic by improving the inputs it depends on before we try to make it fancy.

Desired behavior:

- Derived predictions should clearly show a projection mean and a confidence interval.
- The system should explain when confidence is low because of limited data or high variance.
- We should improve the inputs that matter most before relying on predictions for public use.

Implementation ideas:

- Add missing stat ingestion work for snap counts and drops.
- Build a cleaner team offense model to pair with the defensive data already being collected.
- Expand matchup features so predictions can account for the opponent more realistically.
- Add optional future inputs for injury, weather, Vegas lines, and projected usage once the core system is stable.

## Priority 5: Data Pipeline and R Integration

Goal: define a durable ingest path so historical and derived NFL data can grow without
making the live app dependent on slow external calls.

Desired behavior:

- Use R for batch ingestion, backfills, and optional analytics jobs.
- Keep Java responsible for serving stored data and orchestrating refresh jobs.
- Make it easy to swap or add data sources later without rewriting the app layer.
- Avoid having the frontend or live player page depend on synchronous R execution.
- Prefer scheduled or manually triggered ETL runs over Java invoking R per request.

This is now implemented for player game-log stats:

- `etl/r/import_player_history.R` - per-player full-career backfill, triggered on-demand (async,
  non-blocking) the first time a player has no stored game stats.
- `etl/r/refresh_stored_players_weekly.R` - recurring refresh for already-stored players, scoped
  to their current season only.
- `StatsRefreshDueChecker` / `StatsRefreshWorker` / `StatsRefreshScheduler` (backend `etl`
  package) trigger the refresh on a data-driven basis - checks `nfl_schedules` against the last
  successful run in `etl_import_runs` - plus on app startup, instead of a fixed cron slot.
- `RScriptRunner` wraps the actual `Rscript` process invocation with a concurrency cap so
  multiple simultaneous backfills don't compete for memory/CPU.

Implementation ideas:

- Decide which remaining datasets (if any) should move from ESPN to nflverse.
- Add a proper scheduler/job runner under `etl/jobs/` if the current Java-triggered model stops
  being enough - not needed yet.

## Priority 6: Team Identity and Visuals

Goal: round out player pages with team-aware visuals once team data is modeled more cleanly.

Desired behavior:

- Show the team's primary logo alongside the player data.
- Keep player/team responsibilities clean in the codebase.
- Use ESPN assets where possible and add a fallback if needed.

Implementation ideas:

- Add team asset handling once team modeling is introduced.
- Avoid stuffing long-term team concerns directly into player-only components.
- Reuse the current player visual card once team branding is ready.

## Priority 7: Team Defense Data Foundation

Goal: store enough team and defense history to support matchup-driven player insights and later predictions.

Desired behavior:

- Store core team identity data:
  - team name
  - team logo
  - location
  - stadium details including indoor/outdoor
  - record and standings summary
- Store defensive game history in a form we can derive from later.
- Keep team data modeled separately from player data, while still making it easy for player insights to look up opponents.
- Leave room for weekly and season-level defensive rankings later.

Implementation ideas:

- Use `Team` for identity and branding data (ESPN-sourced, kept that way deliberately).
- Use `TeamDefenseGameStat` as the source of truth for defensive history - as of 2026-08-07 this
  is populated by `etl/r/import_team_defense.R` (nflverse), not ESPN. See Recently Completed.
- Derive defensive season totals and matchup summaries from stored game rows rather than storing every aggregate up front.
- Done (2026-08-07): `receivingYardsAllowed` now comes from nflverse's real `receiving_yards`
  column instead of duplicating `passingYardsAllowed` as a stand-in.
- Add follow-up work for:
  - weekly defensive rank calculations
  - season aggregate defensive views
  - team logos on player pages
  - investigating whether defensive scheme is available anywhere reliable
  - Fixed (2026-08-07): the upcoming-opponent auto-refresh wasn't completing because
    `EspnTeamClient` was 403ing on every request - ESPN's `site.api.espn.com` blocks a bare
    `Mozilla/5.0` User-Agent (and Java's own default), confirmed by direct testing; switched to a
    verified-working value. Also added logging to `TeamRefreshWorker`/`PlayerRefreshService`'s
    async paths, which had been silently swallowing exceptions - that's why this went unnoticed
    for over a month.

## Priority 8: Candidate Matching Cleanup

Goal: keep search results tight and typo-tolerant as usage grows.

Desired behavior:

- Return at most 5 candidate matches.
- If no candidate clears a 25 percent confidence threshold, return no results and prompt the user to check spelling.
- Do not show match confidence directly in the UI.
- Sort results by best match first.
- Continue improving typo tolerance for near matches.

Implementation ideas:

- Tighten backend candidate ranking and threshold logic further if needed.
- Keep search output clean and player-centric.
- Preserve typo tolerance without overwhelming the user with low-quality matches.

## Priority 9: Performance and Responsiveness

Goal: make the app feel fast, especially for the two hottest user paths.

Desired behavior:

- Player searches should return quickly and feel responsive.
- Player detail loading should avoid unnecessary waiting when data is already available.
- Background hydration should not block the user experience.
- We should keep an eye on performance as derived stats and more data sources are added.

Implementation ideas:

- Add or tune caching for common player lookups and search results.
- Reduce repeated backend calls during a single player page load.
- Make background refresh behavior more incremental where possible.
- Revisit slow queries or joins if player search or page load starts lagging.
- Consider a background preload strategy for high-value offensive players using team rosters or ESPN listings if first-hit latency becomes a bigger issue.
- Keep prediction performance in mind once prediction endpoints start using larger history windows.
- Done: capped concurrent R script execution (`agp.etl.max-concurrent-scripts`) after simultaneous
  new-player backfills were found competing for memory/CPU - each script loads a full nflverse
  season into memory, so uncapped concurrency made every in-flight backfill slow together.
- Done (2026-08-09): cut first-time player load latency - see Recently Completed for the two bugs
  fixed (batched nflverse fetch in `import_player_history.R`, and a same-class `@Async`
  self-invocation bug that was making the "background" backfill block the request synchronously).
  The remaining ~4-6s the R backfill itself takes in the background is mostly fixed
  Rscript/R-runtime process-startup overhead (measured directly - package loading plus data
  fetch only accounts for part of the wall-clock gap), not something addressable without a
  bigger architectural change like a persistent R service process. Since the backfill is now
  correctly non-blocking, that residual time no longer holds up the HTTP response - it's not
  worth chasing further right now.

## Longer-Term Direction

- Add richer player trend and split views.
- Add role-specific player cards and comparison views.
- Introduce caching for common lookups.
- Evaluate a background preload job for likely offensive players once usage warrants it.
- Build prediction-ready features from the stored history.
- Expand from player analysis into team-level analysis later.
- Add premium-user-only derived views once the public experience is stable.
- Replace Hibernate-only schema generation with Flyway once the team tables and next database changes settle down.
- After team data modeling is in place, introduce Flyway migrations and move away from
  relying on Hibernate schema creation as the long-term database strategy.
- Add a simple ETL scheduler or runner once the batch scripts settle, rather than calling R from the live app.

## Working Agreement

- Store raw data once.
- Derive insights from stored data.
- Keep player ingestion reliable.
- Prefer incremental improvements that make future betting and model work easier.

# AGP Bets Work Plan

This document tracks the active product direction and the next implementation targets.
It should stay lightweight and evolve as the project matures.

## Current Focus

**Core mission (reframed 2026-08-10 at explicit user direction):** this app's reason to exist is
finding player-stat projections that consistently disagree with - and beat - the lines FanDuel/
DraftKings actually post, surfaced through an admin view that flags a set number of "sleeper" picks
each week. That's the product. A trustworthy-looking player page that never gets checked against a
real line is not the goal; it's a means to it. Priorities 1-4 below are the direct dependency chain
for that outcome, in order, and take precedence over everything else in this document. Priorities
5-8 are supporting infrastructure/UX work that's mostly done or in steady maintenance mode - real,
but not what's currently gating the core mission.

Why this reframe matters technically, not just as prioritization: beating an efficient market
isn't primarily a model-architecture problem. FanDuel/DraftKings prop lines are already built from
public box-score data plus more - snap/target share trends, Vegas-implied team totals, weather,
practice-report trajectory, coverage tendencies - and get sharpened further by sharp-money line
movement. A more sophisticated model trained on a *thinner* feature set than the market doesn't
manufacture edge, it just curve-fits the same limited signal harder. So the real path to the core
mission is, in order: (1) get real market lines into the system at all - without them "beating the
line" isn't even measurable, let alone something to optimize for; (2) validate whatever prediction
approach against real outcomes, then against real lines once they exist, choosing model complexity
based on backtest evidence rather than instinct; (3) close the gap between our feature set and what
the market already prices in; (4) only then build the surfacing/admin feature on top of a model
that's actually been shown to hold up. See Priority 1-4 below for the full breakdown of each step.

What's built so far, supporting this: a real React/Vite frontend with dedicated player pages,
automatic player hydration and stat sync, a search flow that favors stored players first, and a
live prediction endpoint (`PlayerPredictionService`) producing one decisive per-stat projection
(no hedged range shown, by product decision) from real nflverse-sourced game-log history instead
of ESPN scraping. First-time player loads are fast and non-blocking (see Recently Completed for
the async/caching bugs fixed to get there), and the player page shows a real ESPN-sourced
availability status instead of placeholder text. None of this is the core mission itself - it's
the substrate the core mission needs to sit on, and most of it is now stable enough that Priority
1 can become the real focus.

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

## Priorities From Last Session / Priorities For Next Session

Rotating list, not a permanent one - things left unfinished or discovered at the end of the most
recent session that should be tackled first thing next time, before moving on to anything else.
If something here turns out to be durable/ongoing rather than a near-term loose end, promote it
into the numbered Priority list below instead of leaving it here. Expected to be mostly or fully
cleared out at the start of most sessions.

Cleared tonight (2026-08-12): both items from the previous rotation are superseded by real work -
see Recently Completed and Priority 2/3 for detail. What's actually next, discovered as part of
tonight's work:

1. **Outcome-MAE backtest is now diluted by non-skill-position players.** A side effect of tonight's
   full-catalog backfill (see Recently Completed): `PredictionBacktestService` walks every stored
   player, and `metricsForPosition`'s `default` branch hands kickers/punters/long-snappers/O-line/
   defensive players the same `rushingYards`/`receivingYards`/`receptions`/`touchdowns` metric list
   as running backs - their real values are structurally near-zero, so the outcome-MAE numbers now
   mix in tens of thousands of meaningless comparisons alongside the real skill-position ones. The
   market-line backtest doesn't have this problem (a player only shows up there if they actually have
   a crosswalked prop line, which self-selects skill positions). Needs a decision next session:
   either scope `metricsForPosition`'s default case to an empty list for clearly non-skill positions,
   or scope the outcome backtest itself to skill positions only - don't trust outcome-MAE numbers at
   face value until this is resolved.
2. **Phase 3 (NGS advanced metrics) and Phase 4 (PFR defense aggregation) are still unbuilt** - see
   Priority 3 for what shipped tonight (Phase 1: weather/Vegas; Phase 2: PFR advanced rushing) and
   what's still scoped but not started, per the original plan
   (`C:\Users\agpol\.claude\plans\elegant-leaping-scone.md`).
3. Decide whether Priority 2's sequenced recommendation (calibrate the existing heuristic before
   considering a trained model) still holds now that market-line hit rates have a far bigger sample
   (147-960 comparisons/metric, up from 56-152) and landed close to the prior read - see Priority 2
   for the actual numbers.

## Recently Completed

Entries here should carry a date and get pruned once they're settled/stable rather than "news" -
git history (`git log`) is the authoritative permanent record, this section is short-term working
memory. As a rough rule of thumb, anything older than ~2 weeks or superseded by later work should
be removed rather than left to accumulate. (2026-08-12: removed a block of long-undated, weeks-old
entries from early React/Vite migration and initial team-data-foundation work per this policy -
still real, just no longer "recent" and already reflected in Current Focus's description of what's
built.)

- (2026-08-07) Simplified the player page's prediction cards to lead with the single projected number per
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
- (2026-08-09) Stripped the explanatory paragraph text from every section header on the player detail page
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
- (2026-08-09) Fixed two real bugs behind slow first-time player loads (Priority 8):
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
- (2026-08-09/10) Fixed two follow-on problems the fast-async fix above surfaced (both reported directly by the
  user after trying it): the page now loads so fast that a brand-new player's page rendered
  before their backfill finished, which (a) looked broken - a full page layout with
  near-empty-looking predictions, not obviously "still loading" - and (b) was actually wrong:
  `PlayerPredictionService` computed a projection from whatever handful of games existed at that
  instant (sometimes just 1) and cached it for the full 20-minute `CACHE_TTL`, so the bad number
  stuck around long after the backfill actually finished (confirmed directly - Geno Smith's real
  109-game history gave a normal ~198 passing-yard projection once the poisoned cache entry
  expired via a backend restart). Fixed both:
  - Split `PlayerHistoryBackfillService` into itself (synchronous gatekeeper + in-flight
    bookkeeping) plus a new `PlayerHistoryBackfillRunner` (`@Async`, actually runs the script) -
    this was needed anyway so `inFlightAthleteIds` updates synchronously on the calling thread,
    letting `isBackfillInProgress(athleteId)` give an accurate answer to a caller checking
    immediately after requesting a backfill, not just on a later poll. Exposed this as
    `backfillInProgress` on the `stats/sync` response.
  - `App.jsx`'s player-load flow now polls `stats/sync` (every 2s, up to 8 times) until
    `backfillInProgress` clears before fetching insights/predictions and revealing the page -
    the existing top-level loading state covers the wait, with its message swapped to "Loading
    full stat history..." while a backfill is in flight, instead of either reverting to a
    blocking backend call or showing a fast-but-wrong page.
  - Defense in depth on the backend: `PlayerPredictionService` now caches a prediction for only
    15s (`THIN_SAMPLE_CACHE_TTL`) instead of the full 20 minutes whenever it's built from fewer
    than 3 stored games - covers the case where the frontend's poll times out, or a genuinely new
    player just has very few games on record.
  - Verified end-to-end against a fresh player (Malik Nabers): first `stats/sync` call reported
    `backfillInProgress: true`, one 2s poll later it reported `false` with 19 games synced, and
    the very next predictions call returned realistic numbers (67 receiving yards, sample size
    19) on the first try - no stale/thin-sample result was ever cached.
- (2026-08-10) Replaced the player detail page's static, always-the-same blurb ("Loaded from search and
  matched to this player profile.") with a real ESPN-sourced availability status (Priority 5,
  first slice of the item scoped last session) - `Player.injuryStatus` now stores ESPN's
  `athlete.status.name` (e.g. "Active", "Day-To-Day", "Injured Reserve"), captured via the
  existing per-player hydration/refresh path (`EspnAthleteMapper.toSnapshot` ->
  `PlayerUpsertService`, no new external call needed) and exposed on `PlayerResponse`. The
  frontend now shows a status pill in the player info block (quiet/neutral for "Active", amber
  for anything else, red for IR/PUP/suspended) plus a one-line blurb only when there's something
  to say - real information instead of a repeated placeholder sentence, and nothing fabricated.
  - Important limitation found via direct testing against real injured players, not assumed:
    `athlete.status` is a **roster** status (on the active roster vs. IR/PUP/suspended/day-to-day),
    not the **weekly game-designation** (Questionable/Doubtful/Out for Sunday) that ESPN's
    separate league-wide injuries feed tracks. Confirmed directly - Jawaan Taylor and Zach
    Harrison, both listed "Out" for this week's game in the injuries feed, both show `Active` via
    `athlete.status`, since they're still on their team's active roster. So this feature reliably
    catches the less-common long-term-absence cases (IR/PUP/suspended) but currently misses the
    much more common weekly Questionable/Doubtful/Out designation. That gap is exactly what the
    already-scoped injuries-feed pull (see Priority 3) would close - this slice shipped first
    because it needed zero new infrastructure, not because it's the complete feature.
- Ingested a real, verified historical NFL player-prop odds window from `odds-api.io` (2026-08-11/12)
  to bootstrap Priority 2's backtest work - see Priority 1 below for the full provider research and
  Priority 2 for what this unlocks. New `odds` package (`PropOddsRawEvent`/`PlayerPropLine` entities,
  a `PropOddsProvider` interface with an `odds-api.io` adapter behind it, and a one-off
  `OddsHistoricalBackfillRunner`) backfilled all 76 settled NFL games from Dec 14, 2025 through the
  Feb 8, 2026 Super Bowl (the real, empirically-found start of this provider's historical odds
  coverage - confirmed live, not assumed) - 11,765 player-prop lines after fixing a real parsing bug
  (`"No Touchdown Scorer"`, a non-player placeholder label, was slipping through as if it were a real
  player). Player crosswalk reuses a newly-extracted `NameSimilarity` utility (pulled out of
  `EspnAthleteMapper`'s fuzzy-matching logic so both ESPN candidate search and this odds crosswalk
  share one implementation) with a conservative 0.85 auto-link threshold - unmatched rows keep their
  raw player name and score rather than being dropped, so linkage can improve later without
  re-fetching. Total spend: ~77 odds-api.io requests, comfortably inside the free tier's 500/day cap.
- (2026-08-11/12) Found and fixed the root cause of a player-search outage discovered immediately after the above
  (searches for real players like Rhamondre Stevenson or Christian McCaffrey were returning unrelated
  noise): ESPN's bulk `sports.core.api.espn.com/.../athletes` endpoint - the one the search
  feature's live fallback depended on - is currently returning corrupted data (confirmed by scanning
  all 21 pages directly: zero real players found, garbage entries like `" [Downed]"`/`" [Touchback]"`
  that look like play-by-play event codes, not athlete profiles). This is external/ESPN-side, not
  something in our code. Root-caused and replaced the whole search architecture rather than patching
  around the outage:
  - Wired up `etl/r/import_players.R` (an existing nflverse bulk-catalog script that had never been
    scheduled) into a new due-checker/worker/scheduler trio
    (`PlayerCatalogRefreshDueChecker`/`Worker`/`Scheduler`), following the same shape as
    `StatsRefreshDueChecker` but time-based (24h staleness against `etl_import_runs`) instead of
    game-completion-based, since roster changes aren't tied to game days and this needs to work
    during the offseason too.
  - Closed a real gap in that script: it hardcoded `team_id = NA` since nflverse doesn't carry ESPN
    team IDs. Fixed with a plain join against the `teams` table (already has both `abbreviation` and
    `espn_team_id` for all 32 teams) using the existing `to_espn_team_abbreviation()` crosswalk from
    the team-defense work - no new mapping table needed.
  - Found and fixed a second, serious bug while first running the updated script: `raw_payload =
    to_json_text(as.list(pick(everything())))` was missing `rowwise()`, so `pick(everything())`
    captured the *entire* filtered table (not one row) into every single row's `raw_payload` -
    bloated it to ~1.8MB *per player* (should be ~1.2KB), ballooned the `players` table to 7.4GB, and
    OOM'd the backend the first time it tried to load all players for search. This is the same bug
    class already documented and fixed elsewhere in `_common.R` for player-game-stats, just not
    caught here before. Also found and deleted 4 leftover corrupted rows (up to 11.4MB raw_payload
    each) from an earlier, pre-this-session run of the original buggy script; `players` table is now
    5.7MB total after a `VACUUM FULL`.
  - Filtered nflverse's all-time catalog (25,041 rows going back decades) down to `last_season >=
    (current year - 1)` (3,463 rows) - `status` turned out not to be a reliable "currently rostered"
    signal (a player whose `last_season` is 1988 still showed `status = "ACT"`).
  - Switched the insert loop from one `dbExecute()` per player to chunked multi-row upserts (500 rows
    per statement) - full-catalog runs now take ~35s instead of several minutes.
  - Rewrote `EspnPlayerIngestionService.findLocalPlayerCandidates` to score every stored player via
    the new `NameSimilarity` utility instead of a SQL `LIKE` substring pre-filter with crude 4-tier
    scoring - local search is now real fuzzy matching, not just a narrow exact-match check before
    falling through to ESPN.
  - Replaced the broken bulk-pagination ESPN fallback with a new `EspnPlayerClient
    .searchAthletesByQuery` wrapping ESPN's site-search endpoint (`site.api.espn.com/apis/search/v2`)
    - confirmed still reliable, and per explicit product direction, kept deliberately in place (not
    removed) so a genuinely uncached player (a free agent, or anyone signed since the last catalog
    refresh) is still findable, just as a rare fallback rather than the primary path. Found and
    documented a real host-specific User-Agent gotcha along the way: this endpoint 403s on
    `EspnPlayerClient`'s default `Mozilla/5.0` (needs `curl/8.16.0`, matching `EspnTeamClient`'s
    already-documented finding for the same host) - confirmed by direct testing before wiring it in,
    not assumed.
  - Verified end-to-end against live data: Rhamondre Stevenson now resolves correctly in ~0.6s
    (previously returned unrelated noise, or ~80s+/OOM crash mid-fix); a deliberately misspelled query
    ("Rhamondree Stevensen") still resolves to the right player via local fuzzy scoring; a genuinely
    uncached historical player (Joe Montana, not in the `last_season >= 2025` catalog) correctly
    triggers the new ESPN site-search fallback and returns accurate results.
- (2026-08-12, later) Shipped Priority 3's weather/Vegas-lines feature (Phase 1) and started PFR
  advanced rushing (Phase 2), run autonomously per explicit user direction (including scoping in a
  live weather API mid-session, beyond the original plan) - see
  `C:\Users\agpol\.claude\plans\elegant-leaping-scone.md` for the full design. Both verified
  end-to-end against real data, not just planned:
  - **Weather/Vegas (Phase 1)**: `nfl_schedules`'s `roof`/`surface`/`temp`/`wind`/`spread_line`/
    `total_line` (already ingested by `import_schedules.R`, never previously joined through) now
    flow into `player_game_stats` via a widened `_common.R` schedule join, and into a new
    `gameConditionsAdjustment` heuristic term (wind suppresses passing metrics only outdoors above
    10mph; the Vegas game total nudges yardage/touchdown projections relative to a 44.5 baseline).
    Added a genuinely live weather forecast path for upcoming games: new `WeatherForecastClient`
    hits Open-Meteo (free, no API key, verified live, 16-day forecast range), resolved via new
    `Team.upcomingGameTime`/`upcomingGameIsHome` fields (previously only had the date, not full
    kickoff time or home/away) captured off ESPN's existing team-sync payload. Verified end-to-end
    against a real synced game (KC's 2026-08-15 preseason game vs. the Rams): live forecast
    produced a real, small `conditionsAdjustment` (-0.12 on a ~119-yard passing projection), not a
    placeholder/zero.
  - **PFR advanced rushing (Phase 2, started per pre-approved scope)**: new
    `etl/r/import_pfr_advanced_rushing.R` enriches existing `player_game_stats` rows (`UPDATE`, not
    insert - a player only appears in PFR's charting if they already have a box-score row) with
    yards-before/after-contact and broken tackles, crosswalked via `pfr_player_id` ->
    `load_players()$pfr_id` -> `espn_id`. New `rushingQualityAdjustment` heuristic term nudges
    `rushingYards` using a player's own recent after-contact-yards-per-carry vs. a 2.6 yd/carry
    hardcoded league baseline (a quality/reliability tilt, not a second volume predictor - before +
    after contact yards already sum to the yardage the existing blend projects). Verified against
    real 2025-season data (2,352 rows updated): before-contact + after-contact yards sum exactly to
    stored `rushingYards` for every spot-checked row, and the live prediction endpoint shows a
    real, correctly-scoped (`rushingYards` only) nonzero adjustment for a real player (Kenneth
    Walker III: -2.70). Wired into the existing `StatsRefreshWorker` due-check/game-completion
    trigger rather than building a fourth scheduler trio.
  - Found and fixed two real bugs along the way: (1) `write_player_game_stats`'s INSERT had 37
    columns but only 36 `$n` placeholders (an off-by-one from adding the 6 new weather/Vegas
    columns) - would have silently failed on every write; caught immediately when the backfill
    first ran. (2) `PlayerPredictionService.metricsForPosition` threw an NPE switching on a null
    `position` - pre-existing, but never triggered before because the previously-stored 54 players
    all happened to have a position on file; only surfaced once the backfill (below) expanded
    coverage to players with missing position data. Fixed with `case null, default ->`.
  - One-off `etl/r/backfill_weather_vegas_columns.R` backfilled the new weather/Vegas columns into
    every existing row. It ended up doing more than scoped: querying `players` for "stored players"
    returned the full 3,482-player catalog, not just the 54 players who actually had game-stat
    history before tonight, so this became a full historical re-import for the whole catalog rather
    than a narrow column backfill - `player_game_stats` grew from 5,839 to 108,348 rows over about
    45 minutes. Harmless (the underlying write is a safe delete-then-insert upsert, confirmed via
    live `pg_stat_activity`/table-size checks mid-run that it wasn't repeating the earlier
    `raw_payload`-bloat bug) and a net positive for data depth, but it's what exposed the
    `metricsForPosition` NPE above and diluted the outcome-MAE backtest (see "Priorities For Next
    Session"). Updated backtest numbers below reflect this larger dataset.
- (2026-08-12) Cleared the full "Priorities From Last Session" rotation and built Priority 2's
  backtest harness for real - see Priority 2 for the actual numbers, this entry is the summary of
  how they were produced. Fixed `import_players.R`'s `active` flag (checked status strings that
  don't match nflverse's real short codes - IR/reserve players were incorrectly showing inactive).
  Re-ran the player-prop-line crosswalk against the full 3,476-player catalog: 9,266 of 9,445
  previously-unmatched lines resolved (98.5% total coverage, up from ~20%), exposing a real
  performance bug along the way (`NameSimilarity` recompiling its regex on every call - see
  Priority 2's entry for detail) that also silently affected live search at smaller scale. Built
  `PredictionBacktestService` + two endpoints (`/api/backtest/outcomes`, `/api/backtest/market-
  lines`), reusing the live prediction algorithm's exact scoring method rather than re-implementing
  it, fed genuinely point-in-time-correct historical inputs. Also switched the local dev workflow
  to the existing `start-backend.sh`/`stop-backend.sh` pair instead of ad-hoc `mvnw spring-boot:run`
  + manual `taskkill`.

## Priority 1: Betting Odds and Line Data Ingestion

Goal: get real market player-prop lines into this system. Everything the core mission depends on
- measuring whether a projection beats the market, training/calibrating against that objective,
and eventually surfacing sleeper picks - is blocked on this. Nothing here exists yet.

Why this is Priority 1 and not a "sleeper picks" implementation detail: without real lines, "did
we beat the market" isn't measurable at all, only "were we close to what actually happened" -
those are different questions, and only the first one matters for the core mission.

Desired behavior:

- Ingest current and, ideally, historical player-prop lines (passing/rushing/receiving yards,
  receptions, touchdowns, etc.) from a real sportsbook or odds aggregator, keyed to the same
  player identity already used elsewhere (`espn_athlete_id` or a crosswalk to it).
- Store lines as their own raw, source-of-truth rows (mirroring how `player_game_stats` and
  `TeamDefenseGameStat` are handled) - don't blend them into prediction output at ingest time.
- Keep this an offline/batch concern, not something the live player page depends on
  synchronously - consistent with the project's existing R/Java split.

Implementation ideas:

- Design the storage shape: probably a new `player_prop_lines` table (player, stat, line value,
  over/under prices, sportsbook, captured-at timestamp) - lines move over the week, so this
  needs to support multiple snapshots per player/stat, not just a single current value, if the
  goal is ever to compare our projection's timing against line movement.
- Decide whether ingestion lives in R (matches the existing ETL pattern) or is a new Java
  integration - likely R, to stay consistent with "R does batch ingestion, Java serves."
- This priority is a real product/cost decision (which provider, how much historical depth to
  buy) as much as an engineering one - needs explicit sign-off before implementation starts.

Provider options researched so far (2026-08-10, via live lookups against each provider's own
docs/pricing pages, not assumed) - no decision made yet, user is doing further research before
committing to one:

- **The Odds API** (the-odds-api.com): free tier is current-odds-only, no historical data at all.
  Historical odds, including player props, only unlock on the paid **Business tier ($99/month)**,
  covering player props from **May 3, 2023 onward** (~2.5-3 seasons of depth as of now), queryable
  per-event via a historical event-odds endpoint at 5-minute snapshot granularity. This is the
  deepest real historical player-prop option found so far.
- **SportsGameOdds** (sportsgameodds.com): free "Amateur" tier ($0, 2,500 objects/month, 10 req/
  min, 10-min update frequency) has live odds/props but explicitly **no historical data**. Rookie
  ($99/mo) also excludes historical data. Historical data only unlocks on **Pro ($299/month,
  unlimited objects)** - exact player-prop historical depth wasn't stated on the pricing page and
  needs a docs/support check before committing. Notable: "objects" are counted per top-level item
  returned (e.g. one event = one object regardless of how many markets/bookmakers are nested
  inside), which could make a paid tier's object budget go much further than it first appears -
  not yet confirmed whether the player-props endpoint specifically counts the same way. ToS
  confirmed to allow storing fetched data in our own database for internal use (expected/normal
  usage); what's prohibited is redistributing/reselling the raw data as a standalone product or
  feed, and all stored data must be deleted if the subscription is terminated.
- **balldontlie.io** (nfl.balldontlie.io): ruled out for historical player props specifically -
  their main props endpoint is explicitly live-only by design ("we do not store historical data"),
  not just paywalled. Their closest thing to historical, an "opening line" snapshot endpoint,
  requires their top **GOAT tier ($39.99/month)** and even then only covers "the most recently
  completed season and ongoing seasons" (~1 season of depth, not 5). Game-level (non-prop) odds
  only go back to 2025 season week 8. Cheap tiers ($9.99-$39.99/mo) could still be a reasonable
  fit for starting a free/cheap forward-looking live-odds capture in parallel with sourcing real
  historical depth elsewhere, just not for backfilling the past.
- **odds-api.io** (2026-08-11/12, docs lookups plus live requests against a free-tier API key -
  `docs.odds-api.io`/`api.odds-api.io`): docs alone were misleading here - the docs implied no NFL
  player-prop support (only basketball was called out) and stated historical odds go back to
  December 2025, but only the second claim held up once tested for real:
  - **Live current-odds test (real, verified):** pulled `/v3/odds` for a real upcoming NFL game
    (Seahawks @ Patriots, `usa-nfl` league, 135 events this season + 48 preseason) and got back real
    per-player prop markets with real names/lines. So the docs undersold this: NFL player props are
    real on this API, contradicting the earlier docs-only read.
  - **Live historical-odds boundary test (real, verified):** `/v3/historical/events` for September
    2025 returns real settled games fine (scores, no odds), but `/v3/historical/odds` for one of
    those games (Eagles @ Cowboys, 2025-09-05) came back with `"bookmakers": {}` - empty, confirming
    a real historical wall exists. Binary-searched the actual boundary against real 2025-season
    games: **Dec 1 and Dec 7, 2025 both return empty; Dec 14, 2025 returns a full market set** - so
    the wall sits somewhere in the **Dec 8-13, 2025** window, not "all of December" as the docs
    vaguely implied. Dec 14's response also resolved the earlier open question: `Rushing Yards O/U`
    and `Receptions O/U` are both present and fully populated (alongside `Passing Yards O/U`,
    `Receiving Yards O/U`, `Passing Touchdowns O/U`, `Interceptions O/U`, plus bonus markets like
    `Longest Reception/Rush/Pass O/U` and `Touchdown Scorers` First/Last/Anytime) - the full prop
    set you'd want is real and present, not a subset.
  - **What this actually buys you:** real, complete player-prop odds for every settled NFL game
    from roughly **Dec 14, 2025 through the Feb 2026 Super Bowl** - regular season weeks 15-18 plus
    the full playoff bracket, all already-completed games queryable right now. That's a genuine
    several-week backtest window with real lines and real outcomes, for free. It's nowhere near The
    Odds API's 2.5-3 season depth (Priority 2's eventual "beat the market across many seasons" goal
    still needs that), but it's real, verified, zero-cost data usable today for an initial-model
    backtest - good enough to validate the harness and get a first read on the heuristic before
    committing money to a deeper archive.
  - **Verdict:** viable as a *limited* historical source (single-season, ~2 months) for bootstrapping
    Priority 2's backtest harness now, at zero cost - not a replacement for The Odds API's multi-
    season depth if/when a paid decision is made. Also a real candidate for ongoing forward-looking
    live capture (free tier: 2 bookmakers, 100 req/hour, 500/day; paid PS49-229/month for broader
    bookmaker coverage and 5,000 req/hour).
  - API key stored in `agp-bets/.env` (git-ignored) for further live testing.
- Takeaway across all three: genuine multi-season (e.g. 5-season) historical player-prop archives
  don't appear to exist affordably anywhere checked so far - prop markets are newer and less
  commoditized than game-level spread/total lines, which by contrast are both older and more
  available (see Priority 3 - nflverse's own schedules data already includes free `spread_line`/
  `total_line` game-level history back to 2006, no new provider needed for that piece).

## Priority 2: Prediction Model Validation - Backtesting, Calibration, and Architecture

Goal: decide, with evidence rather than instinct, whether `PlayerPredictionService`'s hand-tuned
heuristic is sufficient, needs calibration, or needs to graduate to something trained - and once
Priority 1 exists, validate it against the metric that actually matters for the core mission:
would this projection have beaten the market line, not just "was it close to the outcome."

Scoping note on which line to benchmark against (2026-08-10): the industry-standard way to prove
real predictive skill is "closing line value" - did you beat the line right before kickoff, since
that's the market's most information-rich number. Every provider evaluated so far for Priority 1
only offers opening-line (or no) historical player-prop data, not a full closing-line time series,
so backtest results here should be read as a softer signal than true CLV, not proof of skill on
their own - and that caveat should travel with any reported results, not just live here. This
isn't necessarily a downgrade for the product itself, though: a user acting on a surfaced pick
would realistically want it early in the week anyway, before the value moves, so the opening line
may be the more practically relevant benchmark even if it's the methodologically softer one.

Current state: the heuristic is unvalidated by design. The class doc says so explicitly - the
blend weight (0.65 recent / 0.35 season) and the opponent-adjustment coefficients (0.08-0.10
yardage, 0.02-0.03 receptions/touchdowns) were picked to be "directionally reasonable," not fit
against outcomes.

**First real backtest results (2026-08-12)** - steps 1 and 2 below both actually built and run
against real data, not just planned. `PredictionBacktestService` (`player/service/`) reuses
`PlayerPredictionService.buildProjection` directly (made package-private for this, not
reimplemented, so the backtest can never silently drift from what the live endpoint computes),
fed point-in-time-correct inputs (games strictly before the target game, that game's real stored
opponent, league averages as of that date - not "now"). `GET /api/backtest/outcomes` and
`GET /api/backtest/market-lines`.

- Outcome MAE (step 1, 54 players, 2,177-3,360 games/metric depending on position coverage):
  errors landed proportional to each stat's real week-to-week variance (e.g. receiving yards MAE
  ~28 on a mean of ~57; passing yards MAE ~63 on a mean of ~225) - nothing that looked like a bug
  (no suspiciously-tiny errors suggesting leaked future data, no absurd outliers).
- Market-line hit rate (step 2 - the metric that actually matters for the core mission): receptions
  58.9% (n=151), passing touchdowns 57.1% (n=56), rushing yards 53.4% (n=103), receiving yards
  50.7% (n=152), passing yards 50.0% (n=56). Read this as a first signal, not a proven edge - most
  metrics sit near a coin flip (expected/healthy for an unvalidated heuristic against a real,
  efficient sportsbook line - a suspiciously high number would be the actual red flag), and standard
  vig means ~52.4%+ (at -110) is needed to be profitable, which only 3 of 5 metrics clear, on
  sample sizes (56-152) still small enough that this could be noise. Grows automatically as more
  odds data gets ingested and crosswalked.

**Updated results (2026-08-12, later, after shipping weather/Vegas + PFR advanced rushing - see
Recently Completed)** - re-run against a much larger dataset after a backfill unintentionally
expanded `player_game_stats` from 54 to the full 3,482-player catalog:

- Market-line hit rate (the metric that matters): receptions 53.8% (n=942, was 58.9% n=151),
  receiving yards 50.8% (n=960, was 50.7% n=152), rushing yards 52.9% (n=442, was 53.4% n=103),
  passing touchdowns 57.1% (n=147, was 57.1% n=56), passing yards 49.0% (n=147, was 50.0% n=56).
  Read as a good result: samples grew 3-6x and every metric landed within ~1-5 points of the prior
  read, no wild swings - consistent with the earlier numbers being a real (if small-sample) signal
  rather than noise that would reshuffle with more data. Still near coin-flip on most metrics
  (expected, see above); only passing touchdowns clears the ~52.4% vig threshold with real margin.
- Outcome MAE: **not comparable to the prior baseline and shouldn't be read at face value** - see
  "Priorities For Next Session." The backtest now walks the full catalog including kickers/punters/
  linemen/defensive players, whose `rushingYards`/`receivingYards`/etc. "projections" are
  structurally near-zero (real `meanActual` values like 4.65 rushing yards or 0.81 receptions
  confirm this - far below any real skill-position player's per-game average), diluting the
  aggregate MAE into something that no longer isolates skill-position accuracy the way the original
  54-player baseline did. Needs `metricsForPosition` or the backtest itself scoped to skill
  positions before this number means anything again.
- Found and fixed two real bugs while building this: (1) `NameSimilarity.normalize()`/`compact()`
  used `String.replaceAll(regex, ...)`, which recompiles the regex on every call with no caching -
  fine for one-off use, but a genuine bottleneck under any O(N*M) matching loop (confirmed directly:
  re-crosswalking ~9,400 unmatched prop lines against ~3,500 players took nearly 8 minutes before
  precompiling the patterns as static `Pattern` fields, seconds after). This was already silently
  costing the live player-search endpoint too, just at a scale too small to notice. (2) The
  backtest's first working version queried team-defense history and league averages fresh per
  backtested game (~5,700 games) instead of once - only 1,252 total stored defense rows, but the
  N+1 query pattern alone took over two minutes; fixed by preloading once and filtering in memory,
  down to ~5s for either endpoint.

Recommendation (flagged 2026-08-09/10, still needs explicit sign-off, not a final decision): you
don't need "ML" in the modern/deep-learning sense to meaningfully improve this, and model
architecture is not actually the bottleneck for the core mission right now - see "Current Focus"
for why beating an efficient market is primarily a data/feature problem, not a model-complexity
one. Sequenced path:

1. Backtest and calibrate what already exists first, against real completed-game outcomes (MAE
   per stat is a reasonable starting metric). There's now enough real nflverse-sourced game
   history for this. Needs no new architecture - same R stack already used for batch analytics,
   pointed at a fitting problem instead of an ETL one. Not blocked on Priority 1.
2. Once Priority 1 exists, rebuild the backtest around the metric that actually matters: how
   often would this projection have been on the correct side of the market line, and by how
   much, net of vig - not raw prediction error. This is the real success metric for the core
   mission and doesn't exist yet in any form. See the scoping note above on opening vs. closing
   line - benchmark against whatever line depth is actually available, but caveat results
   accordingly rather than treating them as proof of skill.
3. Only escalate to a trained model (ridge/linear regression per stat, computed periodically in
   R, coefficients stored and served by Java - the same "R computes offline, Java serves" pattern
   already used for team defense averages) if step 1 or 2 show the heuristic has a real ceiling.
4. Don't reach for gradient boosting, neural nets, or a dedicated ML pipeline unless steps 1-3
   demonstrably hit a ceiling with a richer feature set (see Priority 3) already in place - a
   more expressive model trained on the same thin features as today won't manufacture edge, it
   will just overfit noise harder. Every constant in the current heuristic is explainable in one
   sentence today (that's what the `notes` field on each projection is for); don't give that up
   without a proven reason to.

Open question that needs an explicit answer before escalating past step 1: how much "black box"
logic is acceptable, given the current heuristic's biggest strength is that a projection can
always be explained in one sentence.

Implementation ideas:

- Steps 1 and 2 (outcome MAE, market-line hit rate) are done - see the 2026-08-12 results above.
  Market-line hit rate now has a much bigger, still-near-coin-flip sample; outcome MAE needs a
  skill-position scoping fix before it's trustworthy again (see "Priorities For Next Session").
- Not yet decided: whether the current near-50%/bigger-sample market-line results are enough
  evidence to revisit the sequenced recommendation above. This is the actual open call now -
  everything before it was infrastructure to get here.

## Priority 3: Prediction Input and Feature Completeness

Goal: close the gap between what our model sees and what a sportsbook's line already prices in -
this is what actually creates the possibility of a real edge once Priority 1's lines exist to
compare against, more so than model architecture (see Priority 2).

Desired behavior:

- Add missing stat ingestion that materially improves prediction quality: snap counts, drops,
  target share trends (not just topline box-score totals).
- Build a cleaner team offense model to pair with the defensive data already being collected.
- Expand matchup features so predictions account for the opponent more realistically (coverage
  tendencies, pace of play, not just aggregate yards/points allowed).
- ~~Add weather and Vegas-derived features~~ **Done (2026-08-12)** - see Recently Completed.
  `gameConditionsAdjustment` covers wind (live-forecast-aware for upcoming games, real historical
  values for backtesting) and the Vegas game total; `team_implied_spread` is stored/backtestable
  but deliberately not wired into the live heuristic yet (see the plan doc - its effect is
  genuinely bidirectional and needs its own backtest read before picking a coefficient direction).
  PFR advanced rushing (yards before/after contact, broken tackles) also shipped as a first slice
  of this priority's "richer player-level input" goal, via a new `rushingQualityAdjustment` term.
  Still unbuilt from the original four-phase plan
  (`C:\Users\agpol\.claude\plans\elegant-leaping-scone.md`): NGS advanced metrics (CPOE, rush yards
  over expected, separation/cushion) and PFR defense aggregation (per-defender stats rolled up to
  team-game level for opponent-adjustment) - real, scoped, not started.
- Close the weekly-availability gap found while building the first injury-status slice (see
  Priority 5 and Recently Completed): `athlete.status` only reflects roster status (IR/PUP/
  suspended/day-to-day), not the weekly Questionable/Doubtful/Out-for-Sunday designation that
  actually moves lines and should move projections. Needs ESPN's league-wide injuries feed
  (`site.api.espn.com/apis/site/v2/sports/football/nfl/injuries`), pulled periodically (daily) and
  cached in Postgres keyed by `espn_athlete_id` - real, sourced content (`status` +
  `shortComment`/`longComment`), not something to fabricate. This serves both the model (a real
  predictive feature) and the player page (a richer, still-honest status blurb).

Implementation ideas:

- Prioritize whichever input has the clearest, cheapest path to real signal first - the weekly
  injury designation (above) is scoped and ready to build; snap counts/target share need a
  source decision (nflverse likely has this - verify before assuming a new ingestion path is
  needed).
- Treat this priority as ongoing rather than a one-time project - re-visit after Priority 2's
  backtest harness exists, since it can tell you which missing input would actually move the
  accuracy needle instead of guessing.

## Priority 4: Sleeper-Pick Detection and Admin View

Goal: the actual payoff feature - an admin view that surfaces a set number of players each week
where the model's projection diverges meaningfully, and correctly, from the real market line.

This is explicitly gated on Priorities 1-3 being substantially in place. Building this on top of
an unvalidated model and no real line data would produce a feature that looks like it works
without any evidence it actually does - worse than not having it, for something users would bet
real money against. Not started; no implementation detail decided yet.

Desired behavior (high-level, to be refined once the dependencies above land):

- For a given week, rank stored players by the size and reliability of the gap between our
  projection and the captured market line.
- Surface only picks the backtest (Priority 2) suggests are trustworthy - not just the largest
  raw gaps, since a large gap from an unreliable projection is noise, not signal.
- Keep this admin-only, separate from the public player-page experience, until it's proven out.

Implementation ideas:

- Don't scope implementation details until Priority 1 (lines exist) and Priority 2 (a validated
  "beat the line" backtest exists) are further along - premature design here is likely to be
  wrong in ways only real data would reveal.

## Priority 5: Player Page Experience and Derived Stats

Goal: keep the public-facing player page trustworthy and free of noise. Real, ongoing work, but
supporting the core mission rather than gating it - a good-looking page over an unvalidated model
doesn't advance the mission on its own.

Desired behavior:

- The most important stats should be obvious at a glance, position-aware, and free of internal/
  technical wording.
- Keep raw game stats as the source of truth and derive everything cleanly on top.
- Error and loading states should stay polished and honest about what's actually happening.

Done: the first player prediction endpoint is live end-to-end and shows one decisive projected
number per stat, not a hedged range (2026-08-07 product decision). Section headers were stripped
of explanatory paragraph text in favor of a dedicated `/faq` page (2026-08-09). The player detail
page's old static placeholder blurb was replaced with a real ESPN-sourced availability status
pill (2026-08-10, first slice - see Priority 3 for the still-open weekly-designation gap). See
Recently Completed for full detail and how each was verified.

Implementation ideas:

- Rework the top summary cards by position group (QB passing/rushing/turnover; RB rushing/
  receiving opportunity; WR/TE target, reception, and yardage).
- Decide which stats belong in the hero area, summary cards, and game log table.
- Add stronger last-X-game views, home/away splits, opponent splits, and trend summaries.
- Continue trimming fields that are technically present but not useful yet.

## Priority 6: Historical Data Pipeline (R/nflverse)

Goal: keep the durable ingest path - R for batch backfill/refresh, Java serving stored data -
healthy as historical coverage and data volume grow. Mostly implemented; this priority is now
maintenance and gap-filling, not new architecture.

Implemented and stable:

- `etl/r/import_player_history.R` - per-player full-career backfill, triggered on-demand (async,
  non-blocking as of 2026-08-09) the first time a player has no stored game stats.
- `etl/r/refresh_stored_players_weekly.R` - recurring refresh for already-stored players, scoped
  to their current season, triggered by a data-driven check (`StatsRefreshDueChecker`/`Worker`/
  `Scheduler`) against `nfl_schedules` rather than a fixed cron slot.
- `etl/r/import_team_defense.R` - league-wide team defensive stats from nflverse.
- `RScriptRunner` wraps `Rscript` invocation with a concurrency cap so simultaneous backfills
  don't compete for memory/CPU.

What's left:

- Known residual gap: `nfl_schedules` (and therefore `game_date`/`home_away`) isn't backfilled
  before 2014 - currently only affects Russell Wilson's 2010-2013 rows (~95 rows). Extend if a
  long-career player needs it.
- No automated check for orphaned/incomplete rows the way the old ESPN game-log leftovers were
  (314 rows found and cleaned up manually in an earlier session) - worth watching as more players
  get backfilled.
- Decide which remaining datasets (if any) should move from ESPN to nflverse.
- Add a proper scheduler/job runner under `etl/jobs/` if the current Java-triggered model stops
  being enough - not needed yet.

## Priority 7: Team and Defense Data Foundation

Goal: keep team identity and defensive history solid enough to support matchup-aware predictions
and player-page visuals. Mostly done; this priority is now follow-up work, not foundational.

Implemented and stable:

- `Team` stores identity/branding/venue (ESPN-sourced, kept that way deliberately - UI-only, not
  model-critical, per explicit product direction).
- `TeamDefenseGameStat` stores defensive game history, sourced from nflverse
  (`etl/r/import_team_defense.R`) rather than ESPN scraping, including a fix for a franchise
  abbreviation mismatch (LA->LAR, WAS->WSH) and a recency window so years-old defense doesn't
  weigh the same as current-season defense in the prediction opponent adjustment.
- Team sync (identity + upcoming opponent) works reliably after a User-Agent-blocking bug was
  found and fixed.

Implementation ideas:

- Weekly and season-level defensive rank calculations.
- Team logos on player pages.
- Investigate whether defensive scheme (man/zone rate, etc.) is available anywhere reliable -
  would also serve Priority 3's feature-completeness goal.

## Priority 8: Search, Candidate Matching, and Performance Maintenance

Goal: keep the two hottest user paths (search, player-detail load) fast and reliable as the app
grows. Both halves of this priority are done or in steady maintenance mode - grouped together
since neither currently needs active work, just monitoring.

Done:

- Candidate search: top-5 results, 25% confidence threshold, typo tolerance, hidden match score.
- Background hydration is genuinely non-blocking end-to-end (2026-08-09/10) - see Recently
  Completed for the batched R fetch, the `@Async` self-invocation fix, and the follow-on loading-
  state/prediction-caching fixes needed once the backend actually stopped blocking. First-time
  player load now takes a few seconds, dominated by R/Rscript process-startup overhead rather
  than blocking the page - not worth chasing further without a bigger architectural change (a
  persistent R service process) that isn't currently justified.
- Concurrent R script execution is capped (`agp.etl.max-concurrent-scripts`) so simultaneous
  backfills don't compete for memory/CPU.

Implementation ideas (low urgency - revisit if real usage shows a regression):

- Add or tune caching for common player lookups and search results.
- Reduce repeated backend calls during a single player page load.
- Consider a background preload strategy for high-value offensive players if first-hit latency
  becomes a bigger issue than it is today.
- Keep prediction performance in mind once prediction endpoints start using larger history
  windows or more features (Priority 3).

## Longer-Term Direction

- Add role-specific player cards and comparison views.
- Expand from player analysis into team-level analysis later.
- Add premium-user-only derived views once the public experience is stable and the core mission
  (Priorities 1-4) is proven out - a paid tier makes sense once there's a real edge to sell
  access to, not before.
- Replace Hibernate-only schema generation with Flyway once the team tables and next database
  changes settle down.
- Add a simple ETL scheduler or runner under `etl/jobs/` once the batch scripts settle, rather
  than calling R from the live app.

## Working Agreement

- Store raw data once.
- Derive insights from stored data.
- Keep player ingestion reliable.
- Prefer incremental improvements that make future betting and model work easier.
- The core mission (see "Current Focus") is finding real, validated edge against real betting
  lines - when in doubt about what to prioritize, favor whatever moves Priorities 1-4 forward
  over polish on Priorities 5-8.

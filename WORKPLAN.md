# AGP Bets Work Plan

This document tracks the active product direction and the next implementation targets.
It should stay lightweight and evolve as the project matures.

## Current Focus

**Core mission (reframed 2026-08-10 at explicit user direction):** this app's reason to exist is
finding player-stat projections that consistently disagree with - and beat - the lines FanDuel/
DraftKings actually post, surfaced through an admin view that flags a set number of "sleeper" picks
each week. That's the product. A trustworthy-looking player page that never gets checked against a
real line is not the goal; it's a means to it. Priorities 1-4 below are the direct dependency chain
for that outcome, in order, and take precedence over everything else in this document. Priority 5
(team head-to-head model, added 2026-08-17) is a second, independent prediction surface, below the
core mission but above general polish. Priorities 6-9 are supporting infrastructure/UX work that's
mostly done or in steady maintenance mode - real, but not what's currently gating the core mission.

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

**Cleared as of 2026-08-22/26** - the 2026-08-20 rotating list (timezone bug, `offenseSnapPct` ->
`usageAdjustment` wiring) is done; see Recently Completed. **Priority 1's paid-provider decision is
explicitly deferred (2026-08-26, user direction)** - not revisiting until the free-data/UX backlog
below is worked through further; provider research from 2026-08-10 stays valid whenever it comes
back up.

**Cleared as of 2026-08-28** - a long, productive session: player-prop coefficients, the team
offense/defense split, and the totals blend weight were all recalibrated with a real temporal
train/test split (the single biggest methodology upgrade to this app's whole calibration history -
every prior calibration, including the same-day Elo constant check, was fit and evaluated on the
same full dataset until today). WOPR/`targetShareAdjustment` was retried twice with two genuinely
different regression methods and both agree it doesn't hold up - a settled negative result, not an
open question. Style-matchup and pace-of-play were both investigated thoroughly and paused as real,
converging nulls. See Recently Completed for the full numbers on all of it.

**Cleared as of 2026-08-28 (continued)** - `rushingQualityAdjustment` retested (see Recently
Completed) with a real, live data bug found and fixed along the way (PFR advanced rushing was only
backfilled for 2025). The Elo constants, the recent/season blend weight, and now this term have all
come back genuine, replicated nulls - every previously-flagged "worth retesting" candidate is now
resolved. That's a meaningful checkpoint: the low-hanging recalibration fruit from this session's
methodology upgrade looks exhausted, not just unexplored. Remaining open items:

**Cleared as of 2026-08-30** - `RECENT_GAME_WINDOW` retested against alternatives {3, 5, 8, 10}
(see Recently Completed) - a real, replicated win this time, unlike the last three checks. Three
metrics now use a per-metric window instead of the old single global 5. Remaining open items:

**Cleared as of 2026-08-30 (continued)** - `StatsRefreshWorker` end-to-end dry run done (see
Recently Completed), a real bug found and fixed along the way. Remaining open items:

**Cleared as of 2026-08-30 (continued again)** - with the calibration campaign and the stats-refresh
dry run both done, the user asked to move to UI/UX (frontend hasn't been surveyed for real gaps
since early in this document's history). A read-only frontend investigation turned up a concrete,
prioritized set of next items - not yet built, just scoped and agreed with the user:

1. **Replace the hardcoded "Featured players" homepage section with real, data-driven picks**
   (recommended starting point). `frontend/src/data/featuredPlayers.js` is 3 static players
   (Josh Allen, Jalen Hurts, Terry McLaurin) with hand-written blurbs, never touching the API -
   same "hand-picked instead of real" pattern this whole session has been fixing on the backend,
   just never applied to the UI. Something like "this week's top predicted performers" or
   "trending searches," sourced from real prediction data, would replace it with something genuine.
2. **UI copy is now quietly stale for 3 metrics.** The 2026-08-30 per-metric recent-game window
   change (receivingYards/passingTouchdowns/turnovers now use 10/8/10 games instead of 5) means any
   UI text still saying "last 5 games" for those specific metrics is wrong. Needs an audit of
   `PlayerDetailPage` copy in `frontend/src/App.jsx` against what's actually true post-recalibration.
3. **No 404/catch-all route.** `frontend/src/App.jsx`'s router (~line 1365) only defines `/`,
   `/players/:athleteId`, `/matchups`, `/faq` - a typo'd or stale URL currently just renders blank
   instead of a real not-found page.
4. **Surface the predicted final score on `/matchups`.** `TeamMatchupPredictionService.predictScore`
   already exists and runs live, but the matchups page (`frontend/src/App.jsx` MatchupsPage,
   ~line 1295) only shows the win-confidence percentage, not the predicted score itself - a small,
   low-effort addition using infrastructure that's already there, and a natural stepping stone
   toward the separately-scoped "compare predicted vs. actual score" backtest idea below.
5. Priority 6/8 UX and data-completeness backlog (last-X-game trend depth, defensive rank
   calculations) - real, lower-urgency work whenever prediction-quality investigation runs dry.
6. Secondary/fun (explicitly lower priority than the above, per 2026-08-28 user direction): a real
   backtest-style comparison of the model's own predicted score against actual final scores.
   `predictScore` already exists and runs live - this would be a new backtest endpoint/view, not new
   prediction logic, so should be quick once picked up.
7. **Residual, untested assumption**: `nflreadr::most_recent_season()` returned 2025 during the
   2026-08-30 dry run (correct - the 2026 season hadn't started yet), and every script that doesn't
   take an explicit season argument relies on this flipping over to 2026 on its own once real 2026
   games exist. Expected to self-correct (this is nflreadr's own season-detection logic, not
   anything this app controls), but hasn't been directly observed - worth a quick sanity check
   after the first real 2026-09-09 refresh actually runs, not before.

## Recently Completed

- (2026-08-30, the actual latest) **`StatsRefreshWorker`'s full ten-script chain run end-to-end for
  real for the first time - a real bug found and fixed along the way, plus the season-start dry run
  itself.** The chain had only ever been exercised one script at a time via manual `Rscript` calls
  across many sessions; this was the first time all ten ran together, in the real documented order,
  via the same mechanism production actually uses.
  - **Real bug found**: `refreshIfDueAsync()` fired all ten `rScriptRunner.run(...)` calls
    unconditionally, never checking any of their boolean return values. A failed
    `import_schedules.R` - the one script the class doc itself says everything else depends on
    being fresh - would have silently let the other nine run against stale schedule data, with
    nothing beyond `RScriptRunner`'s own per-script error log to notice anything went wrong. Fixed:
    the chain now aborts entirely if `import_schedules.R` fails, but keeps running the other nine
    independently on any single one of *them* failing (they're largely independent enrichments - a
    snap-counts hiccup shouldn't block the Elo recompute at the end of the chain), and always logs
    one clear pass/fail summary at the end instead of only ever seeing scattered per-script logs.
    Also wrapped the whole method in a catch-and-log, matching this app's established "`@Async`
    methods must log" convention (nothing else ever observes this `CompletableFuture` -
    `StatsRefreshScheduler` fires it and forgets it - so an uncaught exception would otherwise
    vanish silently). Two new tests cover both the abort-on-schedules-failure and
    continue-past-other-failures paths; full suite 92/92 green.
  - **Real end-to-end dry run**: ran all ten scripts for real, in the documented order, from the
    real working directory, using bare `Rscript` (not a hardcoded full path) to specifically verify
    the untested assumption baked into `RScriptRunner`'s default config
    (`agp.etl.r-executable: Rscript`, relying on PATH resolution rather than a full path) actually
    resolves correctly when spawned the way the real Java `ProcessBuilder` would spawn it - it did.
    All ten scripts succeeded (exit code 0), real row counts throughout (19,400 player stat rows/
    3,526 players, 570 team defense rows, 19,368 snap-count updates, 6,590 Elo rating rows, etc.),
    total wall time ~12.3 minutes (longest single script, `refresh_stored_players_weekly.R`, took
    7.4 minutes - comfortably under `RScriptRunner`'s 15-minute per-script timeout). Confirms the
    documented script-ordering dependencies hold up in practice (e.g. `import_pfr_defense_advanced.R`/
    `import_participation_defense.R` both ran cleanly after `import_team_defense.R`, which they
    UPDATE rows from). One residual, untested assumption flagged in the rotating list above:
    `nflreadr::most_recent_season()` correctly returned 2025 during this dry run (2026 hadn't
    started) - expected, but not directly observed, to flip to 2026 once real 2026 games exist.

- (2026-08-30, before the StatsRefreshWorker work above) **Player-prop recent-game window length
  (`PlayerPredictionService.RECENT_GAME_WINDOW`, the count of a player's most recent games that
  count as "recent form" in the blend) retested against alternatives with a real held-out
  methodology - the first genuine win after three consecutive replicated nulls (Elo constants,
  blend weight, `rushingQualityAdjustment`), closing out the last item on the 2026-08-28 rotating
  list.** Unlike a regression coefficient, window length is a small discrete menu choice, not
  something to fit - directly evaluated held-out MAE for windows {3, 5, 8, 10} (using the existing
  0.65/0.35 blend weight, unchanged) across the same 6 temporal cutoffs (2018-2023) used throughout
  this session, computed straight from `player_game_stats` in R rather than through the Java
  calibration export (window length isn't one of that export's fields). Three metrics showed a
  real, replicated win - the SAME window beat the default 5 on every single one of the 6 cutoffs,
  not just on average: `receivingYards` (window 10, MAE improved 0.6%-1.6% across cutoffs),
  `passingTouchdowns` (window 8, 0.6%-1.9%), `turnovers` (window 10, 0.9%-2.1%). `rushingYards`/
  `receptions`/`touchdowns` were already optimal at 5 - left unchanged. `passingYards` leaned toward
  a smaller window (3) but only won 5 of 6 cutoffs (lost at the 2020 cutoff) - not clean enough to
  trust by this session's own bar, left at the default.
  - **Implementation**: added `RECENT_GAME_WINDOW_BY_METRIC` (a small override map, defaulting to
    `RECENT_GAME_WINDOW` for every metric not listed) and a shared `recentGameWindowFor(metric)`
    helper. `buildProjection` now slices its per-metric `recentAverage` window from `allStats` (the
    full point-in-time-correct history, already available) instead of the passed-in `recentStats`,
    which stays fixed at the old 5-game window for the *other* adjustment methods
    (`rushingQualityAdjustment`/`advancedMetricAdjustment`/`targetShareAdjustment`/`usageAdjustment`)
    that were calibrated against that specific window and would otherwise have silently started
    seeing a different one. `PredictionBacktestService`'s `CalibrationRow` export was updated the
    same way, so future recalibration work reads what the live formula actually uses rather than a
    stale fixed-5 value for these three metrics.
  - Two new unit tests: a direct check of the override map, and a `buildProjection`-level test that
    deliberately passes a `recentStats` list different from `allStats`' own 10-game prefix, so a
    passing assertion proves the wider window is really being read from `allStats` and not a silent
    fallback to the old list. Full suite 90/90 green. Verified live via `/api/backtest/outcomes`
    after a clean restart - no anomalies (receivingYards MAE 18.26, passingTouchdowns MAE 0.908,
    turnovers MAE 0.792, all in the expected range).

- (2026-08-28, before the window-length work above) **PFR advanced rushing (`rushing_yards_after_contact`/
  `rushing_yards_before_contact`/`rushing_broken_tackles`) was only backfilled for the 2025 season -
  found while retesting `rushingQualityAdjustment` below, same "backfill gap, not a real
  data-availability limit" pattern as several other bugs this session.**
  `import_pfr_advanced_rushing.R` already supports any season and is already wired into
  `StatsRefreshWorker`'s weekly chain (defaults to `most_recent_season()`), so nothing needed
  backfilling was ever missing except the one-time historical run - it had simply never been run for
  2018-2024. Ran it for all seven seasons; `player_game_stats.rushing_yards_after_contact` now has
  real coverage matching `carries > 0` row counts for every season 2018-2025 (previously 0/12,275
  rows before 2025, now 10,761/12,275 overall). No code change needed - the refresh chain already
  covers this correctly going forward.

- (2026-08-28) **`rushingQualityAdjustment`/`AFTER_CONTACT_QUALITY_COEFFICIENT` (0.35d) retested with
  the raw-predictor method (same approach used for `SNAP_PCT_*`/WOPR), now backed by the real PFR
  backfill above rather than 2025-only data.** Recovered the exact raw predictor by dividing the
  exported `rushingQualityAdjustment` by the current coefficient (a single term, not a composite, so
  this is lossless unlike a rescaled-composite regression), then fit `actual - blendedMean - otherAdj
  ~ rawPredictor - 1` (no intercept, matching the term's actual shape in the live formula) on
  `season <= cutoff`, evaluated held-out on `season > cutoff`, replicated across the same 6 cutoffs.
  Result: **a real, replicated null.** The properly-fit coefficient is small and *negative* across
  every cutoff (-0.05 to -0.11, opposite sign from the current +0.35), marginally significant on the
  full dataset (p=0.020, coef=-0.067) but not reliably significant per-cutoff (p=0.08-0.40). More
  importantly: even using this "correctly fit" coefficient loses to the current hand-picked +0.35 on
  held-out MAE on every single one of the 6 cutoffs (current wins by 0.22-0.32 points every time,
  n_test 4,287-10,618). Whatever this raw predictor is capturing, a plain linear OLS fit to it
  doesn't generalize better than the existing guess - left untouched, same treatment as the Elo
  constants and the blend-weight retest below. This closes out the last previously-flagged
  recalibration candidate from this session - see the rotating list above for what's left.

- (2026-08-28) **Player-prop recent/season blend weight (`PlayerPredictionService.buildProjection`'s
  hard-coded `0.65d * recentAverage + 0.35d * seasonAverage`) re-tested per-metric with a real
  temporal train/test split - retroactively closes out the 2026-08-20 in-sample-only finding that
  flagged this as "real but not acted on" (e.g. receivingYards fitted ~42:58) without ever being
  held-out validated.** Per metric, fit `blendTarget ~ recentAverage + seasonAverage` (OLS,
  `blendTarget = actual` minus the other already-calibrated adjustment terms held fixed) on
  `season <= cutoff`, evaluated held-out on `season > cutoff`, replicated across the same 6 cutoffs
  (2018-2023). Result: **a real, replicated null - and it overturns the in-sample finding rather
  than confirming it.** For 6 of 7 metrics (receivingYards, receptions, touchdowns, rushingYards,
  passingYards, passingTouchdowns), the per-metric fitted blend loses to the current global
  0.65/0.35 split on every single held-out cutoff (e.g. receivingYards: implied recent share
  ~30-36% in every fit, but held-out MAE is *worse* by 0.79-1.29 points every time, not better).
  Only `turnovers` "won" on all 6 cutoffs, but the effect is tiny (~0.03 points on an MAE around
  0.78-0.80, ~3% relative) and the fitted recent-weight coefficient is unstable across cutoffs
  (even goes negative on the 2018 cutoff, n_train=1160) - textbook small-sample noise on an
  inherently low-signal metric, not trusted. Left untouched; the global 0.65/0.35 blend stays as-is.
  Honest scope caveat: this held the other adjustment terms' coefficients fixed at their currently-
  calibrated values while swapping only the blend - those coefficients were themselves fit assuming
  the current blend, so a *joint* refit of blend + adjustments together could in principle differ.
  Not pursued this pass (bigger lift, same "possible follow-up" treatment as other joint-refit ideas
  in this file) given the clear, consistent, non-marginal held-out losses already found.

- (2026-08-28) **Elo constants (`K_FACTOR`/`HOME_FIELD_ADVANTAGE_ELO`/`SEASON_REVERSION_FRACTION`
  in `compute_team_strength_ratings.R`) re-tested through the now-live offense/defense-enriched
  formula, not pure Elo alone** - the earlier same-day Elo-constant grid search (see below)
  evaluated pure Elo before the offense/defense enrichment shipped, which is no longer what's
  deployed; re-tested since the enrichment reweights how much Elo contributes (0.6427) and could in
  principle have shifted the optimal underlying Elo replay. 150-combo grid (K in
  {10,15,20,25,30,35}, HFA in {25,35,45,55,65}, reversion in {0.10,0.20,0.25,0.30,0.40}), selected
  on train (season<2022), confirmed on held-out test (season>=2022, n=1184), offense/defense
  coefficients held fixed at their shipped values. Result: **real null, and a stronger one than
  before** - train accuracy across all 150 combos spans just 0.0161 (0.6367-0.6528), meaning the
  enrichment has essentially absorbed whatever sensitivity to these hyperparameters used to exist.
  The best combo (K=20, HFA=25, reversion=0.3) beat current (K=20, HFA=55, reversion=0.25) on
  held-out accuracy (0.6495 vs 0.6402) but not MAE (9.929 vs 9.928, technically worse), and the
  accuracy gain does not clear significance (McNemar p=0.1524, n=49 disagreements) - same
  conclusion as the original standalone test, now reconfirmed with the enriched formula. Left
  untouched. Current Elo constants stay as-is.

- (2026-08-28) **Real, temporal train/test-split recalibration of the team
  totals-prediction blend weight - a third real win in the same session, though with an honest
  limit on how it could be verified live.** Same pattern as the day's other two wins: found a
  hand-picked constant (`expectedTotalPoints`'s fixed 50/50 blend of a team's own recent scoring
  vs. the opponent's recent points-allowed, with no intercept) that had never once been
  regression-calibrated, and tested whether real data supports something different.
  - **Method and result**: fit `actual_total ~ home_recent_scored + home_recent_allowed +
    away_recent_scored + away_recent_allowed` (letting all four raw inputs have their own weight
    plus a real intercept, instead of the fixed no-intercept 50/50 split), same 6-cutoff temporal
    replication (2018-2023). Held-out MAE improved on every single cutoff (+0.45% to +1.50%), all
    four coefficients real and statistically significant (p<0.03, most p<1e-9). Full-dataset fit
    used for final values: intercept=20.9851, home-scored=0.4702, home-allowed=0.1335,
    away-scored=0.3024, away-allowed=0.1741.
  - **Honest, checked-not-assumed limitation of this particular fix**: does NOT solve the
    totals-compression problem flagged 2026-08-20 (predicted range ~28-64 vs. real range 3-105,
    which pace-of-play also failed to fix earlier the same session) - the refit's own held-out
    predictions still only spanned ~37-54. A real, validated improvement to the existing formula's
    accuracy, not a fix for its known structural range ceiling - documented as such in the code so
    it isn't mistaken for one.
  - **A real verification limit, found and reported honestly rather than glossed over**: checked
    directly against Postgres - all 3,295 historical completed games already have a real posted
    `nfl_schedules.total_line`, so `TeamMatchupBacktestService.runTotalsBacktest()` *always* prefers
    the real line and structurally never touches the computed-estimate fallback this change lives
    in - confirmed live (`GET /api/backtest/team-matchups/totals` reads exactly 10.4566 MAE, byte-
    identical before and after, because the fallback path never fires on this dataset). This
    recalibration can only ever matter for upcoming games without a posted line yet - checked live,
    160 of the current 272 upcoming 2026 games (`GET /api/team-matchups/upcoming/all`) are in
    exactly that situation right now, so this is a real, live-relevant change, not a dead branch -
    just one whose accuracy claim rests on the R-side held-out validation alone, since the app's own
    historical backtest endpoint can't independently confirm it. Sanity-checked the live output
    directly: predicted totals across all 272 upcoming games ranged 38-54, matching the R-side
    refit's own held-out range (37-54) almost exactly, and every sampled score looked like a real,
    plausible NFL final (e.g. NE@SEA 18-26), not garbage output.
  - New/updated tests: `TeamMatchupPredictionServiceTest`'s `expectedTotalPoints` test and
    `TeamMatchupBacktestServiceTest`'s totals-fallback test both updated to the new formula's exact
    hand-computed values (verified independently before editing, not copy-pasted from a failing
    test's actual-output). Full suite: 88 tests passing.

- (2026-08-28, latest of all) **Retried `targetShareAdjustment` (WOPR) with a cleaner regression
  method - same conclusion as the earlier attempt the same day, now with much stronger evidence it
  isn't a methodology artifact.** The first retry (rescaling the current already-scaled coefficient
  by a train-fitted fraction, same method used successfully elsewhere the same day) failed - held
  out MAE got consistently worse. Retried with the different method `SNAP_PCT_*` used successfully
  (regress the RAW predictor directly - reconstructed by dividing the exported `targetShareAdjustment`
  value back out by the current live coefficient - rather than rescaling an existing guess), same
  6-cutoff replication discipline, fresh calibration export pulled after the day's other
  coefficient changes landed (so the baseline being compared against was genuinely current, not
  stale). **Same answer**: held-out MAE consistently *worse* on every single cutoff for both
  `receivingYards` (-2.15% to -6.37%) and `receptions` (-0.09% to -2.36%), despite the fitted
  coefficient being extremely "significant" by naive standards (p<1e-9 every time, n=27,382) and
  the full-dataset fit landing at 39.2 for receivingYards - roughly 8.5x the current live value
  (4.58), itself unstable across cutoffs (59.2 down to 40.4, never settling). Two independently
  different regression methods agreeing this term doesn't generalize is real, converging evidence
  the issue isn't *how* it's been calibrated - `targetShareAdjustment` stays at its current value.
  Likely explanation, not fully investigated: WOPR is volume-correlated with `blendedMean`
  (recentAverage/seasonAverage, already in the model) - a VIF check back on 2026-08-20 found this
  wasn't a same-sample correlation-artifact problem (VIF ~1.15), but that doesn't rule out the
  *incremental* WOPR effect being unstable across different seasons/eras in a way a single VIF
  number can't catch. Worth a dedicated look if this term ever gets revisited, not a five-minute
  fix. No code changed - a real, now well-tested negative result, documented so a future session
  doesn't re-attempt the same thing a third time without a genuinely new angle.

- (2026-08-28, even later still) **Priority 5's symmetric team offense/defense split, redone with
  a real temporal train/test split - a second major win in the same session, live-confirmed across
  three separate real metrics, and it corrected a mistake this session made a few hours earlier.**
  User pushback: the claim that this specifically "needs 2026 season data" (written into WORKPLAN
  the same session) was wrong - it had exactly the same gap the player-prop coefficients had that
  morning (fit and evaluated on the same full 2014-2025 dataset back on 2026-08-20, never held
  out), fixable immediately with data already stored.
  - **Methodology**: rebuilt the Elo replay in R using the live production constants (K=20,
    HFA=55, reversion=0.25 - unchanged from the same-day Elo-constant check, which is a separate
    question from this offense/defense addition), tracking each team's real point-in-time trailing
    scored/allowed as the replay walked forward chronologically. Same 6-cutoff replication
    discipline as the player-prop work (2018-2023).
  - **Real, consistently replicated result**: held-out margin MAE improved on every single one of
    the 6 cutoffs tested (+0.07 to +0.13 points), and win-loss pick accuracy also improved on every
    cutoff (+0.15pp to +1.26pp) - both metrics moving the same direction consistently is real
    signal, not noise. A reduced model (dropping `awayRecentAllowed`, p=0.76 in the full fit -
    contributing nothing) performed identically to the full one, so the simpler 4-term version
    shipped. Full-dataset fit: `elo_predicted_margin` coefficient drops to 0.6427 (confirming the
    2026-08-20 in-sample finding that this can't be a small additive nudge - the core Elo margin's
    own weight genuinely needs rescaling, not just new terms added on top).
  - **Honest caveat, checked properly, not glossed over**: a McNemar-style significance test on the
    discrete win/loss pick specifically (2022 cutoff, 78 disagreements) came back p=0.57 - not
    independently significant on its own. Win/loss accuracy is a much lower-power metric than
    continuous margin error at this sample size (a few thousand team-games vs. tens of thousands of
    player-game rows on the prop side), so this doesn't contradict the consistent MAE finding, but
    it's a real limit on how confidently the *pick-accuracy* half specifically can be claimed from
    the held-out test alone.
  - **Shipped and verified live across three separate real metrics** (not just the held-out
    replication numbers): `GET /api/backtest/team-matchups` winner accuracy 63.9%->64.67% (2131 of
    3295 correct, up from 2104), margin MAE 10.4->10.24; `GET /api/backtest/team-matchups/spread`
    hit rate 49.7%->51.12% (crossed from *below* a coin flip to above one); `GET
    /api/backtest/team-matchups/totals` MAE unchanged (10.46, as expected - this change only
    touches margin/win-probability, not the total-points calculation). All three metrics moving
    the right direction together is real convergent evidence, on top of the held-out replication.
  - **Implementation**: new `TeamMatchupPredictionService.predict(homeRating, awayRating,
    Double homeRecentScored, Double homeRecentAllowed, Double awayRecentScored)` overload (real
    coefficients: `OFFENSE_DEFENSE_INTERCEPT`=5.8815, `OFFENSE_DEFENSE_ELO_COEFFICIENT`=0.6427,
    `OFFENSE_DEFENSE_HOME_SCORED_COEFFICIENT`=0.2283, `OFFENSE_DEFENSE_HOME_ALLOWED_COEFFICIENT`
    =-0.1948, `OFFENSE_DEFENSE_AWAY_SCORED_COEFFICIENT`=-0.2695) - falls back to the original
    pure-Elo 2-arg `predict()` when any of the three is null (a team's literal first game on
    record), same graceful-degradation shape used everywhere else in this app. `homeWinProbability`
    is derived from the same enriched margin (converted back through
    `ELO_POINTS_PER_MARGIN_POINT`), not the raw rating difference - keeps the displayed confidence
    and the actual pick internally consistent, the same bug class `UpcomingTeamMatchupService`'s
    2026-08-20 fix already closed once for the margin/score pairing. Wired into
    `UpcomingTeamMatchupService` (reusing the trailing averages it already computed for
    `expectedTotalPoints`, no new data plumbing) and `TeamMatchupBacktestService.runBacktest()`/
    `runSpreadBacktest()`. `runStyleCalibrationExport()` deliberately kept calling the pure-Elo
    overload - that export's whole purpose is testing whether style interactions add anything
    *beyond* Elo, so enriching its own baseline would undermine the comparison; documented in its
    doc comment so this reads as a deliberate choice, not a missed call site.
  - New tests: `TeamMatchupPredictionServiceTest` covers the enriched formula directly (exact
    hand-computed values) and the null-fallback case; `TeamMatchupBacktestServiceTest` confirms
    `runBacktest()` actually uses the enriched margin when real trailing history exists (asserts an
    error value that could only come from the enriched formula, not the pure-Elo fallback - a
    direct proof the wiring executed, not just that it compiles). Full suite: 88 tests passing.

- (2026-08-28, latest) **Real, temporal train/test-split recalibration of four player-prop
  coefficients - the biggest validated win of the session, live-confirmed, and it directly
  disproved the "needs to wait for 2026 data" framing from earlier the same day.** User pushback:
  proper out-of-sample validation means holding out data you already have, not waiting for new
  data - correct, and a real gap, since every calibration this app has ever done (including this
  same session's own Elo constant check) was fit and evaluated on the exact same full historical
  dataset, never genuinely held out.
  - **Methodology**: extended `PredictionBacktestService.CalibrationRow` with `season`/`gameDate`
    (previously absent - no calibration data before today could even be split temporally). For
    each candidate rescale: fit on `season <= cutoff`, evaluate held-out MAE on `season > cutoff`,
    repeated across 6 independent cutoffs (2018-2023) before trusting any result - a single split
    isn't enough (see the near-miss below for exactly why).
  - **Real, replicated wins - held-out MAE improved on every single one of the 6 cutoffs tested,
    not just one lucky split**: `passingYards` (+1.27% to +1.74%), `touchdowns` (+2.20% to
    +4.10%, the largest), `passingTouchdowns` (+0.18% to +0.38%), `receivingYards` (+0.96% to
    +1.81%, after correctly excluding the already-separately-validated `zoneCoverageRateDelta`
    term from what got rescaled - see below). Final coefficients fit on the full 2014-2025 dataset
    for maximum precision, now that the method itself was validated via replication.
  - **A real near-miss, caught and avoided**: `targetShareAdjustment` (WOPR) looked highly
    significant on a naive single-split regression (fraction≈9x, which would have undone the
    2026-08-20 correction and drifted back toward the original problematic value) - but applying
    it made held-out MAE *worse*, consistently, every time it was tested. Same tell that caught the
    original 2026-08-20 miscalibration bug. Left completely untouched. `receptions`/`rushingYards`
    showed no real signal either way (also untouched).
  - **A real subtlety caught before shipping**: `receivingYards`'s `opponentAdjustment` composite
    includes the recently-and-separately-validated `zoneCoverageRateDelta` term (2026-08-26 work,
    p=0.019 on its own dedicated regression). Naively rescaling the whole composite would have
    silently undone that validated work by compounding two independent corrections. Fixed by
    reconstructing the composite minus the zone-coverage contribution before fitting, leaving
    `ZONE_COVERAGE_RECEIVING_YARDS_COEFFICIENT`/`ZONE_COVERAGE_RECEPTIONS_COEFFICIENT` completely
    untouched - re-validated via the same 6-cutoff replication with this cleaner decomposition.
  - **Coefficient changes** (`PlayerPredictionService`): `CPOE_PASSING_YARDS_COEFFICIENT` 3.0->0.0273,
    `CPOE_PASSING_TOUCHDOWNS_COEFFICIENT` 0.02->0.0054, `RECEIVING_QUALITY_COEFFICIENT` 0.5->-0.0648
    (a real sign flip, consistent across all 6 replications - a hot recent YAC-over-expectation
    stretch predicts regression toward the mean, not more of the same), `PRESSURE_PASSING_YARDS_
    COEFFICIENT` -2.0->-1.3408, `PRESSURE_PASSING_TOUCHDOWNS_COEFFICIENT` -0.02->-0.0235, new
    `MISSED_TACKLE_RECEIVING_YARDS_COEFFICIENT`=24.32 (split out of the shared
    `MISSED_TACKLE_YARDS_COEFFICIENT`=100.0, which stays unchanged for rushingYards - the two
    metrics showed different real behavior), new named `PASSING_YARDS_OPPONENT_COEFFICIENT`=0.0469,
    `TOUCHDOWNS_OPPONENT_COEFFICIENT`=0.0044, `RECEIVING_YARDS_OPPONENT_COEFFICIENT`=0.00365
    (extracted from inline literals now that they're genuinely calibrated), wind/total-line
    coefficients in `gameConditionsAdjustment` updated similarly, and the shared touchdown-metrics
    total-line constant (previously one 0.03d value covering touchdowns/passingTouchdowns/
    rushingTouchdowns) split into independent `TOUCHDOWNS_TOTAL_LINE_COEFFICIENT`=0.0045 and
    `PASSING_TOUCHDOWNS_TOTAL_LINE_COEFFICIENT`=0.0213 once real evidence showed they wanted
    meaningfully different magnitudes.
  - **Verified live, before vs. after** (`GET /api/backtest/outcomes`): passingYards MAE 68.42 ->
    67.18 (-1.8%), touchdowns MAE 0.3726 -> 0.3575 (-4.1%), passingTouchdowns MAE 0.9192 -> 0.9138
    (-0.6%), receivingYards MAE 18.67 -> 18.41 (-1.4%) - receptions/rushingYards/turnovers
    (deliberately untouched) confirmed byte-for-byte identical before and after, proving no
    unintended side effects. 4 existing tests updated to the new expected values (recomputed by
    hand from the new constants, not copy-pasted from the tool output); full suite: 85 tests
    passing throughout.
  - **Follow-up for future sessions**: this same methodology should be applied to the Elo constants
    (today's earlier joint-grid/isolated-K_FACTOR check didn't clear significance, but wasn't
    retested with this session's later, more careful zone-coverage-exclusion-style rigor) and to
    any future player-prop coefficient work - `runCalibrationExport` now always carries
    `season`/`gameDate`, so there's no reason to ever fit-and-evaluate on the same full dataset
    again.

- (2026-08-28, even later) **Investigated pace-of-play as a fix for the totals-compression
  problem flagged 2026-08-20 - a real, converging null across two operationalizations, not built
  into the app.** Explicit user direction after the style-matchup work concluded, on the reasoning
  that raw play volume should plausibly drive scoring in a way the current points-scored/allowed
  averaging formula doesn't directly capture.
  - **Same-game check (the obvious first look)**: total offensive plays (both teams combined,
    real counts from `nflreadr::load_participation()` row counts per team-game) vs. actual total
    points, 855 real games 2023-2025 - correlation 0.43, R²=0.186. By a wide margin the strongest
    single-variable relationship found anywhere this session (stronger than the real Vegas
    `total_line`'s own ~0.09 R² ceiling documented 2026-08-12, stronger than anything from the
    style-matchup work). Real total-play range (126-203) and real total-points range (3-90) both
    show substantial variance, so this wasn't a degenerate/constant-value artifact either.
  - **Point-in-time test (the one that matters)**: each team's own trailing (8-game, matching
    `RECENT_GAMES_FOR_SCORING`) average offensive plays, summed for both sides, predicting the
    actual total - controlling for the existing model's own predicted total (replicated exactly:
    `((homeScored+awayAllowed)/2) + ((awayScored+homeAllowed)/2)`) so a real coefficient would mean
    something genuinely incremental. **The strong same-game correlation completely collapsed**:
    0.43 -> 0.041, R² 0.0017, p=0.234, n=839 - not remotely significant, and barely different from
    zero. The same-game number was almost entirely picking up how *this specific game* unfolded
    (blowouts run fewer plays as the leading team runs out the clock; close/back-and-forth games
    run more), not a persistent team characteristic that predicts a *future* game - the same
    reactive-confound pattern already flagged as a risk for `avgPassRushers`/`avgDefendersInBox`
    on 2026-08-26, now confirmed directly for total play volume too.
  - **One legitimate refinement tried, not a fishing expedition**: `no_huddle` rate (a real
    coaching-philosophy/tempo choice, not just a game-script byproduct the way raw play count is) -
    same trailing point-in-time test. Also a clean null: correlation 0.007, p=0.842, n=839.
  - **Conclusion**: the totals-compression problem (predicted range 28-64 vs. real range 3-105,
    documented 2026-08-20) stays genuinely open - pace-of-play doesn't close it, at least not via
    simple trailing averages of either raw play volume or no-huddle rate. A real, useful negative
    result closing out an open question rather than leaving it to be re-investigated blind next
    time. Not built into the app - ad-hoc R investigation only, same "don't build infrastructure
    for a hypothesis that didn't show real signal" discipline as the style-matchup work.
  - Both of today's two free-data prediction-quality investigations (style-matchup and pace) came
    back honest nulls - flagged in the rotating list above as a real signal that the easy free wins
    in "add a new trailing-average signal" direction may be thinning out for now, worth weighing
    against other available work rather than continuing to search blindly.

- (2026-08-28, later) **Pushed the style-vs-style matchup question all the way to the play-level
  EPA test the same day the coarse version landed - the sharper, more direct test the coarse
  result explicitly couldn't rule out (see the entry below). Real, converging null result across
  four independent tests, not built into the app (ad-hoc R only, per explicit user direction to
  keep iterating and report back what's actually found).**
  - **Confirmed the `load_pbp()`/`load_participation()` join works cleanly before building
    anything on top of it** (the real technical risk this work was scoped around): joining on
    `game_id`/`play_id` matched 100% of participation's 45,919 rows (2024) against real EPA/
    success/yards_gained data, 92.8% of `pbp`'s own rows (the remainder are non-play rows -
    kneels, spikes, timeouts - participation doesn't track).
  - **Confirmed there's real signal to chase before investing in a full point-in-time pipeline**:
    `epa ~ posteam * defense_man_zone_type` (interaction) vs. `epa ~ posteam + defense_man_zone_type`
    (no interaction), fit against 109,932 real offensive plays (2023-2025) - the interaction is
    statistically significant (ANOVA F=1.50, p=0.0067), meaning teams genuinely do differ in how
    much coverage type affects their EPA, beyond both team quality and coverage type alone. Real,
    not noise - this justified building the actual predictive test rather than stopping here.
  - **The actual point-in-time predictive test (the one that matters)**: for every team-game
    2023-2025 with enough trailing play history, computed the offense's own trailing EPA split by
    coverage faced (zone vs. man, from real prior plays only) and the upcoming opponent's own
    trailing zone-coverage rate (as the defense), then tested whether their interaction predicts
    the offense's *actual* EPA in that specific game, controlling for the offense's own overall
    trailing EPA and the opponent's aggregate zone tendency. **Null**: interaction p=0.410, R²
    barely moved (0.0877->0.0881), n=1,613 real team-games.
  - **Refined and re-tested rather than stopping at one null**: `defense_man_zone_type` is a
    passing-coverage concept, so mixing in run plays (where box count matters more directly than
    coverage) dilutes any real effect - rebuilt the same test scoped correctly to pass plays only
    (67,480 real pass plays). **Still null, if anything weaker**: interaction p=0.653, n=1,613.
  - **Tested a conceptually distinct mechanism too, not just variations on coverage**: the run-game
    analog - offense's own trailing EPA split by box count faced (light box <7 defenders vs.
    stacked box >=7, standard football convention) interacted with the opponent's own trailing
    average box count, predicting actual rushing EPA. Found and excluded the same zero-as-sentinel
    data quirk `import_participation_defense.R` already documented for `defenders_in_box` (0 means
    "not applicable," not a real zero). **Also null**: interaction p=0.380, n=1,599 real team-games
    (42,351 real run plays).
  - **Honest conclusion, not a dead end left open indefinitely**: four independent, methodologically
    distinct tests (coarse aggregate, play-level coverage split on all plays, the same test scoped
    correctly to pass plays, and a genuinely different run-game/box-count mechanism) all return the
    same answer - no significant style-interaction effect survives controlling for the
    already-validated Elo/EPA baseline, even where real in-sample between-team variance does exist.
    Explicitly did not run a fifth or sixth variation chasing a different p-value once this pattern
    was clear - that would be exactly the "constructed after the fact, won't replicate" trap this
    whole priority was scoped to avoid from the start. Recommended: pause this specific line of
    investigation rather than keep iterating on the same 2023-2025 sample; revisit once real 2026
    season data exists as a genuinely new angle, not more variations on what's already been tested.
  - Deliberately not built into the app this pass (no new Java/Postgres infrastructure, no
    `load_pbp()` ingestion pipeline) - ad-hoc R investigation only, consistent with "don't build
    real infrastructure for a hypothesis until it's shown real signal," which it didn't.

- (2026-08-28) **Built Priority 5's team offense data foundation and ran a real, disciplined test
  of the style-vs-style matchup hypothesis - a genuine, thorough null result at the coarse
  (team-game-aggregate) level, with a concrete next step (play-level EPA) scoped and carried
  forward rather than the question being left open indefinitely.** Followed an explicit plan the
  user approved before any code was written (same discipline as the participation-defense work).
  - **New `TeamOffenseGameStat`/`team_offense_game_stats`** - the counterpart `TeamDefenseGameStat`
    never had (closes Priority 3's long-open "build a cleaner team offense model" bullet). Two
    clean, unambiguous style signals for this first pass (deliberately not parsing
    `offense_personnel` groupings yet - real parsing risk, noted as a future refinement):
    `passRate` (`nflreadr::load_team_stats()`, `attempts / (attempts + carries)`, backfilled
    2018-2025 matching PFR advanced defense's own floor) and `shotgunRate`
    (`nflreadr::load_participation()`, `offense_formation == "SHOTGUN"` share, grouped by
    `possession_team` - the mirror image of how `import_participation_defense.R` derives
    `defense_team` from the same column).
  - **Found and fixed a real data-quality issue before it reached the regression**:
    `offense_formation`'s taxonomy changed in 2023, confirmed live - 2018-2022 used a granular
    scheme (EMPTY/I_FORM/JUMBO/PISTOL/SHOTGUN/SINGLEBACK/WILDCAT) where formations still snapped
    from shotgun (EMPTY, SINGLEBACK) got their own separate labels instead of counting as
    "SHOTGUN", while 2023+ collapsed everything to a clean PISTOL/SHOTGUN/UNDER CENTER scheme.
    First backfill attempt produced a suspiciously flat ~53-55% shotgun rate for 2018-2022 that
    jumped to 62-69% in 2023 - caught by a live plausibility check (real NFL shotgun rate has been
    consistently higher than 53-55% for years, this wasn't a real trend), confirmed via the raw
    formation-value distributions per season, not assumed. Fixed by gating `shotgunRate`
    computation to `season >= 2023` in `import_team_offense.R` and nulling out the 2018-2022 values
    already written, rather than leaving a wrong-but-present number sitting in the column - same
    "an honest null beats a silently wrong value" discipline the injury-status/participation-defense
    data-loss bugs already established this app follows.
  - **New `TeamMatchupBacktestService#runStyleCalibrationExport()`** (`GET
    /api/backtest/team-matchups/style-calibration`), same point-in-time-correct shape as
    `PredictionBacktestService.runCalibrationExport()`: per backtestable game, the actual margin,
    the already-validated Elo-predicted margin, and each side's own trailing (last 8 games,
    matching `UpcomingTeamMatchupService.RECENT_GAMES_FOR_SCORING`) offense/defense style averages
    - home offense vs. away defense, and the mirror set for away offense vs. home defense. 839 real
    rows (2023-2025, where both offense and defense trailing data exist).
  - **The real regression, six pre-specified interaction terms (not a grid search) - each a real
    football hypothesis**: pass-heavy offense vs. zone-heavy defense, run-heavy offense vs.
    stacked box, shotgun-heavy offense vs. high-pressure defense, tested both directions (home
    offense x away defense, and the mirror). Fit `actual_margin ~ elo_predicted_margin + <all six
    terms>` together (controlling for Elo and each other) and each term individually (checking
    whether multicollinearity among the six was masking a real univariate effect) - **genuine null
    result either way**: no term significant in the joint model (p=0.16-0.97), no term significant
    tested alone either (p=0.10-0.86, closest was `home_rush_x_away_box` at p=0.097, still short of
    the conventional 0.05 threshold), the joint F-test comparing the full model to Elo-alone wasn't
    significant (F=0.91, p=0.487), and R²/MAE barely moved (R² 0.1486->0.1541; MAE 10.240->10.232).
    This is a real, thorough negative result - not force-fit into the model, same treatment as
    `avgPassRushers`/`avgDefendersInBox` and `team_implied_spread` got. Nothing wired into
    `TeamMatchupPredictionService` this pass.
  - **What this does and doesn't settle**: this coarse, team-game-aggregate-level test genuinely
    doesn't find a broad "this offense style generally struggles against that defense style"
    effect once Elo is already controlled for. It does NOT rule out the sharper, more direct
    version of the user's original question (team A specifically vs. team C's specific tendencies,
    at play-level granularity) - that needs joining `nflreadr::load_pbp()`'s EPA/success-rate data
    to `load_participation()`'s per-play coverage/pressure/box data, a bigger, separate lift not
    attempted this session (explicitly scoped as a next step in the approved plan, not silently
    dropped) - see "Priorities From Last Session" above and Priority 5's "Implementation ideas".
  - New tests: `TeamMatchupBacktestServiceTest` covers the export directly (trailing-average
    computation for both sides, and the "no history yet -> skipped" case) - 9 tests in that file
    now, up from 7. Full suite: 85 tests passing.
  - Also found, mid-session, that the Postgres container had been removed (not just stopped) since
    the prior session - `docker compose ps` returned no containers at all, though `docker info`
    confirmed the daemon itself was running. Confirmed the named `postgres_data` volume was still
    intact before recreating the container (`docker compose up -d postgres`) - verified all data
    survived (106,560 `player_game_stats` rows, matching the known count), consistent with the
    2026-08-20 finding that container recreation doesn't touch the named volume.

- (2026-08-26, even later) **Built the real, point-in-time-correct version of the participation
  (coverage-scheme/pass-rush) feature investigated earlier the same day - one new real
  `opponentAdjustment` signal shipped, two real candidates tested and correctly left out.**
  Followed the exact Phase 4 sequence (schema + ETL first, then a real regression against the
  harness that already exists, wire in only what survives) per an explicit plan the user approved
  before any code was written.
  - **New `etl/r/import_participation_defense.R`**, mirroring `import_pfr_defense_advanced.R`'s
    shape (UPDATE against existing `team_defense_game_stats` rows, `etl_import_runs` bookkeeping),
    with one real difference: `load_participation()` is per-play with only the *offense's* team
    labeled, so the script derives the defending team via a join to `nfl_schedules`
    `home_team`/`away_team` before aggregating. Backfilled 2023-2025 (1,710 team-games - the only
    seasons this source is reliably populated, confirmed earlier the same day).
  - **Found and fixed a real data-quality bug while backfilling, before it ever reached a
    regression**: `number_of_pass_rushers` and `defenders_in_box` both use `0` as a "not
    applicable to this play" sentinel (e.g. a run play has no pass-rush count), not a genuine
    zero - confirmed directly (23,754 of 45,919 2024 plays are exactly `0` for pass rushers; 9,214
    of those 9,219 zero-box rows are also zero-rushers rows, consistent with "not tracked for this
    play type"). Naively averaging them in produced implausible league-wide numbers (2.08 pass
    rushers/play, 4.87 defenders in box) caught by a live plausibility spot-check against real NFL
    baselines; excluding the sentinel zeros before averaging fixed both to realistic figures (4.31
    pass rushers, 6.20 defenders in box). `zoneCoverageRate` didn't have this problem - it's a real
    category, not a count, so no zero-sentinel issue applied.
  - **New `TeamDefenseGameStat` columns** (`zoneCoverageRate`, `avgPassRushers`,
    `avgDefendersInBox`) and three matching trailing-delta helpers on `PlayerPredictionService`
    (`zoneCoverageRateDelta`/`avgPassRushersDelta`/`avgDefendersInBoxDelta`, refactored alongside
    `missedTackleDelta` onto one shared `averageDoubleDelta` helper - real reuse, not just new
    code). `LeagueDefenseAverages`/`leagueDefenseAveragesFrom` extended with three more real
    computed fallback averages (queried directly against the full 2023-2025 backfill: 0.2885 zone
    rate, 4.3117 pass rushers, 6.2013 defenders in box - not guesses, same discipline as every
    other fallback in this file).
  - **The real regression, run against `PredictionBacktestService`'s existing point-in-time
    harness** (extended `CalibrationRow`/`runCalibrationExport` with the three raw trailing deltas
    - not pre-scaled by any coefficient, since none existed yet, same "regress the raw predictor"
    treatment `WOPR_*`/`SNAP_PCT_*` got): fit `actual ~ recentAverage + seasonAverage +
    opponentAdjustment + conditionsAdjustment + rushingQualityAdjustment + advancedMetricAdjustment
    + targetShareAdjustment + <new delta>` per metric - critically, `opponentAdjustment` here
    already includes the existing pressures/missedTacklePct signals, so a significant coefficient
    on a new term is real evidence of something incremental, not a restatement of what's already
    wired. **Real result, and it split the three candidates apart cleanly**: `zoneCoverageRateDelta`
    held up for `receivingYards` (coefficient 8.4465, p=0.019, n=14,473) and `receptions`
    (coefficient 1.2441, p=2.94e-06, n=14,473) - both real, both football-sane (more zone coverage
    than league average associates with more underneath completions/yards allowed, consistent with
    zone's known tendency to concede shorter, higher-percentage throws). `avgPassRushersDelta`
    (tested against passingYards/passingTouchdowns) and `avgDefendersInBoxDelta` (tested against
    rushingYards) did NOT hold up (p=0.10-0.97) - and `avgDefendersInBoxDelta`'s sign flipped
    negative once measured correctly (more box defenders -> fewer rushing yards, the intuitive
    direction), confirming last session's same-game positive correlation was exactly the
    reactive-coaching confound flagged as a risk at the time, not a real predictive signal. Both
    left unwired, stored, and documented as real checked negative results - not force-fit in.
  - **Wired `zoneCoverageRateDelta` into `advancedDefenseAdjustment`** (folded in there, not a new
    top-level field - same reasoning Phase 4 used) for `receivingYards` (alongside the existing
    missed-tackle nudge) and `receptions` (a brand new case in that switch - receptions had never
    had an advanced-defense nudge before). New test
    (`opponentAdjustmentAddsZoneCoverageNudgeForReceivingMetrics`) plus direct unit coverage for
    all three new delta methods (`PlayerPredictionServiceTest`, 25 tests in that file now, up from
    20) - `pressureDelta`/`missedTackleDelta` had never had direct tests either before this pass,
    only indirect coverage through `opponentAdjustment`, so this is the first direct test coverage
    for that whole method family.
  - **Verified live, before vs. after** (`GET /api/backtest/outcomes`, same clean comparison every
    prior calibration entry uses): receivingYards MAE 18.68 -> 18.6677, receptions MAE 1.371 ->
    1.3701 - both genuinely, if modestly, improved, exactly the size of movement you'd expect from
    a real-but-secondary signal added on top of an already-calibrated model (not a dramatic swing,
    which would have been the more suspicious outcome - same read this file has given every prior
    calibration pass). `passingYards`/`rushingYards`/`touchdowns`/etc. MAE unchanged, as expected
    (zoneCoverageRateDelta only touches receivingYards/receptions). Full suite: 83 tests passing.
  - Added `import_participation_defense.R` to `StatsRefreshWorker`'s chain (now nine scripts),
    right after `import_pfr_defense_advanced.R` for the same "only UPDATEs rows
    `import_team_defense.R` already wrote" reason that script runs where it does.

- (2026-08-26, later) **Shipped Priority 6's position-grouped summary cards, fixed a stale/false
  FAQ answer, and closed the long-standing "never visually verified in a browser" gap - all in one
  session, per explicit user direction to work through free UX/data items rather than revisit
  Priority 1's paid-data decision yet.**
  - **Position-grouped summary cards** (`PlayerDetailPage`'s "Player snapshot" section,
    `App.jsx`): the top 3-4 hero cards were previously one generic QB-vs-everyone-else split
    (combined yards/TDs/turnovers). Replaced with real position-specific groups - QB (passing
    yards/g, passing TDs/g, rushing yards/g, turnovers/g), RB (rushing yards/g with carries/g
    context, rushing TDs/g, receiving yards/g with targets/receptions context, turnovers/g),
    WR/TE/FB (targets/g, receptions/g with catch rate, receiving yards/g, receiving TDs/g), with
    the old generic 3-card layout kept as the fallback for an unrecognized position - same grouping
    `PlayerPredictionService.SKILL_POSITIONS` already uses backend-side, for consistency. Genuinely
    free: every field (`carriesPerGame`, `receivingTargetsPerGame`, `receptionsPerGame`, etc.) was
    already computed and returned by `PlayerStatInsightSummary` - this was a pure frontend reshuffle,
    no backend change. Visually verified against three real players (see below).
  - **Fixed a stale, actively false FAQ answer**: "What's not factored in yet?" still listed snap
    counts, injury status, and weather as unmodeled - all three (plus target share and opponent
    pass-rush/missed-tackle data) have actually been live in the model since earlier sessions this
    FAQ entry was never updated to reflect. Rewrote it to name the real remaining gap (betting-market
    lines - Priority 1) and correctly list what's actually wired in, including the honest caveat that
    snap share is tracked but deliberately not applied live. Also refreshed "How is the projected
    stat line calculated?" to mention the opponent/weather/role/injury nudges that have been added
    since that answer was first written, not just the original recent/season blend.
  - **Closed the "no browser tool available" gap flagged across multiple prior sessions (2026-08-19,
    2026-08-20, 2026-08-26 earlier today)**: `playwright` installs in seconds via `npm install` in a
    throwaway scratch directory, and a Chromium binary was already cached on this machine
    (`AppData/Local/ms-playwright`) from some earlier, unrelated setup - no slow/heavy download was
    needed. Wrote a standalone script (not committed - lives in the session scratchpad) that starts
    the real dev stack (`start-dev.ps1`), drives the real UI (search -> open player -> wait for the
    prediction/insight sections -> screenshot), and checks for console/page errors. **Verified live,
    with real data**: Patrick Mahomes (QB - shows the real live "Questionable" injury-status pill),
    Christian McCaffrey (RB), and Tyreek Hill (WR) all render their new position-grouped cards
    correctly with sane real numbers; the FAQ page's updated text reads cleanly; `/matchups` (flagged
    unverified since 2026-08-19) also confirmed rendering correctly for the first time - real
    per-game scores, correct winner bolding, and the `predictedTie` case (two 2026-08-20 additions
    that had never actually been seen rendered) both showing correctly. Zero console/page errors
    across all five pages checked. Backend/frontend dev processes were stopped again afterward;
    Postgres was left running as it already was (not touched by this verification pass).
- (2026-08-26) **Fixed a real compile error left over from an undocumented 2026-08-22 session** -
  the machine this project runs on was restarted mid-edit while wiring `offenseSnapPct` into a new
  `usageAdjustment` term (the item flagged 2026-08-20). The `usageAdjustment(...)` method itself was
  fully written and tested (3 passing tests), but `buildProjection` referenced its return value as a
  bare local variable that was never actually assigned - a genuine `cannot find symbol` compile
  error, confirmed via a clean `mvnw compile` (not caught earlier since a stale `target/` let
  incremental builds report success without recompiling the changed file). That 08-22 session's work
  was also never written back to WORKPLAN.md, which is how this went unnoticed rather than being
  caught by the "read WORKPLAN first" habit. Fixed with the one missing line, computing the value and
  surfacing it on the response without folding it into `projectedMean` - matching the surrounding
  comment, which documents a live A/B test already found no net accuracy improvement from summing it
  in (rushingYards MAE +0.0095, receivingYards MAE +0.022). Verified clean: backend compiles, all 78
  tests pass, frontend builds with no errors. Everything since the last commit (2026-08-11/12,
  covering NGS/PFR-defense/injury-status/coefficient-calibration/team-matchups) was also still
  sitting uncommitted at this point - now pushed to `main`.

- (2026-08-20, later) **Knocked out the two remaining free-data items: the UX pill gap and the
  `targetShareAdjustment` recalibration follow-up - the latter shipped a real, self-caught
  arithmetic error before it landed for good.**
  - **UX pill fix**: `PredictionCard` in `App.jsx` now sums every adjustment term
    (`opponentAdjustment`/`conditionsAdjustment`/`rushingQualityAdjustment`/
    `advancedMetricAdjustment`/`targetShareAdjustment`) for the "Favorable matchup"/"Tough
    matchup" pill's tone threshold, instead of just `opponentAdjustment`. Not visually verified in
    a browser (no browser tool available this session), but it's a straightforward sum of
    already-numeric response fields.
  - **`targetShareAdjustment` recalibration, corrected twice**: first checked the collinearity
    concern that held this back at the coefficient-calibration pass (WOPR is volume-correlated
    with `blendedMean`) via a variance inflation factor check - VIF ~1.15 for both receivingYards
    and receptions, well below the conventional ~5 concern threshold, so the earlier regression
    finding wasn't a correlation artifact. Then **made a real calibration error**: the correct
    operation (matching how `opponentAdjustment` was calibrated) is to multiply the old coefficient
    by the fitted regression coefficient (old x fitted), but this was computed as a division (old /
    fitted) instead, concluding the term was underscaled by ~9x when the regression actually said
    the opposite - it was overscaled to just 11.4%/8.2% of its magnitude (receivingYards/receptions
    respectively), the same pattern every other calibrated term in this file showed. Shipped a
    doubled coefficient (40.0->80.0, 3.0->6.0) as a deliberately conservative partial version of
    the (wrong) ~9x finding - live-verified immediately after via `GET /api/backtest/outcomes`,
    and the MAE got measurably **worse** (receivingYards 18.77->20.86, receptions 1.379->1.565),
    not better. That result was the tell: caught and fixed before moving on, not left as a silent
    regression. Corrected coefficients (40.0 x 0.1145 = 4.58, 3.0 x 0.082 = 0.25), re-verified live:
    receivingYards MAE 18.77->18.68, receptions 1.379->1.371 - both genuinely improved this time,
    consistent with the regression's real finding once applied in the right direction. Full suite:
    75 tests passing throughout (test expectations updated to match, twice).

- (2026-08-20) **Real coefficient calibration for `PlayerPredictionService`, plus a serious
  data-loss bug found and fixed along the way.** Built a real regression pipeline instead of
  hand-guessing further: new `PredictionBacktestService#runCalibrationExport` (`GET /api/backtest
  /calibration-export`) walks every backtestable historical game and exports, per metric, the raw
  inputs `buildProjection` already computes (`recentAverage`/`seasonAverage` separately, plus
  every named adjustment term) - real data for an offline R regression, reusing the exact same
  point-in-time logic the live path uses rather than a second, hand-rolled feature computation
  that could drift from it.
  - **The bug**: the first calibration export came back with `advancedMetricAdjustment` exactly
    zero for every single passingYards row. Investigation traced this to `passing_cpoe`/
    `receiving_yac_above_expectation`/`receiving_separation_avg`/`rushing_yards_over_expected_per_att`
    being **completely empty across all 106,551 stored `player_game_stats` rows** - despite
    WORKPLAN already marking "Phase 3: NGS wiring" done back on 2026-08-13, and despite
    `etl_import_runs` showing a real, successfully-committed `import_nextgen_stats` run on
    2026-08-14 that wrote 3,704 real values. The data had been silently wiped since then. Root
    cause: `write_player_game_stats()` (`_common.R`) used a DELETE-then-INSERT pattern to upsert
    box-score rows, and its INSERT never included any enrichment column (NGS, PFR advanced
    rushing, snap counts) - so any later box-score refresh for an already-enriched player/season/
    week silently reset those columns to NULL. This is exactly what happened: this session's own
    earlier `refresh_stored_players_weekly.R` run (to backfill target_share/wopr) wiped out both
    the 2026-08-14 NGS data and 2,352 rows of PFR advanced rushing data from 2026-08-13, without
    any error or warning - confirmed directly (`rushing_yards_after_contact` also found at 0 for
    season 2025 after that run). Not a killed-process or Docker-corruption issue (ruled out: the
    `etl_import_runs` completion record for that run proves the transaction committed cleanly) -
    a straightforward architectural gap between two write patterns that both touch the same rows.
  - **The fix**: added a real unique constraint (`uq_player_game_stats_player_season_week` on
    `player_id, season, week`, declared on `PlayerGameStat.java` so Hibernate creates it) and
    rewrote `write_player_game_stats()` as a true `INSERT ... ON CONFLICT DO UPDATE`, with the
    `DO UPDATE SET` clause deliberately excluding every enrichment-only column (and `created_at`,
    so refreshes stop resetting a row's original creation timestamp too) - Postgres leaves any
    column not named in that clause untouched on conflict, so this is now structurally guaranteed
    rather than dependent on script run order to self-heal. Backfilled NGS for 2016-2024 (2025 was
    already redone) and re-ran PFR advanced rushing for 2025 to restore what was lost. **Verified
    the fix directly**: recorded exact enrichment counts for season 2025 (2,352 PFR-rushing, 561
    NGS-CPOE, 19,361 snap-count, 19,400 target_share rows), re-ran the full box-score refresh, and
    confirmed the counts came back byte-for-byte identical - proof the refresh can no longer wipe
    enrichment data.
  - **The calibration itself**, run against the now-clean data (`fit_calibration.R`, ad-hoc, not
    a permanent file): fit `actual ~ recentAverage + seasonAverage + opponentAdjustment +
    conditionsAdjustment + ...` per metric, using each term's CURRENT hand-picked value as the
    regression input, so the fitted coefficient on each term reads directly as "what fraction of
    this term's current magnitude does real data support." Consistent, strong finding across every
    metric: `opponentAdjustment` and `conditionsAdjustment` are both substantially overscaled -
    rushingYards needed only 44% of its current opponentAdjustment magnitude (p<0.0001, n=12,274),
    receivingYards 18% (p=0.0048, n=27,380), receptions 7% (p=0.003, n=27,380), touchdowns 16%
    (p<0.0001, n=27,380), passingYards 69% (weaker evidence, p=0.051, n=4,959); conditionsAdjustment
    (wind+total-line) needed only 51% for passingYards (p<0.0001) and 12% for receivingYards
    (p<0.0001), while rushingYards showed no measurable effect at all (p=0.93) - its total-line
    coefficient was cut to a small residual rather than removed outright. Applied all of these
    (see `opponentAdjustment`/`gameConditionsAdjustment` in `PlayerPredictionService.java` for the
    new values and full citations). **Not applied**: `targetShareAdjustment` (added earlier this
    session) came back needing 8-12x its current magnitude - real, statistically significant
    evidence, but too large a jump to trust from a single day-old term without more scrutiny
    (collinearity with `blendedMean` is a real risk given WOPR is volume-correlated) - flagged for
    a follow-up read once more backtest cycles exist. `rushingQualityAdjustment`/
    `advancedMetricAdjustment` (PFR after-contact/CPOE/YAC) showed weak, sometimes wrong-signed,
    non-significant relationships - left unchanged, real but inconclusive evidence either way. The
    global 0.65/0.35 recent/season blend split held up reasonably for yardage metrics (fitted
    ratios close to 65:35 for passingYards/rushingYards/receptions) but touchdowns/turnovers/
    receivingYards real ratios lean much more toward season-long average (e.g. receivingYards
    fitted ~42:58) - a real finding, not acted on this pass since making the blend per-metric
    instead of one global constant is a bigger architectural change.
  - **Verified live, before vs. after** (`GET /api/backtest/outcomes`, same clean dataset both
    times): passingYards MAE 69.00 -> 68.42, rushingYards 17.66 -> 17.16, receivingYards
    19.99 -> 18.77 (the biggest win - within a few hundredths of the regression's own fitted-model
    MAE ceiling of 18.64, meaning essentially all the available improvement for this metric was
    captured), receptions 1.418 -> 1.379, touchdowns 0.377 -> 0.373 - real, consistent improvement
    on every metric touched, no regression on passingTouchdowns/turnovers (left untouched, MAE
    unchanged as expected). Full suite: 72 tests passing (2 pre-existing `opponentAdjustment`
    assertions updated to the new calibrated values).
- (2026-08-20, later) **Closed out all three of Priority 5's remaining gaps: scheduler wiring, a
  real "beat the spread" backtest (which caught and fixed a real sign bug along the way), and a
  team offense/defense investigation.**
  - **(a) Scheduler wiring**: `import_schedules.R` and `compute_team_strength_ratings.R` were
    previously only ever run manually - added to `StatsRefreshWorker`'s existing scheduled chain
    (now 8 scripts, sharing the one due-check everything else uses). `import_schedules.R` runs
    first (everything downstream depends on `nfl_schedules` reflecting the newest final scores/
    lines), `compute_team_strength_ratings.R` runs last (needs those fresh completed games). No new
    worker/scheduler/due-checker triad needed - both scripts are cheap and fully idempotent (a real
    upsert for schedules, a full-but-fast Elo replay for ratings), so joining the existing chain was
    strictly simpler and safer than building a second one.
  - **(b) Beat-the-spread backtest, and the sign bug it caught**: new
    `GET /api/backtest/team-matchups/spread` (`TeamMatchupBacktestService#runSpreadBacktest`) -
    same shape as the existing totals backtest, comparing whether our predicted margin diverged
    from the market's implied home margin in the same direction the real result did, excluding
    pushes. The first version assumed nflverse's `spread_line` uses the traditional bookmaker
    convention (negative = favorite) and came back with an implausible 76.5% hit rate - too good to
    be a real edge against real closing lines from a simple hand-tuned Elo model. Verified
    independently in R rather than trusting or shipping it: `spread_line` (raw, unmodified)
    correlates +0.43 with real home margin, while its negation correlates -0.43 - proof the sign
    was backwards (nflverse's `spread_line` is already the home team's own implied margin directly,
    positive = home favored). Fixed the formula, and found the exact same wrong assumption baked
    into `PlayerGameStat.teamImpliedSpread`'s derivation in `_common.R` (same root cause, same fix
    shape - flip which side of the home/away branch gets negated) - fixed there too, and
    re-backfilled the column with the corrected sign across the full 2010-2025 history (106,560
    rows, `backfill_weather_vegas_columns.R`, ~35 minutes). See the corrected `team_implied_spread`
    entry below for the re-run investigation. **Corrected spread-backtest result, verified live**:
    49.7% hit rate (1,597 correct of 3,214 decided picks, 81 pushes, across 3,295 games) - an
    honest, credible "no real edge against the market" result, exactly what's expected from a
    simple point-differential Elo model going up against real closing lines. 3 new tests
    (`TeamMatchupBacktestServiceTest`).
  - **(c) Team offense/defense split - investigated, not built.** Fit `actual_margin ~
    elo_predicted_margin` (baseline) vs. `actual_margin ~ elo_predicted_margin +
    home_recent_scored_avg + home_recent_allowed_avg + away_recent_scored_avg +
    away_recent_allowed_avg` (adding each team's own last-8-game scoring/allowed averages
    separately, same point-in-time logic the totals backtest already uses) against 3,279
    backtestable games. The extended model is a real, statistically significant improvement (ANOVA
    F-test comparing the two models: p=8.9e-07) - `home_recent_scored`/`away_recent_scored` are
    both individually significant (p<0.001) and directionally sane (a team's own recent scoring
    helps its margin, the opponent's recent scoring hurts it). But the practical size is modest:
    R² improves 0.134 -> 0.143, MAE improves 10.32 -> 10.23 (about a tenth of a point). Deliberately
    **not implemented** this pass: the extended model's own fitted `elo_predicted_margin`
    coefficient drops to 0.65 (from 0.88 in the baseline, and implicitly 1.0 in the current live
    code) - properly incorporating this finding means rescaling the core Elo margin's own weight,
    not just adding a small additive nudge the way `PlayerPredictionService`'s adjustment terms
    work. That's a bigger, riskier change to an already-validated (63.9% winner accuracy) core model
    than a sub-1%-MAE improvement clearly justifies without further validation (in particular,
    out-of-sample testing - this read is in-sample, same caveat any single R² number carries).
    Documented as a real, evidence-backed finding and a good candidate for a dedicated future pass,
    not force-fit into today's session.
- (2026-08-20) **Investigated `team_implied_spread` as a live heuristic input - real effect found,
  deliberately not wired in.** **Corrected (2026-08-20, later)**: the original version of this
  entry reported the coefficients below with the opposite sign and called the RB direction
  "counter-intuitive" - that was itself a bug (see the same-day `team_implied_spread`/`spread_line`
  sign-convention fix in Priority 5's Recently Completed entry), not a real finding. Re-run after
  the fix: all directions are now the intuitive one (favored teams see slightly MORE production,
  not less), and R² is unchanged by the pure sign flip, so the "not worth wiring in" conclusion
  itself was never wrong - only the direction commentary was. Numbers below are the corrected ones.
  Ran a real regression (ad-hoc R script, not a permanent ETL file)
  against 104,237 stored `player_game_stats` rows with a real `team_implied_spread` (2010-2025):
  `rushingYards ~ team_implied_spread` for RBs (coefficient +0.44, p<0.0001, R²=0.0051, n=7,606),
  `passingYards ~ team_implied_spread` for QBs (+1.36, p<0.0001, R²=0.0074, n=5,227),
  `receivingYards ~ team_implied_spread` for WR/TE (+0.37, p<0.0001, R²=0.0040, n=20,778), and
  volume checks (`carries`/`receiving_targets`) showing the same tiny-but-real pattern (targets
  weren't even statistically significant, p=0.11). All three yardage relationships are real and
  directionally sane for QB/WR/TE and RB alike (favorites produce a bit more across the board,
  consistent with conventional wisdom) - but every R² is 0.001-0.007, roughly an order of
  magnitude weaker than even the team-totals model's already-modest 0.04-0.09 ceiling (see
  Priority 5's totals work). Conclusion:
  real signal, not worth wiring in - a coefficient this small mostly just adds a plausible-looking
  number without moving projections meaningfully, and effects this weak are hard to trust as
  genuine game-script signal rather than confounding (garbage time, backup units late in blowouts,
  etc.). Closes out this open question with an honest negative result instead of leaving it
  perpetually on the rotating list, and instead of forcing in a coefficient just to have "wired
  something" - explicitly the kind of fudging this whole priority has been trying to avoid.
- (2026-08-20) **Shipped snap counts and target share/WOPR** - verified the real source live before
  building anything (per this priority's own note): `nflreadr::load_snap_counts()` and
  `load_player_stats()`'s `target_share`/`air_yards_share`/`wopr` columns are both real, confirmed
  via a live pull before writing any code. Two very different costs to ship, so they got two
  different treatments:
  - **target_share/air_yards_share/wopr: nearly free.** These already come back from the exact
    same `load_player_stats()` call `_common.R` was already making for every other box-score field
    - they just weren't being selected into the `player_game_stats` insert. Widened
    `write_player_game_stats()`'s column list (3 new columns, no new nflverse call), added the 3
    matching `PlayerGameStat` fields, and wired a new `PlayerPredictionService#targetShareAdjustment`
    (receivingYards/receptions only) using WOPR - the composite metric, not target_share/
    air_yards_share separately, to avoid stacking three redundant role signals into one adjustment.
    Baseline (`LEAGUE_AVERAGE_WOPR = 0.28`) is a real number, not a guess: computed directly from
    4,451 stored WR/TE/RB player-games in the 2025 season with target_share > 0, queried right
    after the backfill landed. Backfilled via the existing `backfill_weather_vegas_columns.R` (a
    genuinely generic re-fetch-and-reshape script despite its weather/Vegas-specific name - see its
    updated header comment) plus a `refresh_stored_players_weekly.R` run: 19,400 rows for season
    2025 now carry real target_share/wopr. This adjustment is naturally backtestable (unlike the
    injury-status multiplier above) since wopr is a real stored per-game column, not a live-only
    signal - confirmed via a live `/api/backtest/outcomes` run after wiring it in (no crash, plausible
    receivingYards MAE ~19.7 across 27,380 samples, a fresh baseline post-change).
  - **Snap counts: a real new ingestion path.** New `import_snap_counts.R` (mirrors
    `import_pfr_advanced_rushing.R`'s shape exactly: PFR-sourced, keyed on `pfr_player_id`, crossed
    to `espn_athlete_id` via `load_players()$pfr_id`, an `UPDATE` against already-written
    `player_game_stats` rows, not a new insert). Wired into `StatsRefreshWorker`'s existing
    six-script chain (was five) so it refreshes on the same due-check as everything else. New
    `PlayerGameStat.offenseSnapPct` column (`snapCount` itself already existed but was always
    null - this is the first time it's ever been populated with real data). Verified live: 19,361
    of 19,400 season-2025 rows updated, real values spot-checked against Jaxon Smith-Njigba's
    playoff run (69-82% snap share, matching a true WR1 workload). **Deliberately NOT wired into
    the live heuristic yet** - unlike WOPR, a meaningful "average" snap share varies a lot by
    position (a workhorse RB and a rotational WR have very different normal baselines), so a single
    flat league-average comparison risks being actively wrong rather than just imprecise. Needs a
    position-aware baseline and its own backtest read first - flagged in the field's own doc
    comment and left as real, stored, unused data for a future pass, same discipline already
    applied to `receivingSeparationAvg`/`rushingYardsOverExpectedPerAtt`.
  - Full suite: 72 tests passing (2 new - `targetShareAdjustment` coverage in
    `PlayerPredictionServiceTest`; `StatsRefreshWorkerTest` updated for the sixth script).
- (2026-08-20) **Shipped the weekly injury/game-status designation feed** - the single largest
  previously-unmodeled error source (a real Questionable/Doubtful/Out swings actual production far
  more than any yardage nudge already in the model). New `EspnInjuryClient` pulls ESPN's
  league-wide injuries feed (`site.api.espn.com/apis/site/v2/sports/football/nfl/injuries`, same
  host/User-Agent gotcha as `EspnTeamClient` - confirmed live before building against it) - grouped
  by team, not by player, and notably the nested `athlete` object has no `id` field at all; `EspnInjuryMapper`
  regex-extracts the ESPN athlete id from the athlete's `playercard` link href (falling back to the
  headshot href) the same way `EspnPlayerClient` already does for its site-search path. New
  `InjuryStatusRefreshWorker`/`InjuryStatusRefreshScheduler` (`agp.injury-refresh.*`, 6h default
  poll) sync every stored player each run - one HTTP call covers the whole league, so unlike other
  refresh workers this doesn't need its own due-checker against `etl_import_runs`. Explicitly
  clears a player's stored status when they drop out of the feed (recovered), not just when a new
  status appears, so a healed player doesn't stay stuck showing a stale designation. New `Player`
  columns: `gameStatus`, `gameStatusDetail` (ESPN's own explanation text - had to be widened to
  `TEXT` after a live run hit a real longComment over 255 chars and failed every single save until
  fixed), `gameStatusUpdatedAt` - deliberately separate from the existing roster-level
  `injuryStatus` field, confirmed (2026-08-17) to genuinely diverge (a player can show "Active" via
  roster status while being reported "Out" for the week). Wired into `PlayerPredictionService` as
  a new `injuryStatusMultiplier` - unlike every other adjustment in that file, this is
  **multiplicative**, not additive (0.85x Questionable, 0.25x Doubtful, 0x Out/IR/PUP/Suspended/
  Did Not Report), applied last to the whole summed projection, since availability affects whether
  a player plays at all rather than how well - and deliberately NOT applied in
  `PredictionBacktestService`, since nothing in this database records a player's historical weekly
  status, only the live-fetched current one. `PlayerResponse`/`PlayerPredictionResponse` both
  surface the new fields; the player page's existing `resolveAvailability` now prefers the weekly
  `gameStatus` over the roster-level `injuryStatus` when both exist. Verified live end-to-end after
  a real sync (800 players in ESPN's feed, 790 stored players updated on first run): George Kittle
  (real "Out", torn Achilles) now projects 0 for every receiving metric with the real ESPN comment
  surfaced in the response; Jayden Higgins (real "Doubtful") shows every projection scaled to 25%.
  Full suite: 70 tests passing (14 new - `EspnInjuryMapperTest`, `InjuryStatusRefreshWorkerTest`,
  and `injuryStatusMultiplier`/full-response coverage added to `PlayerPredictionServiceTest`).
- (2026-08-20) **Adopted the real Vegas `total_line` for predicted game totals, computed estimate
  as fallback.** Followed up on item 9's structural-compression finding with a real regression
  (ad-hoc R script, not a permanent ETL file) fitting `actual_total ~ home_scored_avg +
  home_allowed_avg + away_scored_avg + away_allowed_avg` against every stored `TeamStrengthRating`
  pair: R² ≈ 0.04 (current formula's naive 50/50 blend is already close to this ceiling - refitting
  the coefficients wasn't going to meaningfully help). Also tried adding `div_game`/`home_rest`/
  `away_rest` from `nfl_schedules` - no meaningful R² improvement. As a reference point, tested the
  real Vegas `total_line` itself against real outcomes: R² ≈ 0.09, MAE 10.47 - still a low ceiling
  (NFL scoring genuinely is noisy game-to-game) but roughly double the explanatory power of the
  model's own inputs. Given explicit direction to prioritize accuracy over the "no market data"
  design philosophy, added `total_line` to the `NflSchedule` entity (already ingested by
  `import_schedules.R`, just never mapped in Java) and changed both `UpcomingTeamMatchupService`
  (live path) and `TeamMatchupBacktestService.runTotalsBacktest()` to prefer the real line when
  present, falling back to the existing recent-scoring estimate otherwise. Margin/win-probability
  prediction is unchanged - still Elo-only. Verified live: `/api/team-matchups/upcoming` now shows
  real varied totals matching actual posted lines (e.g. NE@SEA 44.5, SF@LAR 48.5) instead of every
  game clustering at 44-45; `/api/backtest/team-matchups/totals` MAE improved 10.93 -> 10.46 across
  3,295 games (112 of 272 current upcoming games have a posted line so far; earlier games get one
  as kickoff approaches, per nflverse's normal posting cadence).
- (2026-08-20) Extended `TeamMatchupBacktestServiceTest` with the totals-backtest test suite: a
  fallback-path case (no posted line) and a new case confirming a real posted `total_line` takes
  precedence over the computed estimate. Full suite: 56 tests passing.

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
- (2026-08-09) Fixed two real bugs behind slow first-time player loads (Priority 9):
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
  matched to this player profile.") with a real ESPN-sourced availability status (Priority 6,
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
- (2026-08-13) Fixed the outcome-MAE dilution flagged in the previous rotation:
  `metricsForPosition` now distinguishes a genuinely unknown position (null/blank - could still be
  a skill player with unsynced data, keeps the old graceful fallback) from a real, recognized
  non-skill position (`OL`/`DL`/`LB`/`DB`/`K`/`P`/`LS`/etc. - confirmed via a direct query of every
  distinct `position` value actually stored) via a small `SKILL_POSITIONS` set instead of enumerating
  every non-skill code, so new/unseen position strings degrade safely. Non-skill positions now get
  zero projections instead of the old RB-shaped fallback, in both the live endpoint (frontend
  already handled an empty `projections` array gracefully - confirmed by reading `App.jsx`, no
  frontend change needed) and the backtest. Verified end-to-end: re-ran `/api/backtest/outcomes`
  and `meanActual` values jumped back to real skill-player magnitudes (rushing yards 4.65 -> 28.4,
  receiving yards 9.34 -> 30.7, receptions 0.81 -> 2.67), with sample sizes dropping from the
  diluted ~78k-94k down to a clean 12.8k-28.5k - see Priority 2 for the full updated numbers.
- (2026-08-13) Shipped Priority 3's Phase 3 (NGS advanced metrics), continuing the same session's
  weather/Vegas + PFR rushing work - see
  `C:\Users\agpol\.claude\plans\elegant-leaping-scone.md` for the original design. New
  `etl/r/import_nextgen_stats.R` enriches existing `player_game_stats` rows (`UPDATE`, same PFR-
  script shape) via `nflreadr::load_nextgen_stats()`, reusing the box-score fetch's existing
  `gsis_id -> espn_id` crosswalk directly (NGS's `player_gsis_id` lines up with it, unlike PFR's
  separate `pfr_player_id`) - confirmed real column names and value ranges via live pulls before
  writing any code, not from memory (CPOE roughly -5 to +5 typical, YAC-over-expectation roughly
  -0.6 to +1.3 yds/catch typical). New `advancedMetricAdjustment` heuristic term: CPOE (already
  expressed relative to nflverse's own expectation model, so no separate baseline needed) nudges
  `passingYards`/`passingTouchdowns`; YAC-over-expectation nudges `receivingYards` using the same
  own-recent-rate-times-own-recent-volume shape as Phase 2's rushing nudge. Deliberately does NOT
  wire NGS's rushing-yards-over-expected metric into the heuristic (stored only) - it would stack a
  second, independent rushing-quality signal on top of Phase 2's PFR-based one with no evidence
  they're additive rather than redundant; same reasoning applied to receiving separation (stored,
  not wired). Verified against real 2025-season data (3,704 column values updated across 561-1,275
  rows depending on stat type): spot-checked rows show real, plausible values for real players
  (Matthew Stafford, Puka Nacua, Cooper Kupp, etc.), and the live endpoint shows correctly-scoped
  nonzero adjustments (Stafford passingYards: -2.42; Puka Nacua receivingYards: +5.11).
  - **Found and fixed a real, previously-undiscovered data-quality bug while verifying this**: 1,798
    `player_game_stats` rows across 22 players (Tyreek Hill, Josh Allen, Justin Jefferson, Ja'Marr
    Chase, CeeDee Lamb, Puka Nacua, and others) had `week = NULL` with a real `season` - leftover
    rows from the old, deleted ESPN game-log scraper (`source_url` pointed at
    `espn.com/nfl/player/gamelog`, not nflverse), the same bug class as the `season = NULL`
    orphans found and cleaned up in an earlier session, just a variant that slipped through that
    cleanup (which only targeted `season = NULL`). Postgres sorts `NULL` first on `ORDER BY ...
    DESC`, so these stale rows were silently winning "most recent game" for every prediction these
    22 players ever served - a real live-prediction correctness bug, not just a Phase 3 blocker.
    Confirmed every affected player also had solid real nflverse-sourced coverage before deleting
    (1,798 rows, all 22 players kept 27-160 real rows each) - not a data-loss risk, a pure cleanup.
  - Re-ran both backtests after the NGS ship + orphaned-row cleanup: numbers stayed close to the
    post-dilution-fix baseline (no dramatic swings, healthy given ~1.7% of rows were removed and two
    small new nudges were added) - market-line hit rates: receptions 53.6% (n=898), receiving yards
    51.1% (n=915), rushing yards 52.9% (n=425), passing touchdowns 57.8% (n=135), passing yards
    46.7% (n=135, the one metric that moved more than a couple points, still not concerning at this
    sample size). See Priority 2 for the full numbers.
- (2026-08-17) Closed out the orphaned-row bug class flagged in the previous rotation with a real,
  permanent fix instead of another manual cleanup: `PlayerGameStat.season`/`week` are now `NOT
  NULL` at the database level (`@Column(nullable = false)` plus a direct `ALTER TABLE ... SET NOT
  NULL`, since Hibernate's `ddl-auto: update` only adds new columns/tables - confirmed directly it
  does NOT retroactively alter an existing column's nullability, so the annotation alone didn't
  take effect until the manual `ALTER`). Deliberately did NOT add the same constraint to
  `game_date`: 2,314 rows legitimately have a null `game_date` (pre-2014 `nfl_schedules` gap, see
  Priority 7 - a known real limitation, not orphaned data), so that column stays nullable. This
  makes the `season = NULL`/`week = NULL` bug class impossible to reintroduce from any future write
  path, rather than relying on someone noticing a suspiciously-zero adjustment a third time.
  Verified the constraint actually took hold (`\d player_game_stats` shows `not null` on both
  columns) and that the backend still starts and serves predictions cleanly with it in place.
- (2026-08-17) Shipped Phase 4 (PFR defense aggregation), the last piece of the four-phase feature
  plan (`C:\Users\agpol\.claude\plans\elegant-leaping-scone.md`) - Priority 3's weather/Vegas/PFR-
  rushing/NGS/PFR-defense scope is now fully built. New `etl/r/import_pfr_defense_advanced.R`
  aggregates PFR's per-defender charting (`load_pfr_advstats(stat_type = "def")` - confirmed real
  columns and real per-team-game distributions via a live pull before writing any code: ~8.5
  pressures/game, ~9% missed-tackle rate, 2023-2025) up to team-game totals/rates (`team_defense_
  game_stats.pressures`/`missed_tackle_pct`) - summing the underlying counts first, then deriving
  the rate, rather than averaging each defender's own percentage (which would over-weight
  low-snap-count players). `UPDATE` against existing rows, same shape as the Phase 2/3 scripts;
  joins straight to `nfl_schedules` on `game_id` since PFR's `game_id` already matches nflverse's
  own format. Extended `opponentAdjustment` itself (not a new top-level adjustment field) with a
  pressure-vs-league-average nudge on `passingYards`/`passingTouchdowns` and a missed-tackle-vs-
  league-average nudge on `rushingYards`/`receivingYards` - folded in there rather than as a
  separate field since it's conceptually the same "how good is this opponent's defense" signal,
  just from richer data. Verified against real 2024-2025 data (1,133 rows updated): spot-checked
  values are plausible real team-games (5-18 pressures, 0-15% missed-tackle rate), and new unit
  tests lock in the nudge math directly.
  - **Found and fixed a serious, previously-undiscovered bug while verifying this - the actual
    headline finding of tonight's session, not Phase 4 itself.** After running the new script,
    re-running both backtests showed *zero* change versus the already-documented baseline - not
    "small," literally digit-for-digit identical, including a 27,380-sample MAE matching to 15
    significant figures. That's not plausible for a real signal being added, so it was chased down
    rather than accepted: `PredictionBacktestService.resolveOpponentDefenseHistory` looked up each
    target game's opponent via `teamsByEspnId.get(targetGame.getOpponentTeamId())` - but
    `PlayerGameStat.opponentTeamId` is stored as nflverse's raw team code (e.g. `"DAL"`, `"LA"`,
    confirmed via a direct query), while the map was keyed by `Team.espnTeamId` (ESPN's numeric id,
    e.g. `"23"`). Those two never matched, for any team, ever - meaning `opponentDefenseHistory` has
    been `List.of()` for **every single backtested game since this harness was first built on
    2026-08-12**, so `opponentAdjustment` (base yardage-allowed terms and every advanced term added
    on top since, including tonight's Phase 4 nudges) has contributed exactly 0 to every backtest
    comparison this whole session. The live prediction endpoint was never affected - it resolves
    the opponent through `Team.upcomingOpponentTeamId` (ESPN-sourced, always in the right format),
    a completely separate code path from the backtest's own opponent resolution. Fixed by keying
    the map by `Team.abbreviation` instead and applying the same nflverse-to-ESPN crosswalk
    `_common.R`'s `to_espn_team_abbreviation()` already uses on the R side (`"LA"`->`"LAR"`,
    `"WAS"`->`"WSH"`, everything else unchanged) before the lookup. Added a real test file for
    `PredictionBacktestService` (didn't exist before tonight) covering the crosswalk and the
    resolution end-to-end, including the exact Rams case that would have masked a partial fix.
  - **Corrected backtest numbers (2026-08-17, opponent-adjustment genuinely active for the first
    time)** - see Priority 2 for the full before/after. Movement was real but modest, not dramatic:
    outcome MAE got very slightly worse across every opponent-adjusted metric (e.g. receiving yards
    19.74 -> 19.80); market-line hit rate moved in a mixed direction (receiving yards, rushing
    yards, passing yards, and passing touchdowns each up ~1-2 points; receptions down from 53.6% to
    52.7%, the one metric that moved against the fix). Read as a healthy sign, not a red flag: a
    long-dormant signal turning on and producing a small, mixed effect - not a dramatic swing in
    either direction - is what "the coefficients are directionally reasonable but not
    backtest-tuned" (the class doc's own honest framing) should actually look like once real.
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
- (2026-08-19) Extended `team_defense_game_stats`/PFR advanced defense history back to 2018 (PFR's
  own charting floor - confirmed live, 2017 errors on the same call), matching
  `import_team_defense.R`/`import_pfr_defense_advanced.R` for seasons 2018-2023 on top of the
  existing 2024-2025 coverage - 4,566 rows now, up from 1,252, 4,411 with real PFR advanced-defense
  data. Re-ran both player-prop backtests: the narrow market-line window (Dec 2025-Feb 2026) was
  already fully covered by the pre-existing 2024-2025 data so those numbers didn't move (expected,
  not a bug); the outcome-MAE backtest (which spans back to 2010) showed small, mixed movement
  since many more historical games now have real `opponentAdjustment` signal applied.
- (2026-08-19) Shipped a V1 of Priority 5's team head-to-head model, re-prioritized to be tackled
  second this session per explicit user direction (ahead of the rest of the free-edge list above).
  New `etl/r/compute_team_strength_ratings.R` computes an Elo rating (with a margin-of-victory
  multiplier, the same shape FiveThirtyEight's public NFL model uses) for every team from real,
  already-fully-ingested game results (`nfl_schedules`, 3,295 completed games back to 2014) - a
  full recompute each run since Elo is inherently sequential, not an incremental enrichment like
  the other team/player scripts. Picked over simpler "average recent point differential" because
  raw averaging can't distinguish "beat bad teams by a lot" from "beat good teams by a little,"
  while Elo naturally prices in opponent strength through the same expected-score mechanism chess
  ratings use - still a small, hand-tuned, explainable system, not a trained model, consistent with
  this project's existing heuristic philosophy. New `TeamStrengthRating` entity/table (one row per
  team per game, `ratingBefore` for point-in-time-correct backtesting), new
  `TeamMatchupPredictionService` (rating differential -> predicted margin/win probability via
  standard Elo formulas) and `TeamMatchupBacktestService` (`GET /api/backtest/team-matchups`) -
  deliberately validated against real final scores first, not a market spread, per explicit product
  direction that this model's whole advantage is not needing to care about odds at all. **Real
  result: 63.9% winner-pick accuracy across all 3,295 games (correctSide 2104), mean absolute
  margin error 10.4 points** - a credible, not suspiciously-good number (in the same range published
  NFL Elo models like FiveThirtyEight's report), and well above the 50% baseline. Verified ratings
  are directionally sane against real 2025 context (Seattle/Philadelphia at the top, Tennessee/Las
  Vegas at the bottom).
  - **Found and fixed a related crosswalk gap while backfilling**: nflverse's historical team codes
    for three real franchise relocations - the Raiders ("OAK" through 2019 -> "LV"), the Chargers
    ("SD" through 2016 -> "LAC"), and the Rams' earlier stint ("STL" through 2015 -> "LAR") - aren't
    in the existing LA/WAS-only crosswalk (`_common.R`'s `to_espn_team_abbreviation()`), because no
    prior script had pulled data far back enough to hit them. Caused a hard crash writing ratings
    for those seasons (R's `[[` throws on a missing name instead of returning `NULL, unlike `[`)
    rather than a silent skip - fixed both the crosswalk gap (extended in both `_common.R` and a
    new shared Java `NflverseTeamAbbreviations` utility - see below) and the crash-prone lookup
    itself (now skips and logs instead of dying mid-run). This same gap likely affects the
    player-prop backtest too for any pre-2020 game against one of these three franchises -
    `PredictionBacktestService`'s own crosswalk shares the same fix via the new utility.
  - **Extracted `NflverseTeamAbbreviations`** (new, `team/service/`) as the one shared
    implementation of this crosswalk, used by both the new team-matchup backtest and
    `PredictionBacktestService` (which previously had its own private copy, added during the
    2026-08-17 opponent-lookup bug fix) - avoids a second copy silently drifting out of sync with
    the R-side version the way the LA/WAS-only version already had.
  - At this point still no live prediction endpoint/UI (added later the same session - see below),
    no "beat the spread" validation against the free `spread_line` already stored, and team offense
    (Priority 3) isn't factored in.
- (2026-08-19, later) Shipped the live-facing half of Priority 5 - a real "who do we predict to
  win" endpoint and a frontend page - plus a predicted score, both per explicit user direction the
  same session. Also found and fixed the reason the live endpoint couldn't have worked yet: **the
  2026 season schedule was never imported at all** - `nfl_schedules` only had 2014-2025 (all
  completed seasons) and zero rows for the upcoming season, since `import_schedules.R` had simply
  never been run for it. Ran it (`Rscript import_schedules.R 2026`) - 272 real, unplayed Week 1-18
  games loaded, safe to re-run any time (upsert on `game_id`).
  - **Predicted score, not just a winner pick**: `TeamMatchupPredictionService.MatchupPrediction`
    now also returns `predictedHomeScore`/`predictedAwayScore`, splitting the predicted margin
    around a fixed 44.5-point baseline total (same hand-picked-constant pattern, and the same
    number, as `PlayerPredictionService.DEFAULT_GAME_TOTAL_LINE`) - trivial to add since it's pure
    derived math from the margin the model already produces, not a second model. Deliberately not
    read from `nfl_schedules.total_line` (a real number, when posted) - consistent with this whole
    priority's point of not leaning on market data.
  - New read-only `NflSchedule` entity/repository (first Java mapping onto `nfl_schedules` - it
    existed only as an R-written table before tonight) and `UpcomingTeamMatchupService`: finds
    every not-yet-played REG/WC/DIV/CON/SB game, resolves both teams' most recent `ratingAfter` (a
    team's rating from its last completed game IS its rating entering the next one - no separate
    "current rating" concept needed), and predicts. Preseason is excluded for free, not by
    filtering it - nflverse's schedules dataset never included it to begin with (confirmed
    directly: no `"PRE"` `game_type` value exists anywhere in stored data). `GET
    /api/team-matchups/upcoming` - verified live against all 272 real 2026 Week 1-18 games, zero
    gaps (every team resolved a rating and a real opponent).
  - New `/matchups` page (`MatchupsPage` in `App.jsx`): every upcoming game grouped by week (or
    playoff round name once those exist), each shown as a card with both teams, the predicted
    score, the pick, and a confidence percentage. Verified the production build succeeds and the
    dev server serves the new route and transforms `App.jsx` without error - **could not visually
    verify rendering in an actual browser this session** (no headless-browser/screenshot tool
    available - see the new note under "Environment" below for what would close this gap).
  - **Removed the login/premium-access placeholder UI** per explicit user direction ("so far away
    from happening I don't even want to see it") - the disabled email/password form, its nav link,
    and the now-fully-dead CSS it was the only consumer of (`.login-form`, `.secondary`,
    `.spotlight`, `.bullet-list`). The nav's old `#login` anchor link is now a real `/matchups`
    route link. The adjacent "Featured spotlight" preview card (unrelated to login, just
    co-located in the same aside) was kept as-is.

**Environment / tooling gap (2026-08-19, user asked this be flagged for after the session)**: this
session has no headless-browser or screenshot tool available, so frontend changes (tonight's
`/matchups` page, and any future ones) can only be verified by build success + dev-server route
checks, not actual rendering - a real gap versus this project's own stated practice of visually
verifying UI changes before calling them done (an earlier session used Playwright directly for
exactly this). Whatever lets a future session drive a real/headless browser against the local dev
server (a Playwright MCP server, or equivalent browser-automation tool) would close this gap -
installing one is a user action, not something fixable from inside a session.
- (2026-08-20) Fixed all three `/matchups` product/UX issues flagged the prior session, same day.
  - **Real per-game predicted totals, replacing the fixed 44.5-point baseline**:
    `TeamMatchupPredictionService` gained `expectedTotalPoints(...)` (blends each team's own recent
    points-scored average against the *opponent's* recent points-allowed average - the same "own
    output vs. what this opponent typically allows" shape as `PlayerPredictionService
    .opponentAdjustment`, not a new pattern) and `predictScore(margin, expectedTotal)`.
    `UpcomingTeamMatchupService` computes each team's last-8-game scoring averages from
    `TeamStrengthRating` (which already tracked `pointsScored`/`pointsAllowed` per team-game -
    no new data needed) and feeds them in. `predictedMargin` itself - the actual validated,
    backtested signal (63.9% winner accuracy) - is unchanged; only how it gets split into a
    display score changed. Verified live: real 2026 Week 1-2 predictions now range 43-54 total
    points instead of clustering at 44-45.
  - **Winner/displayed-score consistency, with real tie detection**: `predictScore` now returns
    whole-number scores (`Math.round`ed once, inside the service) plus a `predictedTie` boolean
    computed from those same rounded numbers - `UpcomingTeamMatchupService` derives the winner
    from this one source of truth instead of the raw margin's sign, so the pick and the displayed
    score can never disagree again. `UpcomingMatchupResponse.predictedWinnerAbbreviation` is now
    nullable (null exactly when `predictedTie` is true); the frontend shows "Predicted tie" instead
    of a pick in that case.
  - **Public page scoped to 2 weeks, full season moved to a new admin endpoint**: `GET
    /api/team-matchups/upcoming` now returns only the earliest 2 distinct week groups (32 games,
    verified live); `GET /api/team-matchups/upcoming/all` returns the complete season (272 games,
    verified live) for admin/internal use. No auth gate on the admin path yet - this app has no
    admin-role infrastructure at all (Priority 4's admin view is unbuilt, and the login/premium
    placeholder UI was removed outright, not replaced), so this is a naming/scoping split for now,
    not a real security boundary; kept as its own path so real gating can be layered on later
    without touching the public endpoint's shape.
  - Along the way, fixed the same N+1 query risk this session already learned twice before
    (`PredictionBacktestService`, `TeamMatchupBacktestService`): `UpcomingTeamMatchupService` used
    to call `TeamStrengthRatingRepository.findAllByTeam_IdOrderByGameDateDesc` once per team per
    game; now preloads every team's rating history once via `findAll()` and groups/sorts in memory,
    same pattern as the other two fixes.
  - Verified end-to-end after a backend restart: real Docker container recreation happened this
    session (not something initiated here - `docker compose up` reported "Creating"/"Created"
    rather than reusing the existing container) - confirmed directly that all data survived
    (106,550 `player_game_stats` rows, 6,590 `team_strength_ratings` rows, 272 2026 schedule rows
    all intact), since the named `postgres_data` volume persists independently of container
    recreation. Worth knowing this can happen, not evidence anything is fragile.
- (2026-08-20, later) Built a real backtest for the predicted-total question raised right after
  shipping it, instead of debating it abstractly - new `TeamMatchupBacktestService
  .runTotalsBacktest()` (`GET /api/backtest/team-matchups/totals`), point-in-time correct like
  every other backtest this session (only games strictly before the target game feed its
  recent-scoring averages). Reports predicted vs. actual total side by side, including min/max, not
  just MAE - the direct comparison the question needed. **Real result across 3,279 games: mean
  predicted (45.6) matches mean actual (45.6) almost exactly, but the range doesn't - predicted
  totals span 28-64 while real totals span 3-105.** MAE is 10.93, a reasonable number in isolation,
  but it was hiding this real range problem.
  - **Tested whether shortening the recent-games window fixes it - it doesn't.** Tried
    `RECENT_GAMES_FOR_SCORING` at 4 and 1 (down from 8), re-running the same live totals backtest
    each time: range widens (span 36 -> 40 -> 68) but MAE gets meaningfully worse each step (10.93
    -> 11.23 -> 12.78 at window=1), and even the single-most-recent-game case never reaches the
    real 3-105 range. Reverted to 8 - best MAE of the three tested, and none of them solved the
    underlying problem. Diagnosed why: the compression comes from the formula's structure (blending
    each side's offense/defense signal via a flat average, then summing two already-averaged
    halves), not the window length - a real fix needs a different formula shape, not another
    constant. Documented as an open item, not fixed tonight.

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

**Correction (2026-08-17), read this before any number below**: every result in this section dated
before 2026-08-17 was computed with `opponentAdjustment` silently contributing exactly 0 to every
backtested game - a real bug in `PredictionBacktestService`'s opponent lookup (nflverse team codes
vs. ESPN team ids never matched), not a caveat about sample size or methodology. It's fixed now -
see Recently Completed for the root cause and the "Corrected results" block at the end of this
section for what actually changed once the signal turned on (modest, not dramatic). The numbers
below are kept for their own historical value (outcome MAE, sample-size growth, bug-hunting
process) but should not be read as evidence about `opponentAdjustment` specifically.

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
- Outcome MAE (**fixed 2026-08-13** - see Recently Completed for the `metricsForPosition`
  skill-position scoping fix; numbers below are the clean re-run, full catalog minus non-skill
  positions): rushing yards MAE 17.5 on a mean of 28.4 (n=12,797), receiving yards MAE 20.3 on a
  mean of 30.7 (n=28,517), receptions MAE 1.4 on a mean of 2.7 (n=28,517), touchdowns MAE 0.38 on a
  mean of 0.28 (n=28,517), passing yards MAE 67.6 on a mean of 215.6 (n=5,482), passing touchdowns
  MAE 0.91 on a mean of 1.38 (n=5,482), turnovers MAE 0.79 on a mean of 0.81 (n=5,481). Errors are
  proportional to each stat's real week-to-week variance again, same read as the original 54-player
  baseline but now over the full skill-position catalog - nothing that looks like a bug.

**Latest results (2026-08-13, after shipping Phase 3/NGS metrics + fixing a real orphaned-row data
bug - see Recently Completed)**: market-line hit rate - receptions 53.6% (n=898), receiving yards
51.1% (n=915), rushing yards 52.9% (n=425), passing touchdowns 57.8% (n=135), passing yards 46.7%
(n=135). All metrics stayed within a few points of the post-dilution-fix numbers above; healthy
given ~1.7% of rows were removed (real orphaned data) and two new small heuristic nudges were
added in between. Passing touchdowns remains the only metric clearing the ~52.4% vig threshold with
real margin.
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

**Corrected results (2026-08-17, `opponentAdjustment` genuinely active for the first time - see
Recently Completed for the bug)**: outcome MAE moved very slightly worse across every
opponent-adjusted metric (receiving yards 19.74 -> 19.80 on a mean of 29.07; rushing yards 17.64 ->
17.66 on a mean of 28.52; passing yards 67.79 -> 67.86 on a mean of 216.44; passing touchdowns
0.9147 -> 0.9145, a tiny improvement; receptions and touchdowns also moved slightly worse -
turnovers unchanged, as expected, since neither `opponentAdjustment` nor the Phase 4 nudge touches
that metric). Market-line hit rate moved in a mixed direction: receiving yards 51.1% -> 51.3%
(n=915), rushing yards 52.9% -> 54.1% (n=425), passing yards 46.7% -> 47.4% (n=135), passing
touchdowns 57.8% -> 58.5% (n=135) - all up modestly; receptions 53.6% -> 52.7% (n=898) - the one
metric that moved against the fix. Read this as a healthy result, not a red flag: a long-dormant
signal turning on and producing a small, mixed effect (not a dramatic swing in either direction) is
what a directionally-reasonable-but-not-backtest-tuned coefficient set should actually look like
once it's real - the alternative (a huge jump) would have been the more suspicious outcome.

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

- Steps 1 and 2 (outcome MAE, market-line hit rate) are done, and as of 2026-08-17 both are
  computed with `opponentAdjustment` genuinely active for the first time (see the "Corrected
  results" above and Recently Completed for why it wasn't before).
- Not yet decided: whether the current near-50%/bigger-sample/opponent-adjustment-active market-line
  results are enough evidence to revisit the sequenced recommendation above. This is the actual open
  call now - everything before it was infrastructure to get here.

## Priority 3: Prediction Input and Feature Completeness

Goal: close the gap between what our model sees and what a sportsbook's line already prices in -
this is what actually creates the possibility of a real edge once Priority 1's lines exist to
compare against, more so than model architecture (see Priority 2).

Desired behavior:

- Add missing stat ingestion that materially improves prediction quality: snap counts, drops,
  target share trends (not just topline box-score totals).
- Build a cleaner team offense model to pair with the defensive data already being collected.
- ~~Expand matchup features so predictions account for the opponent more realistically (coverage
  tendencies, pace of play, not just aggregate yards/points allowed)~~ **Coverage tendencies done
  (2026-08-26)** - `zoneCoverageRate` (nflreadr::load_participation()) is a real, trailing-average-
  calibrated `opponentAdjustment` signal now (receivingYards/receptions) - see Recently Completed.
  Pass-rush-frequency and defenders-in-box were tested the same rigorous way and did not hold up as
  real trailing predictors (real checked negative results, not wired in). Pace of play (plays/game,
  seconds/play - also available via nflverse `load_pbp()`) is not yet investigated.
- ~~Add weather and Vegas-derived features~~ **Done (2026-08-12)** - see Recently Completed.
  `gameConditionsAdjustment` covers wind (live-forecast-aware for upcoming games, real historical
  values for backtesting) and the Vegas game total; `team_implied_spread` is stored/backtestable
  but deliberately not wired into the live heuristic yet (see the plan doc - its effect is
  genuinely bidirectional and needs its own backtest read before picking a coefficient direction).
  PFR advanced rushing (yards before/after contact, broken tackles) also shipped as a first slice
  of this priority's "richer player-level input" goal, via a new `rushingQualityAdjustment` term.
  **Phase 3 done too (2026-08-13)**: NGS advanced metrics (CPOE for passing, YAC-over-expectation
  for receiving) wired into a new `advancedMetricAdjustment` term; NGS's rushing-yards-over-expected
  and receiving-separation metrics are stored but deliberately not wired in, to avoid stacking a
  second, independent quality signal on the same metric without evidence it's additive.
  **Phase 4 done too (2026-08-17), closing out the full four-phase plan**
  (`C:\Users\agpol\.claude\plans\elegant-leaping-scone.md`): PFR advanced defense (per-defender
  pressure/missed-tackle charting, aggregated up to team-game level) folded directly into
  `opponentAdjustment` as a pressure nudge on passing metrics and a missed-tackle nudge on rushing/
  receiving yardage. Verifying this is what surfaced a much bigger, previously-undiscovered bug in
  `opponentAdjustment` itself (see Recently Completed and Priority 2) - the actual headline finding
  of that session, well beyond Phase 4's own scope.
- ~~Close the weekly-availability gap found while building the first injury-status slice~~ **Done
  (2026-08-20)** - see Recently Completed. `athlete.status` only ever reflected roster status (IR/
  PUP/suspended/day-to-day), never the weekly Questionable/Doubtful/Out-for-Sunday designation that
  actually moves lines and should move projections - that gap is now closed via ESPN's league-wide
  injuries feed, synced every 6h, applied as a multiplicative participation-likelihood scale on
  every projection (not an additive nudge - see `PlayerPredictionService#injuryStatusMultiplier`).

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

## Priority 5: Team Head-to-Head Prediction Model

Goal: a second, independent prediction surface - who wins a given team matchup, and by how much -
validated against real game outcomes we already have (nflverse `nfl_schedules` final scores, back
to 2014 currently ingested, nflverse itself has it back to 2006), without depending on Priority 1's
paid player-prop odds decision at all. Explicit user direction (2026-08-17) on where this sits:
below the core player-prop mission (Priorities 1-4), but above general UI polish/maintenance
(Priorities 6-9). Re-sequenced (2026-08-19) to be tackled second in that session, ahead of the rest
of the free-edge player-model list, since it doesn't compete with Priority 1's paid-data question.

Why this is attractive independent of Priority 1: `nfl_schedules` already has real final scores,
plus `spread_line`/`total_line`/moneylines in the same table - a genuinely large, free,
multi-season dataset to both build from and validate against. Player props are bottlenecked on a
~2-month free odds window; a team-level model isn't, because "did we predict the winner/margin
correctly" is answerable directly from data already fully ingested, at real season-spanning scale,
with no purchase required for statistical confidence.

**V1 shipped (2026-08-19)** - see Recently Completed for the full build. An Elo rating (with a
margin-of-victory multiplier) computed from real game results, backtested against real final
scores: **63.9% winner-pick accuracy across 3,295 games since 2014, mean absolute margin error
10.4 points** - a credible result in the range published NFL Elo models report, not suspiciously
good. `GET /api/backtest/team-matchups`.

**Live endpoint + frontend page shipped the same session (2026-08-19, later)** - `GET
/api/team-matchups/upcoming` predicts every not-yet-played REG/playoff game (272 real Week 1-18
2026 games as of tonight, once the 2026 schedule itself was found missing and imported), including
a predicted score (not just a winner pick - simple derived math from the margin, not a second
model). New `/matchups` page shows it grouped by week. Frontend build verified, actual rendering
not yet visually confirmed (no browser tool available this session - see the new Environment note
in Recently Completed).

Desired behavior (updated post-V1 + live shipment):

- ~~Predict a team-vs-team outcome~~ **Done** - `TeamMatchupPredictionService` predicts margin
  and home win probability from two teams' Elo ratings, plus (2026-08-20) a real per-game predicted
  score derived from both teams' own recent scoring history, not a fixed baseline. Still uses only
  real game results (points scored/allowed) to compute strength, not `TeamDefenseGameStat` or a
  team offense equivalent as separate inputs - **investigated (2026-08-20), not built**: a real,
  statistically significant improvement exists (ANOVA p<0.001 vs. Elo-only) but is modest in
  practice (R² 0.134->0.143, MAE 10.32->10.23) and would require rescaling the core Elo margin's
  own weight to apply correctly, not just adding a nudge - too large a change to the validated core
  model for the size of the gain this pass. See Recently Completed for the full regression.
- ~~Validate against real final scores~~ **Done**. ~~Still open: validate against the free
  spread_line already stored~~ **Done (2026-08-20)** - `GET /api/backtest/team-matchups/spread`,
  see Recently Completed (including a real sign bug this work caught and fixed).
- ~~No live prediction endpoint or UI~~ **Done** - see above. ~~Public page shows all 18 weeks,
  winner pick can disagree with the displayed score, total is a fixed unrealistic baseline~~ **All
  three fixed 2026-08-20** - see Recently Completed.
- Keep this a separate model/surface from the player-prop heuristic, not a replacement - the two
  serve different bets and don't need to share architecture just because they share some
  underlying data (team defense history, schedules). Held so far: no shared code between
  `TeamMatchupPredictionService` and `PlayerPredictionService` beyond the crosswalk utility.

Implementation ideas:

- ~~Add the "beat the spread" backtest~~ **Done (2026-08-20)**.
- ~~Decide whether a team offense model would meaningfully improve prediction quality~~
  **Investigated (2026-08-20)** - real but modest effect found, not built this pass. See Recently
  Completed.
- **Style-vs-style matchup awareness (2026-08-26, explicit user direction).** The 2026-08-20
  investigation above tested a *symmetric* idea: does adding "how good is this team on offense/
  defense overall" to the Elo margin help - found a real but small effect, correctly declined to
  force it in. This is a genuinely different, *asymmetric* question: can team A be a bad matchup
  for team C specifically - man coverage vulnerable to exactly the routes C's offense runs, a
  light box exploitable by exactly the run scheme C uses - independent of A and C's overall
  quality, such that A struggles against C while beating B, and B in turn beats C? Elo is
  transitive by construction (a single strength number per team can't represent "A but weak
  specifically against C-style offenses"), so this needs its own interaction term, not a small
  nudge on the existing margin.
  - ~~A team offense equivalent of TeamDefenseGameStat doesn't exist yet~~ **Done (2026-08-28)** -
    new `TeamOffenseGameStat`/`import_team_offense.R` (`passRate` 2018-2025, `shotgunRate`
    2023-2025 only after a real taxonomy-change bug was found and fixed - see Recently Completed).
  - ~~Test a concrete, pre-specified style-interaction hypothesis~~ **Done (2026-08-28) - a real,
    thorough null result at the coarse (team-game-aggregate) level.** Six pre-specified interaction
    terms (pass-heavy offense x zone-heavy defense, run-heavy offense x stacked box, shotgun-heavy
    offense x high-pressure defense, both directions), tested jointly and individually against the
    already-validated 63.9%-accuracy Elo baseline - none significant either way (joint model
    p=0.16-0.97; individually p=0.10-0.86; joint F-test p=0.487; R²/MAE barely moved). Nothing
    wired in - see Recently Completed for the full numbers. This is a real, useful negative result
    (matches the 2026-08-20 symmetric-split investigation's own honest conclusion), not a dead end:
    see the next bullet for what it doesn't settle.
  - ~~The play-level EPA version~~ **Done (2026-08-28, later) - real, converging null across four
    tests, not built into the app.** `load_pbp()`/`load_participation()` join confirmed clean first
    (100% of participation's rows matched real EPA data on `game_id`/`play_id`), and a real
    in-sample interaction between team identity and coverage-faced was confirmed to exist
    (ANOVA p=0.0067) before investing in the full point-in-time test - so this wasn't skipped or
    under-tried. The actual predictive test (offense's own trailing EPA-by-coverage split x
    opponent's trailing zone rate, predicting real game EPA, controlling for aggregate EPA/zone
    tendency): null (p=0.410, n=1,613). Refined to pass-plays-only (the football-correct scope for
    a coverage concept): still null, weaker (p=0.653). A genuinely distinct run-game/box-count
    version: also null (p=0.380, n=1,599). See Recently Completed for the full numbers. **Paused,
    not abandoned or left ambiguous** - four independent, honest nulls across real operationalizations
    of the same idea is real evidence this specific approach (simple trailing-average interactions,
    2023-2025 sample) doesn't surface a usable signal; running more variations chasing significance
    would be the exact overfitting trap this priority was scoped to avoid. Revisit once real 2026
    in-season data exists as a genuinely new angle - not as a default next-session task.
- Consider whether the hand-picked constants (`K_FACTOR = 20`, `HOME_FIELD_ADVANTAGE_ELO = 55`,
  `ELO_POINTS_PER_MARGIN_POINT = 25`, and - new as of 2026-08-20 -
  `RECENT_GAMES_FOR_SCORING = 8` for the predicted-total calculation) hold up under real
  calibration, same open question Priority 2's rotating-list item 4 raises for the player-prop
  heuristic's own constants. Explicit user-agreed target for this: high-60s to low-70s winner
  accuracy is a realistic goal to iterate toward; 75%+ sustained is not (see the 2026-08-19
  discussion - Vegas/FiveThirtyEight-caliber public models top out around 65-70%, and NFL's
  inherent single-game variance is a structural ceiling, not a modeling gap).
- Weekly schedule refresh needed: `import_schedules.R` isn't on any scheduler yet (this session's
  gap - the 2026 season had simply never been imported) - wire it into the existing
  `StatsRefreshWorker` cadence or a similar due-check so next season's schedule (and each week's
  real scores as they're played) load automatically instead of requiring a manual run.
- Re-run `compute_team_strength_ratings.R` on the same cadence once games start being played each
  week (it's a full, cheap recompute - no incremental-update logic needed).

## Priority 6: Player Page Experience and Derived Stats

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

## Priority 7: Historical Data Pipeline (R/nflverse)

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
  before 2014 - now affects 2,314 rows (grew from the ~95-row estimate in an earlier session, once
  the 2026-08-12 backfill expanded `player_game_stats` to the full player catalog - see Recently
  Completed). `game_date` is deliberately left nullable for this reason (see the 2026-08-17 NOT
  NULL entry below) rather than backfilled further back - extend `nfl_schedules`'s coverage if a
  long-career player's pre-2014 games actually need it.
- **Fixed (2026-08-17)**: the orphaned-row bug class (old ESPN game-log leftovers with
  `season`/`week = NULL` winning "most recent game" via Postgres's NULL-sorts-first-on-DESC
  behavior - two real instances found across two sessions) now has a permanent `NOT NULL`
  constraint on both columns, not just another manual cleanup - see Recently Completed.
- Decide which remaining datasets (if any) should move from ESPN to nflverse.
- Add a proper scheduler/job runner under `etl/jobs/` if the current Java-triggered model stops
  being enough - not needed yet.

## Priority 8: Team and Defense Data Foundation

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
- ~~Investigate whether defensive scheme (man/zone rate, etc.) is available anywhere reliable~~
  **Done (2026-08-26)** - see Priority 3 and Recently Completed.

## Priority 9: Search, Candidate Matching, and Performance Maintenance

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
- ~~Expand from player analysis into team-level analysis later~~ **Scoped as Priority 5
  (2026-08-17)** - see that section for the team head-to-head model.
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
  lines - when in doubt about what to prioritize, favor whatever moves Priorities 1-4 forward,
  then Priority 5 (team head-to-head model), over polish on Priorities 6-9.

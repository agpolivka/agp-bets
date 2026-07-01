# AGP Bets Work Plan

This document tracks the active product direction and the next implementation targets.
It should stay lightweight and evolve as the project matures.

## Current Focus

The project now has a real React/Vite frontend, dedicated player pages, automatic player
hydration, automatic stat sync on page load, player headshots on the detail screen, and a
better search flow that favors stored players first and ESPN fallback second.
The next session should focus on shipping the first prediction endpoint, then improving
the quality of derived insights, cleaning up what information is displayed, and continuing
to harden historical stat freshness.
The premium or special-user layer should remain a future phase, after the derived-stat
experience is strong enough to justify it.

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

## Priority 1: Derived Stats and Insights Cleanup

Goal: make the derived insight layer more trustworthy, more useful, and easier to build on.

Desired behavior:

- Tighten the formulas and output for the derived player stats we care about most.
- Make sure the top summary cards are meaningful across QB, RB, WR, and TE.
- Add stronger last-X-game views, home/away splits, opponent splits, and trend summaries.
- Keep raw game stats as the source of truth and derive everything cleanly on top.
- Add more matchup-aware derived views that can later support premium-only insights.
- Add the first player prediction endpoint with a mean projection and confidence interval.
- Start with a simple weighted-average model before getting fancy with machine learning.
- Keep the prediction layer honest by clearly exposing when confidence is low.
- Keep the prediction layer honest by tracking the missing inputs that impact quality most:
  - incomplete snap counts and drops
  - missing team offense model
  - incomplete defensive matchup features
  - missing injury, weather, Vegas, and projected-usage inputs

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

Implementation ideas:

- Improve the season and game-log backfill strategy.
- Add a better scheduled/admin refresh path for old and new stat lines.
- Preserve raw game stats as the source of truth and derive insights from them.
- Add lightweight search-result caching and consider broader preload jobs only after the public player experience needs them.
- Fill in stat fields that materially improve prediction quality, especially:
  - snap counts
  - drops
  - older-season historical coverage
  - any missing team/game metadata that reduces matchup quality

## Priority 3: Clean Up Displayed Information

Goal: make the player page show the right information in the right places without noise.

Desired behavior:

- The most important stats should be obvious at a glance.
- Position-specific players should not see misleading or empty top-box values.
- Internal or overly technical wording should be removed from the user-facing screen.
- Error and loading states should stay polished and simple.

Implementation ideas:

- Rework the top summary cards by position group.
- Decide which stats belong in the hero area, summary cards, and game log table.
- Trim fields that are technically present but not useful yet.
- Continue moving the app away from a database-viewer feel.

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

## Priority 5: Team Identity and Visuals

Goal: round out player pages with team-aware visuals once team data is modeled more cleanly.

Desired behavior:

- Show the team's primary logo alongside the player data.
- Keep player/team responsibilities clean in the codebase.
- Use ESPN assets where possible and add a fallback if needed.

Implementation ideas:

- Add team asset handling once team modeling is introduced.
- Avoid stuffing long-term team concerns directly into player-only components.
- Reuse the current player visual card once team branding is ready.

## Priority 6: Team Defense Data Foundation

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

- Use `Team` for identity and branding data.
- Use `TeamDefenseGameStat` as the source of truth for defensive history.
- Derive defensive season totals and matchup summaries from stored game rows rather than storing every aggregate up front.
- Add follow-up work for:
  - weekly defensive rank calculations
  - season aggregate defensive views
  - team logos on player pages
  - investigating whether defensive scheme is available anywhere reliable
  - revisiting receiving-yards-allowed if ESPN exposes a better source than team passing totals

## Priority 7: Candidate Matching Cleanup

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

## Priority 8: Performance and Responsiveness

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

## Working Agreement

- Store raw data once.
- Derive insights from stored data.
- Keep player ingestion reliable.
- Prefer incremental improvements that make future betting and model work easier.

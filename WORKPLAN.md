# AGP Bets Work Plan

This document tracks the active product direction and the next implementation targets.
It should stay lightweight and evolve as the project matures.

## Current Focus

The project now has a real React/Vite frontend, dedicated player pages, automatic player
hydration, automatic stat sync on page load, and player headshots on the detail screen.
The next session should focus on improving the quality of derived insights, cleaning up
what information is displayed, and continuing to harden historical stat freshness.

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

## Priority 1: Derived Stats and Insights Cleanup

Goal: make the derived insight layer more trustworthy, more useful, and easier to build on.

Desired behavior:

- Tighten the formulas and output for the derived player stats we care about most.
- Make sure the top summary cards are meaningful across QB, RB, WR, and TE.
- Add stronger last-X-game views, home/away splits, opponent splits, and trend summaries.
- Keep raw game stats as the source of truth and derive everything cleanly on top.

Implementation ideas:

- Audit each derived field against the stored game stat rows.
- Prioritize offensive betting views first:
  - QB passing/rushing/turnover summaries
  - RB rushing/receiving opportunity summaries
  - WR/TE target, reception, and yardage summaries
- Add clearer role-aware insight cards instead of one generic layout for every position.

## Priority 2: Clean Up Displayed Information

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

## Priority 3: Historical Backfill and Stat Freshness

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

## Priority 4: Team Identity and Visuals

Goal: round out player pages with team-aware visuals once team data is modeled more cleanly.

Desired behavior:

- Show the team's primary logo alongside the player data.
- Keep player/team responsibilities clean in the codebase.
- Use ESPN assets where possible and add a fallback if needed.

Implementation ideas:

- Add team asset handling once team modeling is introduced.
- Avoid stuffing long-term team concerns directly into player-only components.
- Reuse the current player visual card once team branding is ready.

## Priority 5: Candidate Matching Cleanup

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

## Longer-Term Direction

- Add richer player trend and split views.
- Add role-specific player cards and comparison views.
- Introduce caching for common lookups.
- Build prediction-ready features from the stored history.
- Expand from player analysis into team-level analysis later.

## Working Agreement

- Store raw data once.
- Derive insights from stored data.
- Keep player ingestion reliable.
- Prefer incremental improvements that make future betting and model work easier.

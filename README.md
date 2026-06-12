# AGP Bets

AGP Bets is a sports stats and prediction project focused on building a solid player data pipeline first, then using that data for analysis, projections, and eventually prediction features for players and teams.

The current direction is:

1. Pull player data from a public sports API through the backend.
2. Store that data in PostgreSQL so it is durable and queryable.
3. Expose the stored data through a frontend so players and stats can be viewed in the browser.
4. Expand the dataset into historical trends and prediction inputs over time.

## Project Overview

AGP Bets is organized as a backend-first application with a lightweight frontend:

- `backend/goforbroke` contains the Spring Boot service.
- `frontend` contains the browser UI.
- PostgreSQL is the primary data store.
- Redis will be introduced later as a cache for hot lookups.
- Docker Compose is used for local infrastructure.

### Backend responsibilities

- Fetch player data from the external API
- Normalize and persist player records
- Serve application APIs to the frontend
- Refresh or re-sync player data when needed
- Cache hot lookups once Redis is added

### Frontend responsibilities

- Search for players
- Display player details, team, and position
- Show stored player data from the backend
- Eventually present trends, summaries, and projections

## Suggested Stack

- Backend: Spring Boot, Java 21
- Database: PostgreSQL
- Cache: Redis
- Frontend: Angular or another modern SPA framework
- Packaging and local ops: Docker and Docker Compose

## Roadmap

### Phase 1: Player ingestion and storage

Goal: retrieve player data from the public API and persist it in PostgreSQL.

- Add and refine the API client code in the backend
- Create database entities and tables for players and stat records
- Build an ingestion flow that can fetch, normalize, and save player data
- Backfill missing player metadata like team, position, and active status
- Add API endpoints to search and query stored players
- Start with offensive players first: `QB`, `RB`, `WR`, and `TE`
- Store game-level stat lines so future averages and splits can be computed from real history

### Core offensive stat targets

These are the main fields we want to support for each player category.

#### QB

- Passing yards
- Rushing yards
- Total yards
- Passing touchdowns
- Rushing touchdowns
- Total touchdowns
- Interceptions
- Fumbles
- Fumbles lost
- Games played
- Turnovers
- Snap count

#### WR / TE / RB

- Carries
- Receiving targets
- Receptions
- Receiving yards
- Rushing yards
- Touchdowns
- Fumbles
- Fumbles lost
- Games played
- Drops
- Snap count

### Data model strategy

To support predictions later, the backend should store each stat line in a way that can be queried by player, game, opponent, and home/away context.

Recommended core concepts:

- `Player` for identity, team, position, and active status
- `Game` for date, week, season, and matchup context
- `PlayerGameStat` for one player’s stat line in one game
- `PlayerSeasonSummary` or a derived summary view for quick display
- optional raw ESPN payload storage for traceability and reprocessing later

This structure lets us build useful mashups from the same data, including:

- last `x` game averages
- home vs away splits
- opponent splits
- rolling averages
- season totals
- trend comparisons like last 3 vs last 5 vs season average

### Phase 2: Frontend player viewer

Goal: make the stored player data visible in the browser.

- Keep the frontend aligned with backend player endpoints
- Add player search and candidate selection
- Display player details, team, position, and stored stats
- Connect the UI to the backend in a simple, reliable way
- Show recent-game views and useful summary splits once the backend exposes them

### Phase 3: Caching and refresh behavior

Goal: reduce repeated API and database work while keeping data fresh.

- Add Redis caching for hot player lookups
- Define cache expiration and refresh rules
- Add manual refresh and background sync behavior
- Make repeated lookups fast without blocking ingestion

### Phase 4: Historical analysis

Goal: make the dataset useful for trends and comparisons.

- Store historical snapshots and game-by-game stat lines
- Add rolling averages and trend metrics
- Preserve opponent and team context in the data model
- Build cleaner time-based records for later analysis

### Phase 5: Prediction features

Goal: turn the stored history into usable forecasting inputs.

- Build feature generation from historical stats
- Experiment with projection logic for players and teams
- Add prediction endpoints or services
- Evaluate model outputs against real results

## Near-Term Focus

The immediate implementation priorities are:

1. Fix and backfill player team / position metadata so stored players stay trustworthy.
2. Keep player ingestion reliable and easy to re-run.
3. Build the game-level stat model for offensive players first.
4. Add summary and split views that can power future betting and prediction logic.
5. Expand the player detail UI once the backend data is solid.

The next major infrastructure step is Docker-based PostgreSQL so local development stays consistent and reproducible.

## Development Approach

For now, the project is intended to run in a simple local setup:

- Run PostgreSQL with Docker Compose
- Run the backend with Maven
- Run the frontend with its normal dev command

Once that flow is stable, the full stack can be containerized together.

## Local Setup

### Start PostgreSQL

```bash
docker compose up -d postgres
```

### Start everything

```powershell
.\start-dev.ps1
```

If you prefer Git Bash:

```bash
./start-dev.sh
```

To stop the stack from Git Bash:

```bash
./stop-dev.sh
```

### Backend defaults

The backend connects to PostgreSQL on `localhost:5432` by default.

### Optional environment overrides

If you want to use different database settings, override these environment variables:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`

### Maintenance endpoints

The backend includes an admin-style metadata refresh endpoint for fixing stale player records:

- `POST /api/players/backfill-metadata`

This scans stored players that are missing team, position, team id, or active status, then refreshes them from ESPN and persists the updated values.

### Player insights endpoint

Derived player insights are computed from stored game logs rather than stored as a second source of truth.

- `GET /api/players/{espnAthleteId}/insights`

This returns:

- the player record
- recent game logs
- overall stat summaries
- last 5 game averages
- last 3 game averages
- home/away splits
- opponent splits

The important part is that the raw `player_game_stats` rows stay as the durable history, and the insights are calculated from those rows on demand.

## Notes

- The project is intentionally being built in phases so the data model can mature before prediction work begins.
- Historical data is more valuable than only the latest snapshot, so the schema should preserve time-based records whenever possible.
- Redis should be treated as a cache, not the source of truth.

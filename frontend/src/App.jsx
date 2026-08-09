import { useEffect, useState } from "react";
import { BrowserRouter, Link, Route, Routes, useLocation, useParams } from "react-router-dom";
import { featuredPlayers } from "./data/featuredPlayers";
import {
  getPlayer,
  getPlayerInsights,
  getPlayerPredictions,
  getTeam,
  getTeamDefenseSummary,
  searchPlayers,
  syncPlayerByAthleteId,
  syncPlayerStats,
} from "./lib/api";

const SEARCH_RESULT_LIMIT = 5;
const SEARCH_RESULT_THRESHOLD = 0.25;
const MATCHUP_RETRY_ATTEMPTS = 2;
const MATCHUP_RETRY_DELAY_MS = 1500;
const BACKFILL_POLL_ATTEMPTS = 8;
const BACKFILL_POLL_DELAY_MS = 2000;

function buildEspnHeadshotUrl(athleteId) {
  if (!athleteId) {
    return null;
  }

  return `https://a.espncdn.com/i/headshots/nfl/players/full/${athleteId}.png`;
}

function getPlayerInitials(displayName) {
  if (!displayName) {
    return "AGP";
  }

  return displayName
    .split(" ")
    .filter(Boolean)
    .map((part) => part[0])
    .join("")
    .slice(0, 3);
}

function formatNumber(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return "-";
  }

  return new Intl.NumberFormat().format(Number(value));
}

function formatDate(value) {
  if (!value) {
    return "Unknown";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Unknown";
  }

  return date.toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

function formatShortDate(value) {
  if (!value) {
    return "Unknown";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Unknown";
  }

  return date.toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
  });
}

function formatLongDate(value) {
  if (!value) {
    return "Unknown date";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Unknown date";
  }

  return date.toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

function formatPace(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return "-";
  }

  return Number(value).toFixed(1);
}

const PREDICTION_METRIC_META = {
  passingYards: { label: "Passing Yards", unit: "yds", decimals: 0 },
  rushingYards: { label: "Rushing Yards", unit: "yds", decimals: 0 },
  receivingYards: { label: "Receiving Yards", unit: "yds", decimals: 0 },
  receptions: { label: "Receptions", unit: "rec", decimals: 1 },
  touchdowns: { label: "Touchdowns", unit: "TD", decimals: 1 },
  passingTouchdowns: { label: "Passing TDs", unit: "TD", decimals: 1 },
  turnovers: { label: "Turnovers", unit: "TO", decimals: 1 },
};

function predictionMetricMeta(metric) {
  return PREDICTION_METRIC_META[metric] ?? { label: metric, unit: "", decimals: 1 };
}

function formatMetricValue(metric, value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return "-";
  }

  return Number(value).toFixed(predictionMetricMeta(metric).decimals);
}

function formatNullablePace(value, suffix = "") {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return "-";
  }

  return `${Number(value).toFixed(1)}${suffix}`;
}

function formatDefenseRankLabel(opponentDefense) {
  if (!opponentDefense) {
    return null;
  }

  return [
    `${formatNullablePace(opponentDefense.totalYardsAllowedPerGame, " yds allowed/g")}`,
    `${formatNullablePace(opponentDefense.pointsAllowedPerGame, " pts allowed/g")}`,
    `${formatNullablePace(opponentDefense.turnoversForcedPerGame, " to forced/g")}`,
  ].join(" | ");
}

function getRecentMatchupLabel(summary) {
  const games = summary?.games ?? 0;
  if (games <= 0) {
    return "Recent matchups";
  }

  if (games === 1) {
    return "Last matchup";
  }

  if (games < 3) {
    return `Last ${games} matchups`;
  }

  return "Last 3 matchups";
}

function summarizeGame(stat, playerPosition) {
  const isQuarterback = (playerPosition ?? "").trim().toUpperCase() === "QB";
  const passingYards = stat.passingYards ?? 0;
  const rushingYards = stat.rushingYards ?? 0;
  const receivingYards = stat.receivingYards ?? 0;

  if (isQuarterback) {
    return [
      `Pass ${formatNumber(passingYards)} yds`,
      `Rush ${formatNumber(rushingYards)} yds`,
    ].join(" | ");
  }

  return [
    `Rec ${formatNumber(receivingYards)} yds`,
    `Rush ${formatNumber(rushingYards)} yds`,
  ].join(" | ");
}

function sleep(ms) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

function normalizeCandidateResults(results) {
  return results
    .filter((candidate) => (candidate.score ?? 0) >= SEARCH_RESULT_THRESHOLD)
    .sort((left, right) => (right.score ?? 0) - (left.score ?? 0))
    .slice(0, SEARCH_RESULT_LIMIT);
}

function AppShell({ children }) {
  return (
    <div className="page-shell">
      <div className="background background-left" />
      <div className="background background-right" />

      <header className="topbar">
        <Link className="brand-lockup" to="/">
          <span className="brand-mark">AGP</span>
          <div>
            <strong>AGP Bets</strong>
            <p>Player data and stat-line projections.</p>
          </div>
        </Link>

        <div className="topbar-actions">
          <nav className="topnav" aria-label="Primary">
            <Link to="/">Search</Link>
            <a href="#featured">Featured</a>
            <Link to="/faq">FAQ</Link>
            <a href="#login">Login</a>
          </nav>
        </div>
      </header>

      {children}
    </div>
  );
}

function SearchResultCard({ player }) {
  return (
    <Link className="result-card" to={`/players/${player.espnAthleteId}`} state={{ candidate: player }}>
      <div className="result-card-top">
        <div>
          <span className="card-label">Match</span>
          <h3>{player.displayName ?? "Unknown player"}</h3>
        </div>
        <span className="status-pill live">Open profile</span>
      </div>

      <p>
        {player.teamName ?? "Unknown team"} | {player.position ?? "Unknown position"}
      </p>
    </Link>
  );
}

function HomePage() {
  const [searchTerm, setSearchTerm] = useState("");
  const [searchState, setSearchState] = useState({
    loading: false,
    error: null,
    results: [],
  });
  const [selectedPreview, setSelectedPreview] = useState(featuredPlayers[0]);

  async function onSearch(event) {
    event.preventDefault();
    const query = searchTerm.trim();

    if (!query) {
      setSearchState((current) => ({
        ...current,
        error: "Enter a player name to search.",
        results: [],
      }));
      return;
    }

    setSearchState({
      loading: true,
      error: null,
      results: [],
    });

    try {
      const results = normalizeCandidateResults(await searchPlayers(query));
      setSearchState({
        loading: false,
        error: null,
        results,
      });

      if (results.length === 0) {
        setSearchState({
          loading: false,
          error: "No strong match found. Try checking the spelling or using a different name.",
          results: [],
        });
      }
    } catch (error) {
      setSearchState({
        loading: false,
        error: error.message,
        results: [],
      });
    }
  }

  return (
    <AppShell>
      <main className="content">
        <section className="hero panel panel-hero">
          <div className="hero-copy">
            <div className="eyebrow-row">
              <span className="eyebrow">NFL player intelligence</span>
            </div>
            <h1>Search players, explore their history, and build toward smarter betting views.</h1>
            <p className="lede">
              AGP Bets helps you search for players, review recent production, and explore
              performance trends that can power smarter betting decisions over time.
            </p>

            <form className="hero-search" onSubmit={onSearch}>
              <label className="search-field">
                <span>Find a player</span>
                <input
                  type="text"
                  value={searchTerm}
                  onChange={(event) => setSearchTerm(event.target.value)}
                  placeholder="Try Josh Allen, Jalen Hurts, Terry McLaurin..."
                  autoComplete="off"
                />
              </label>
              <button type="submit" disabled={searchState.loading}>
                {searchState.loading ? "Searching..." : "Search players"}
              </button>
            </form>

            {searchState.error ? <p className="inline-error">{searchState.error}</p> : null}

            <div className="search-results search-results-home">
              {searchState.results.map((player) => (
                <SearchResultCard key={player.espnAthleteId} player={player} />
              ))}
            </div>
          </div>

          <aside className="hero-aside" id="login">
            <div className="panel-card spotlight">
              <span className="card-label">Premier access</span>
              <h2>Login will eventually unlock deeper insights.</h2>
              <p>
                This area is reserved for premium access later, so the app can grow into a public
                and members-only experience without redesigning the whole front end.
              </p>
              <div className="login-form">
                <label>
                  Email
                  <input type="email" placeholder="name@example.com" disabled />
                </label>
                <label>
                  Password
                  <input type="password" placeholder="Coming soon" disabled />
                </label>
                <button type="button" className="secondary" disabled>
                  Sign in coming soon
                </button>
              </div>
              <ul className="bullet-list">
                <li>Premium insights will live here later.</li>
                <li>Public browsing stays simple and fast.</li>
              </ul>
            </div>

            <div className="panel-card preview-card">
              <span className="card-label">Featured spotlight</span>
              <h2>{selectedPreview.name}</h2>
              <p>
                {selectedPreview.team} | {selectedPreview.position}
              </p>
              <p className="preview-copy">{selectedPreview.blurb}</p>
              <div className="preview-notes">
                <span>{selectedPreview.label}</span>
                <strong>Featured on the board</strong>
              </div>
            </div>
          </aside>
        </section>

        <section className="panel section" id="featured">
          <div className="section-head">
            <div>
              <span className="section-kicker">Featured players</span>
              <h2>Players worth keeping an eye on</h2>
              <p>
                These cards give us a place to highlight interesting player situations as the app
                grows into a stronger betting experience.
              </p>
            </div>
          </div>

          <div className="featured-grid">
            {featuredPlayers.map((player) => (
              <button
                key={player.name}
                type="button"
                className="featured-card"
                onClick={() => setSelectedPreview(player)}
              >
                <div className="featured-card-head">
                  <strong>{player.name}</strong>
                  <span>{player.label}</span>
                </div>
                <p>
                  {player.team} | {player.position}
                </p>
                <small>{player.blurb}</small>
              </button>
            ))}
          </div>
        </section>
      </main>
    </AppShell>
  );
}

function SummaryCard({ label, value, detail }) {
  return (
    <article className="insight-card">
      <span className="summary-label">{label}</span>
      <strong>{value}</strong>
      <p>{detail}</p>
    </article>
  );
}

function formatSplitLabel(value) {
  if (!value) {
    return "Unknown split";
  }

  if (value.toLowerCase() === "home") {
    return "Home games";
  }

  if (value.toLowerCase() === "away") {
    return "Away games";
  }

  return value;
}

function SplitCard({ label, games, yardsPerGame, touchdownsPerGame, turnoversPerGame }) {
  return (
    <article className="summary-card split-card">
      <span className="summary-label">{label}</span>
      <strong>{formatNumber(games)} games</strong>
      <p>
        {formatPace(yardsPerGame)} yds/g | {formatPace(touchdownsPerGame)} td/g |{" "}
        {formatPace(turnoversPerGame)} to/g
      </p>
    </article>
  );
}

function UpcomingOpponentCard({ upcomingOpponent, opponentTeam, opponentDefense, playerPosition }) {
  const isQuarterback = (playerPosition ?? "").trim().toUpperCase() === "QB";
  const yardsLabel = isQuarterback ? "Pass" : "Rec";
  const lastThreeYardsPerGame = isQuarterback
    ? upcomingOpponent?.lastThreeSummary?.passingYardsPerGame
    : upcomingOpponent?.lastThreeSummary?.receivingYardsPerGame;
  const allTimeYardsPerGame = isQuarterback
    ? upcomingOpponent?.allTimeSummary?.passingYardsPerGame
    : upcomingOpponent?.allTimeSummary?.receivingYardsPerGame;

  if (!upcomingOpponent) {
    return (
      <article className="summary-card split-card">
        <span className="summary-label">Upcoming opponent</span>
        <strong>Matchup details unavailable</strong>
        <p>Can not load player matchup details at this time.</p>
      </article>
    );
  }

  return (
    <article className="summary-card split-card upcoming-opponent-card">
      <span className="summary-label">Upcoming opponent</span>
      <div className="opponent-card-head">
        {opponentTeam?.logoUrl ? (
          <img
            src={opponentTeam.logoUrl}
            alt={upcomingOpponent.opponentName ? `${upcomingOpponent.opponentName} logo` : "Team logo"}
            className="team-logo team-logo-opponent"
          />
        ) : null}
        <div>
          <strong>{upcomingOpponent.opponentName}</strong>
          <p>{formatLongDate(upcomingOpponent.gameDate)}</p>
        </div>
      </div>

      <div className="opponent-card-grid">
        <section className="opponent-card-box">
          <span className="summary-label">{getRecentMatchupLabel(upcomingOpponent.lastThreeSummary)}</span>
          <strong>{formatNumber(upcomingOpponent.lastThreeSummary?.games ?? 0)} games</strong>
          <p>
            {yardsLabel} {formatPace(lastThreeYardsPerGame)} | Rush{" "}
            {formatPace(upcomingOpponent.lastThreeSummary?.rushingYardsPerGame)} | TD{" "}
            {formatPace(upcomingOpponent.lastThreeSummary?.totalTouchdownsPerGame)}
          </p>
        </section>

        <section className="opponent-card-box">
          <span className="summary-label">All time</span>
          <strong>{formatNumber(upcomingOpponent.allTimeSummary?.games ?? 0)} games</strong>
          <p>
            {yardsLabel} {formatPace(allTimeYardsPerGame)} | Rush{" "}
            {formatPace(upcomingOpponent.allTimeSummary?.rushingYardsPerGame)} | TD{" "}
            {formatPace(upcomingOpponent.allTimeSummary?.totalTouchdownsPerGame)}
          </p>
        </section>

        <section className="opponent-card-box opponent-defense-box">
          <span className="summary-label">Current defense</span>
          {opponentDefense ? (
            <>
              <strong>{formatNumber(opponentDefense.games)} game sample</strong>
              <p>{formatDefenseRankLabel(opponentDefense)}</p>
              <p>
                Pass {formatNullablePace(opponentDefense.passingYardsAllowedPerGame, " yds/g")} | Rush{" "}
                {formatNullablePace(opponentDefense.rushingYardsAllowedPerGame, " yds/g")} | Sacks{" "}
                {formatNullablePace(opponentDefense.sacksPerGame, "/g")}
              </p>
            </>
          ) : (
            <>
              <strong>Loading defense snapshot</strong>
              <p>Current defensive context will appear once that team history is loaded.</p>
            </>
          )}
        </section>
      </div>
    </article>
  );
}

function PredictionCard({ prediction }) {
  const meta = predictionMetricMeta(prediction.metric);
  const adjustment = Number(prediction.opponentAdjustment ?? 0);
  const matchupTone = adjustment > 0.05 ? "favorable" : adjustment < -0.05 ? "tough" : null;

  return (
    <article className="prediction-card">
      <span className="summary-label">{meta.label}</span>

      <div className="prediction-value">
        <strong>{formatMetricValue(prediction.metric, prediction.mean)}</strong>
        {meta.unit ? <span className="prediction-unit">{meta.unit}</span> : null}
      </div>

      <div className="prediction-meta">
        <span>{formatNumber(prediction.sampleSize)} games on record</span>
        {matchupTone ? (
          <span className={`matchup-pill matchup-${matchupTone}`}>
            {matchupTone === "favorable" ? "Favorable matchup" : "Tough matchup"}
          </span>
        ) : null}
      </div>

      {prediction.notes ? <p className="prediction-notes">{prediction.notes}</p> : null}
    </article>
  );
}

function PlayerDetailPage() {
  const { athleteId } = useParams();
  const location = useLocation();
  const [player, setPlayer] = useState(null);
  const [insights, setInsights] = useState(null);
  const [playerTeam, setPlayerTeam] = useState(null);
  const [opponentTeam, setOpponentTeam] = useState(null);
  const [opponentDefense, setOpponentDefense] = useState(null);
  const [predictions, setPredictions] = useState(null);
  const [predictionLoading, setPredictionLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [statsBackfilling, setStatsBackfilling] = useState(false);
  const [error, setError] = useState(null);
  const [headshotFailed, setHeadshotFailed] = useState(false);

  const headshotUrl = buildEspnHeadshotUrl(player?.espnAthleteId ?? athleteId);
  const isQuarterback = (player?.position ?? "").trim().toUpperCase() === "QB";
  const yardsPerGameLabel = isQuarterback ? "Passing and rushing yards per game" : "Yards per game";

  useEffect(() => {
    let canceled = false;

    async function loadPlayerPage() {
      setLoading(true);
      setError(null);
      setHeadshotFailed(false);
      setPlayerTeam(null);
      setOpponentTeam(null);
      setOpponentDefense(null);
      setPredictions(null);
      setPredictionLoading(true);
      setStatsBackfilling(false);

      try {
        let playerResponse;
        try {
          playerResponse = await getPlayer(athleteId);
        } catch (fetchError) {
          const shouldHydrate =
            location.state?.candidate ||
            fetchError?.status === 404 ||
            fetchError?.status === 500 ||
            String(fetchError.message ?? "").includes("No stored player found");

          if (!shouldHydrate) {
            throw fetchError;
          }

          playerResponse = await syncPlayerByAthleteId(athleteId);
        }

        if (!canceled) {
          setPlayer(playerResponse);
        }

        try {
          let syncResponse = await syncPlayerStats(athleteId);
          let pollAttempt = 0;

          // The backfill runs in the background and usually finishes in a few seconds - wait for
          // it here (still showing the page's existing loading state) instead of revealing
          // insights/predictions built from a handful of games while the rest of a player's
          // history is still landing. If it runs long, give up after BACKFILL_POLL_ATTEMPTS
          // rather than blocking the page indefinitely.
          if (syncResponse?.backfillInProgress && !canceled) {
            setStatsBackfilling(true);
          }

          while (syncResponse?.backfillInProgress && pollAttempt < BACKFILL_POLL_ATTEMPTS && !canceled) {
            await sleep(BACKFILL_POLL_DELAY_MS);
            if (canceled) {
              break;
            }
            syncResponse = await syncPlayerStats(athleteId).catch(() => null);
            pollAttempt += 1;
          }
        } catch {
          // Keep the player page usable even if stats are still catching up.
        } finally {
          if (!canceled) {
            setStatsBackfilling(false);
          }
        }

        let freshPlayer;
        let insightsResponse;
        for (let attempt = 0; attempt <= MATCHUP_RETRY_ATTEMPTS; attempt += 1) {
          [freshPlayer, insightsResponse] = await Promise.all([
            getPlayer(athleteId),
            getPlayerInsights(athleteId),
          ]);

          const hasMatchupContext =
            freshPlayer?.teamId &&
            freshPlayer?.teamName &&
            freshPlayer?.position &&
            insightsResponse?.upcomingOpponent;

          if (hasMatchupContext || attempt === MATCHUP_RETRY_ATTEMPTS) {
            break;
          }

          await sleep(MATCHUP_RETRY_DELAY_MS);
        }

        if (!canceled) {
          setPlayer(freshPlayer);
          setInsights(insightsResponse);
        }

        try {
          const predictionResponse = await getPlayerPredictions(athleteId);
          if (!canceled) {
            setPredictions(predictionResponse);
          }
        } catch {
          if (!canceled) {
            setPredictions(null);
          }
        } finally {
          if (!canceled) {
            setPredictionLoading(false);
          }
        }

        const teamRequests = [];
        if (freshPlayer?.teamId) {
          teamRequests.push(
            getTeam(freshPlayer.teamId)
              .then((teamResponse) => ({ kind: "playerTeam", value: teamResponse }))
              .catch(() => ({ kind: "playerTeam", value: null })),
          );
        }

        const opponentTeamId = insightsResponse?.upcomingOpponent?.opponentTeamId;
        if (opponentTeamId) {
          teamRequests.push(
            getTeam(opponentTeamId)
              .then((teamResponse) => ({ kind: "opponentTeam", value: teamResponse }))
              .catch(() => ({ kind: "opponentTeam", value: null })),
          );
          teamRequests.push(
            getTeamDefenseSummary(opponentTeamId)
              .then((summaryResponse) => ({ kind: "opponentDefense", value: summaryResponse }))
              .catch(() => ({ kind: "opponentDefense", value: null })),
          );
        }

        if (teamRequests.length > 0) {
          const responses = await Promise.all(teamRequests);
          if (!canceled) {
            for (const response of responses) {
              if (response.kind === "playerTeam") {
                setPlayerTeam(response.value);
              } else if (response.kind === "opponentTeam") {
                setOpponentTeam(response.value);
              } else if (response.kind === "opponentDefense") {
                setOpponentDefense(response.value);
              }
            }
          }
        }
      } catch {
        if (!canceled) {
          setError("Failed to load player data right now. Please try again.");
        }
      } finally {
        if (!canceled) {
          setLoading(false);
        }
      }
    }

    loadPlayerPage();

    return () => {
      canceled = true;
    };
  }, [athleteId, location.state]);

  return (
    <AppShell>
      <main className="content">
        <section className="panel section player-hero">
          <div className="player-hero-main">
            <Link className="back-link" to="/">
              Back to search
            </Link>
            <div className="eyebrow-row">
              <span className="eyebrow">Player detail</span>
            </div>

            <h1>{player?.displayName ?? "Loading player..."}</h1>
            <p className="lede">
              {player?.teamName ?? "Unknown team"} | {player?.position ?? "Unknown position"}
            </p>
            {location.state?.candidate ? (
              <p className="player-meta">Loaded from search and matched to this player profile.</p>
            ) : null}
          </div>

          <div className="player-portrait-card">
            <div className="player-avatar">
              {headshotUrl && !headshotFailed ? (
                <img
                  src={headshotUrl}
                  alt={player?.displayName ? `${player.displayName} headshot` : "Player headshot"}
                  className="player-headshot"
                  onError={() => setHeadshotFailed(true)}
                />
              ) : (
                <span>{getPlayerInitials(player?.displayName)}</span>
              )}
            </div>
            <div className="player-branding">
              <span className="card-label">Profile</span>
              {playerTeam?.logoUrl ? (
                <img
                  src={playerTeam.logoUrl}
                  alt={player?.teamName ? `${player.teamName} logo` : "Team logo"}
                  className="team-logo team-logo-profile"
                />
              ) : null}
              <h2>{player?.teamName ?? "Unknown team"}</h2>
              <p>{player?.position ?? "Unknown position"}</p>
            </div>
          </div>
        </section>

        {error ? <p className="inline-error">{error}</p> : null}

        {loading ? (
          <div className="empty-state">
            <div className="loading-row">
              <span className="loading-spinner" />
              <span>{statsBackfilling ? "Loading full stat history..." : "Loading player detail..."}</span>
            </div>
          </div>
        ) : null}

        {!loading && insights ? (
          <>
            <section className="panel section">
              <div className="section-head">
                <div>
                  <span className="section-kicker">Predictions</span>
                  <h2>Projected Stat Line</h2>
                </div>
                <Link className="faq-link" to="/faq">
                  How this works
                </Link>
              </div>

              {predictionLoading ? (
                <div className="empty-state">
                  <div className="loading-row">
                    <span className="loading-spinner" />
                    <span>Building the projection...</span>
                  </div>
                </div>
              ) : null}

              {!predictionLoading && predictions?.projections?.length ? (
                <div className="prediction-grid">
                  {predictions.projections.map((prediction) => (
                    <PredictionCard key={prediction.metric} prediction={prediction} />
                  ))}
                </div>
              ) : null}

              {!predictionLoading && !predictions?.projections?.length ? (
                <p className="candidate-meta">Prediction snapshot is unavailable right now.</p>
              ) : null}
            </section>

            <section className="panel section">
              <div className="section-head">
                <div>
                  <span className="section-kicker">Player snapshot</span>
                  <h2>Quick read on recent form</h2>
                </div>
              </div>

              <div className="insight-grid">
                <SummaryCard
                  label={yardsPerGameLabel}
                  value={
                    isQuarterback
                      ? `${formatPace(insights.overallSummary?.passingYardsPerGame)} pass | ${formatPace(
                          insights.overallSummary?.rushingYardsPerGame,
                        )} rush`
                      : formatPace(insights.overallSummary?.totalYardsPerGame)
                  }
                  detail={
                    isQuarterback
                      ? "Passing and rushing are shown separately for quarterbacks."
                      : "Combined passing, rushing, and receiving yards."
                  }
                />
                <SummaryCard
                  label="Touchdowns per game"
                  value={formatPace(insights.overallSummary?.totalTouchdownsPerGame)}
                  detail="Total touchdowns across all loaded games."
                />
                <SummaryCard
                  label="Turnovers per game"
                  value={formatPace(insights.overallSummary?.turnoversPerGame)}
                  detail="Interceptions plus fumbles lost."
                />
              </div>

              <div className="summary-grid summary-grid-detail">
                <article className="summary-card">
                  <span className="summary-label">Last 5</span>
                  <strong>{formatNumber(insights.lastFiveSummary?.games ?? 0)} games</strong>
                  <p>
                    {isQuarterback
                      ? `${formatPace(insights.lastFiveSummary?.passingYardsPerGame)} pass | ${formatPace(
                          insights.lastFiveSummary?.rushingYardsPerGame,
                        )} rush`
                      : `${formatPace(insights.lastFiveSummary?.totalYardsPerGame)} yards per game`}
                  </p>
                </article>
                <article className="summary-card">
                  <span className="summary-label">Last 3</span>
                  <strong>{formatNumber(insights.lastThreeSummary?.games ?? 0)} games</strong>
                  <p>
                    {isQuarterback
                      ? `${formatPace(insights.lastThreeSummary?.passingYardsPerGame)} pass | ${formatPace(
                          insights.lastThreeSummary?.rushingYardsPerGame,
                        )} rush`
                      : `${formatPace(insights.lastThreeSummary?.totalYardsPerGame)} yards per game`}
                  </p>
                </article>
                <article className="summary-card">
                  <span className="summary-label">Career sample</span>
                  <strong>{formatNumber(insights.overallSummary?.games ?? 0)} games</strong>
                  <p>
                    {isQuarterback
                      ? `${formatNumber(insights.overallSummary?.passingYardsTotal ?? 0)} pass yards | ${formatNumber(
                          insights.overallSummary?.rushingYardsTotal ?? 0,
                        )} rush yards`
                      : `${formatNumber(insights.overallSummary?.totalYardsTotal ?? 0)} total yards`}
                  </p>
                </article>
                <article className="summary-card">
                  <span className="summary-label">Scoring</span>
                  <strong>{formatNumber(insights.overallSummary?.totalTouchdownsTotal ?? 0)} TDs</strong>
                  <p>
                    {isQuarterback
                      ? `${formatNumber(insights.overallSummary?.passingTouchdownsTotal ?? 0)} pass TDs | ${formatNumber(
                          insights.overallSummary?.rushingTouchdownsTotal ?? 0,
                        )} rush TDs`
                      : `${formatNumber(insights.overallSummary?.touchdownsTotal ?? 0)} total scored plays`}
                  </p>
                </article>
              </div>

              <p className="section-note">Last refreshed {formatDate(insights.generatedAt)}</p>
            </section>

            <section className="panel section">
              <div className="section-head">
                <div>
                  <span className="section-kicker">Game log</span>
                  <h2>Recent game-by-game production</h2>
                </div>
              </div>

              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Opponent</th>
                      <th>Home/Away</th>
                      <th>Production</th>
                      <th>TDs</th>
                      <th>Turnovers</th>
                    </tr>
                  </thead>
                  <tbody>
                    {insights.recentGames?.length ? (
                      insights.recentGames.map((stat) => (
                        <tr key={stat.id}>
                          <td>
                            <strong>{formatShortDate(stat.gameDate)}</strong>
                            <div className="subtle">
                              Season {stat.season ?? "-"} Week {stat.week ?? "-"}
                            </div>
                          </td>
                          <td>{stat.opponentName ?? "Unknown"}</td>
                          <td>{stat.homeAway ?? "Unknown"}</td>
                          <td>{summarizeGame(stat, player?.position)}</td>
                          <td>{formatNumber(stat.totalTouchdowns ?? stat.touchdowns ?? 0)}</td>
                          <td>{formatNumber(stat.turnovers ?? 0)}</td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan="6" className="candidate-meta">
                          No game stats loaded for this player yet.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </section>

            <section className="panel section">
              <div className="section-head">
                <div>
                  <span className="section-kicker">Derived splits</span>
                  <h2>Home, away, and opponent samples</h2>
                </div>
              </div>

              <div className="split-section">
                <div>
                  <h3 className="split-heading">Upcoming matchup</h3>
                  <div className="summary-grid split-grid split-grid-single">
                    <UpcomingOpponentCard
                      upcomingOpponent={insights.upcomingOpponent}
                      opponentTeam={opponentTeam}
                      opponentDefense={opponentDefense}
                      playerPosition={player?.position}
                    />
                  </div>
                </div>

                <div>
                  <h3 className="split-heading">Home and away</h3>
                  <div className="summary-grid split-grid">
                    {insights.homeAwaySplits?.length ? (
                      insights.homeAwaySplits.map((split) => (
                        <SplitCard
                          key={`${split.splitType}-${split.splitValue}`}
                          label={formatSplitLabel(split.splitValue)}
                          games={split.summary?.games ?? 0}
                          yardsPerGame={split.summary?.totalYardsPerGame}
                          touchdownsPerGame={split.summary?.totalTouchdownsPerGame}
                          turnoversPerGame={split.summary?.turnoversPerGame}
                        />
                      ))
                    ) : (
                      <p className="candidate-meta">No home/away split data is available yet.</p>
                    )}
                  </div>
                </div>

                <div>
                  <h3 className="split-heading">Top opponents</h3>
                  <div className="summary-grid split-grid">
                    {insights.opponentSplits?.length ? (
                      insights.opponentSplits.map((split) => (
                        <SplitCard
                          key={`${split.splitType}-${split.splitValue}`}
                          label={split.splitValue}
                          games={split.summary?.games ?? 0}
                          yardsPerGame={split.summary?.totalYardsPerGame}
                          touchdownsPerGame={split.summary?.totalTouchdownsPerGame}
                          turnoversPerGame={split.summary?.turnoversPerGame}
                        />
                      ))
                    ) : (
                      <p className="candidate-meta">No opponent split data is available yet.</p>
                    )}
                  </div>
                </div>
              </div>
            </section>
          </>
        ) : null}
      </main>
    </AppShell>
  );
}

const FAQ_ENTRIES = [
  {
    question: "How is the projected stat line calculated?",
    answer:
      "Each number blends the player's last 5 games with their season average (weighted 65/35 toward recent form), then adjusts for how the upcoming opponent's defense has played compared to the rest of the league this season.",
  },
  {
    question: "Why don't you show a confidence range or score?",
    answer:
      "We used to show a range and a confidence badge next to every number, but a hedge isn't useful for a betting decision - you want a number to act on, not a spread of possibilities. We still compute both internally; they're just not on the page right now.",
  },
  {
    question: "Where does the data come from?",
    answer:
      "Game logs and team defensive stats come from nflverse, a maintained public NFL dataset - not scraped from a live site, so it's stable and consistent across seasons. Player identity, team branding, and headshots still come from ESPN, since that's a better fit for that kind of lookup data.",
  },
  {
    question: "How far back does the history go?",
    answer:
      "We backfill a player's full career, or at least the last 10 seasons, whichever is shorter for their actual career length. A rookie with one season on record will only show one season - there's nothing else to pull from yet.",
  },
  {
    question: "How often does this update?",
    answer:
      "Stats refresh automatically after games complete, checked against the schedule rather than a fixed day/time - so Thursday, Saturday, Sunday, and Monday games are all covered without a special case for any of them.",
  },
  {
    question: "What's not factored in yet?",
    answer:
      "Snap counts, drops, injury status, weather, and betting-market lines aren't part of the model yet. The opponent adjustment is also intentionally a small nudge, not the dominant factor, since it's not been backtested against real outcomes.",
  },
];

function FaqPage() {
  return (
    <AppShell>
      <main className="content">
        <section className="panel section">
          <Link className="back-link" to="/">
            Back to search
          </Link>
          <div className="section-head">
            <div>
              <span className="section-kicker">FAQ</span>
              <h2>How the predictions work</h2>
            </div>
          </div>

          <div className="faq-list">
            {FAQ_ENTRIES.map((entry) => (
              <article key={entry.question} className="faq-entry">
                <h3>{entry.question}</h3>
                <p>{entry.answer}</p>
              </article>
            ))}
          </div>
        </section>
      </main>
    </AppShell>
  );
}

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/players/:athleteId" element={<PlayerDetailPage />} />
        <Route path="/faq" element={<FaqPage />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;

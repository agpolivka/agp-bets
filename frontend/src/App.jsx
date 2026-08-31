import { useEffect, useState } from "react";
import { BrowserRouter, Link, Route, Routes, useLocation, useParams } from "react-router-dom";
import {
  getCurrentWeekPicks,
  getPlayer,
  getPlayerInsights,
  getPlayerLeaderboard,
  getPlayerPredictions,
  getTeam,
  getTeamDefenseSummary,
  getUpcomingTeamMatchups,
  searchPlayers,
  submitPicks,
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

const CONCERNING_AVAILABILITY_STATUSES = new Set([
  "out",
  "injured reserve",
  "ir",
  "physically unable to perform",
  "pup",
  "suspended",
  "did not report",
]);

function resolveAvailability(injuryStatus) {
  if (!injuryStatus || injuryStatus.trim().toLowerCase() === "active") {
    return { tier: "active", label: "Active", blurb: null };
  }

  const normalized = injuryStatus.trim().toLowerCase();
  const tier = CONCERNING_AVAILABILITY_STATUSES.has(normalized) ? "out" : "caution";
  const blurb =
    tier === "out"
      ? `Listed as ${injuryStatus} - not expected to play this week.`
      : `Listed as ${injuryStatus} - availability for this week isn't guaranteed.`;

  return { tier, label: injuryStatus, blurb };
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

// Groups positions the same way the backend's SKILL_POSITIONS/metricsForPosition does (see
// PlayerPredictionService), so the top summary cards ask about the stats a position actually
// posts instead of a generic yards/TDs/turnovers row for everyone - a QB's turnover rate and a
// WR's target share aren't comparable numbers, so they shouldn't share one card layout.
const POSITION_SUMMARY_GROUP = {
  QB: "QB",
  RB: "RB",
  WR: "RECEIVER",
  TE: "RECEIVER",
  FB: "RECEIVER",
};

function getPositionSummaryGroup(position) {
  return POSITION_SUMMARY_GROUP[(position ?? "").trim().toUpperCase()] ?? "GENERIC";
}

// Builds the 3-4 hero summary cards for a given stat window (overallSummary/lastFiveSummary/
// etc. from PlayerInsightsResponse) - every field referenced here already exists on
// PlayerStatInsightSummary, so this is purely a frontend reshuffle, no backend change needed.
function buildPositionSummaryCards(position, summary) {
  if (!summary) {
    return [];
  }

  const group = getPositionSummaryGroup(position);

  if (group === "QB") {
    return [
      {
        label: "Passing yards/g",
        value: formatPace(summary.passingYardsPerGame),
        detail: `${formatNumber(summary.passingYardsTotal ?? 0)} passing yards across ${formatNumber(summary.games ?? 0)} games.`,
      },
      {
        label: "Passing TDs/g",
        value: formatPace(summary.passingTouchdownsPerGame),
        detail: `${formatNumber(summary.passingTouchdownsTotal ?? 0)} passing touchdowns.`,
      },
      {
        label: "Rushing yards/g",
        value: formatPace(summary.rushingYardsPerGame),
        detail: `${formatNumber(summary.rushingYardsTotal ?? 0)} rushing yards.`,
      },
      {
        label: "Turnovers/g",
        value: formatPace(summary.turnoversPerGame),
        detail: `${formatNumber(summary.interceptionsTotal ?? 0)} interceptions, ${formatNumber(summary.fumblesLostTotal ?? 0)} lost fumbles.`,
      },
    ];
  }

  if (group === "RB") {
    return [
      {
        label: "Rushing yards/g",
        value: formatPace(summary.rushingYardsPerGame),
        detail: `${formatPace(summary.carriesPerGame)} carries/g, ${formatNumber(summary.rushingYardsTotal ?? 0)} yards total.`,
      },
      {
        label: "Rushing TDs/g",
        value: formatPace(summary.rushingTouchdownsPerGame),
        detail: `${formatNumber(summary.rushingTouchdownsTotal ?? 0)} rushing touchdowns.`,
      },
      {
        label: "Receiving yards/g",
        value: formatPace(summary.receivingYardsPerGame),
        detail: `${formatPace(summary.receptionsPerGame)} receptions on ${formatPace(summary.receivingTargetsPerGame)} targets/g.`,
      },
      {
        label: "Turnovers/g",
        value: formatPace(summary.turnoversPerGame),
        detail: `${formatNumber(summary.fumblesLostTotal ?? 0)} lost fumbles.`,
      },
    ];
  }

  if (group === "RECEIVER") {
    const targetsPerGame = summary.receivingTargetsPerGame ?? 0;
    const receptionsPerGame = summary.receptionsPerGame ?? 0;
    const catchRateDetail =
      targetsPerGame > 0
        ? `${Math.round((receptionsPerGame / targetsPerGame) * 100)}% catch rate.`
        : "No target data on record yet.";

    return [
      {
        label: "Targets/g",
        value: formatPace(summary.receivingTargetsPerGame),
        detail: "Opportunity - how often the offense looks their way.",
      },
      {
        label: "Receptions/g",
        value: formatPace(summary.receptionsPerGame),
        detail: catchRateDetail,
      },
      {
        label: "Receiving yards/g",
        value: formatPace(summary.receivingYardsPerGame),
        detail: `${formatNumber(summary.receivingYardsTotal ?? 0)} receiving yards total.`,
      },
      {
        label: "Receiving TDs/g",
        value: formatPace(summary.receivingTouchdownsPerGame),
        detail: `${formatNumber(summary.receivingTouchdownsTotal ?? 0)} receiving touchdowns.`,
      },
    ];
  }

  // GENERIC fallback for an unknown/unrecognized position - same shape the page always showed
  // before position-aware cards existed.
  return [
    {
      label: "Yards per game",
      value: formatPace(summary.totalYardsPerGame),
      detail: "Combined passing, rushing, and receiving yards.",
    },
    {
      label: "Touchdowns per game",
      value: formatPace(summary.totalTouchdownsPerGame),
      detail: "Total touchdowns across all loaded games.",
    },
    {
      label: "Turnovers per game",
      value: formatPace(summary.turnoversPerGame),
      detail: "Interceptions plus fumbles lost.",
    },
  ];
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
            <Link to="/matchups">Matchups</Link>
            <Link to="/picks">My Picks</Link>
            <Link to="/faq">FAQ</Link>
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

// Shapes one entry of the real leaderboard (see PlayerLeaderboardService - the QB actually
// projected to throw for the most yards, the RB projected to rush for the most, the WR projected
// to receive for the most, computed from every real active candidate's live prediction, not a
// curated pick) into what the featured-player cards show.
function buildFeaturedPlayer(topPlayer) {
  if (!topPlayer) {
    return null;
  }

  return {
    athleteId: topPlayer.athleteId,
    displayName: topPlayer.displayName ?? "Unknown player",
    teamName: topPlayer.teamName ?? "Unknown team",
    position: topPlayer.position ?? "Unknown position",
    headline: {
      label: predictionMetricMeta(topPlayer.metric).label,
      value: formatMetricValue(topPlayer.metric, topPlayer.value),
      unit: predictionMetricMeta(topPlayer.metric).unit,
    },
  };
}

function HomePage() {
  const [searchTerm, setSearchTerm] = useState("");
  const [searchState, setSearchState] = useState({
    loading: false,
    error: null,
    results: [],
  });
  const [featuredState, setFeaturedState] = useState({ loading: true, error: null, players: [] });
  const [selectedAthleteId, setSelectedAthleteId] = useState(null);

  useEffect(() => {
    let cancelled = false;

    getPlayerLeaderboard()
      .then((leaderboard) => {
        if (cancelled) {
          return;
        }

        // A position with no real candidate yet (e.g. a fresh database) is skipped rather than
        // breaking the whole section - same graceful-degradation shape used throughout this app.
        const players = [leaderboard.topQuarterback, leaderboard.topRusher, leaderboard.topReceiver]
          .map(buildFeaturedPlayer)
          .filter(Boolean);

        setFeaturedState({
          loading: false,
          error: players.length === 0 ? "Featured players are temporarily unavailable." : null,
          players,
        });
        setSelectedAthleteId((current) => current ?? players[0]?.athleteId ?? null);
      })
      .catch((error) => {
        if (cancelled) {
          return;
        }
        setFeaturedState({ loading: false, error: error.message, players: [] });
      });

    return () => {
      cancelled = true;
    };
  }, []);

  const selectedPreview = featuredState.players.find((player) => player.athleteId === selectedAthleteId) ?? null;

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

          <aside className="hero-aside">
            <div className="panel-card preview-card">
              <span className="card-label">Featured spotlight</span>
              {featuredState.loading ? (
                <div className="loading-row">
                  <span className="loading-spinner" />
                  <span>Loading live projection...</span>
                </div>
              ) : null}
              {!featuredState.loading && selectedPreview ? (
                <>
                  <h2>{selectedPreview.displayName}</h2>
                  <p>
                    {selectedPreview.teamName} | {selectedPreview.position}
                  </p>
                  <p className="preview-copy">
                    {selectedPreview.headline
                      ? `Projected ${selectedPreview.headline.value} ${selectedPreview.headline.label.toLowerCase()} this week.`
                      : "No live projection available yet for this player."}
                  </p>
                  <div className="preview-notes">
                    <span>Real projection, updated live</span>
                    <Link to={`/players/${selectedPreview.athleteId}`}>View full breakdown</Link>
                  </div>
                </>
              ) : null}
              {!featuredState.loading && !selectedPreview ? (
                <p className="empty-state">{featuredState.error}</p>
              ) : null}
            </div>
          </aside>
        </section>

        <section className="panel section" id="featured">
          <div className="section-head">
            <div>
              <span className="section-kicker">Featured players</span>
              <h2>This week's real top projections</h2>
              <p>
                The QB projected to throw for the most yards, the RB projected to rush for the
                most, and the WR projected to receive for the most - computed live from every
                real active player's own projection, not a fixed pick.
              </p>
            </div>
          </div>

          {featuredState.loading ? (
            <div className="empty-state">
              <div className="loading-row">
                <span className="loading-spinner" />
                <span>Loading featured players...</span>
              </div>
            </div>
          ) : null}

          {!featuredState.loading && featuredState.players.length === 0 ? (
            <p className="empty-state">{featuredState.error}</p>
          ) : null}

          {!featuredState.loading && featuredState.players.length > 0 ? (
            <div className="featured-grid">
              {featuredState.players.map((player) => (
                <button
                  key={player.athleteId}
                  type="button"
                  className="featured-card"
                  onClick={() => setSelectedAthleteId(player.athleteId)}
                >
                  <div className="featured-card-head">
                    <strong>{player.displayName}</strong>
                    <span>{player.headline ? `${player.headline.value} ${player.headline.unit}` : "No projection"}</span>
                  </div>
                  <p>
                    {player.teamName} | {player.position}
                  </p>
                  <small>
                    {player.headline
                      ? `Projected ${player.headline.value} ${player.headline.unit} (${player.headline.label}) this week.`
                      : "No live projection available yet."}
                  </small>
                </button>
              ))}
            </div>
          ) : null}
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
  // Sum every adjustment term the backend computes, not just opponentAdjustment - a player whose
  // weather/PFR/NGS/WOPR nudges are the dominant signal for a stat used to show no pill at all
  // even when the projection moved meaningfully (e.g. targetShareAdjustment alone can be +20
  // yards for a heavily-featured receiver).
  const adjustment =
    Number(prediction.opponentAdjustment ?? 0) +
    Number(prediction.conditionsAdjustment ?? 0) +
    Number(prediction.rushingQualityAdjustment ?? 0) +
    Number(prediction.advancedMetricAdjustment ?? 0) +
    Number(prediction.targetShareAdjustment ?? 0) +
    Number(prediction.usageAdjustment ?? 0);
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
  // Prefer the weekly Questionable/Doubtful/Out game-status designation over the roster-level
  // injuryStatus (Active/Injured Reserve/etc.) when both exist - it's the more specific, more
  // game-relevant signal (a player can show "Active" via injuryStatus while genuinely ruled out
  // for this week's game - see WORKPLAN.md). Falls back to injuryStatus so IR/PUP/suspended
  // players without a current weekly report still show something.
  const availability = resolveAvailability(player?.gameStatus ?? player?.injuryStatus);

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
            {player ? (
              <div className="availability-row">
                <span className={`availability-pill availability-${availability.tier}`}>
                  {availability.label}
                </span>
                {availability.blurb ? <p className="player-meta">{availability.blurb}</p> : null}
              </div>
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
                {buildPositionSummaryCards(player?.position, insights.overallSummary).map((card) => (
                  <SummaryCard key={card.label} label={card.label} value={card.value} detail={card.detail} />
                ))}
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
      "Each number starts as a blend of the player's last 5 games and their season average (weighted 65/35 toward recent form), then gets nudged by several factors: how the upcoming opponent's defense has played compared to the rest of the league, weather and the Vegas game total, the player's own recent role (target share, PFR/Next Gen advanced metrics), and a weekly Questionable/Doubtful/Out designation if one exists. Every nudge is a small, explainable adjustment on top of that blend, not a separate model.",
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
      "Real betting-market lines aren't factored in yet - that's the single biggest gap, since it means we can't yet measure whether a projection actually beats what a sportsbook posts. Weather, the Vegas game total, weekly injury/game-status designations, target share, and opponent defensive tendencies (including pass rush and missed-tackle rate) are all factored in. Offensive snap share is tracked but deliberately left out of the live projection for now - testing it against real outcomes didn't show a clear improvement.",
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

const PLAYOFF_ROUND_LABELS = {
  WC: "Wild Card",
  DIV: "Divisional Round",
  CON: "Conference Championship",
  SB: "Super Bowl",
};

function matchupGroupLabel(matchup) {
  if (matchup.gameType && matchup.gameType !== "REG") {
    return PLAYOFF_ROUND_LABELS[matchup.gameType] ?? matchup.gameType;
  }
  return `Week ${matchup.week}`;
}

function groupMatchupsByWeek(matchups) {
  const groups = new Map();
  for (const matchup of matchups) {
    const label = matchupGroupLabel(matchup);
    if (!groups.has(label)) {
      groups.set(label, []);
    }
    groups.get(label).push(matchup);
  }
  return Array.from(groups.entries());
}

function TeamMatchupCard({ matchup }) {
  const homeIsPick = !matchup.predictedTie && matchup.predictedWinnerAbbreviation === matchup.homeTeamAbbreviation;
  const confidence = Math.round((homeIsPick ? matchup.homeWinProbability : 1 - matchup.homeWinProbability) * 100);

  return (
    <article className="team-matchup-card">
      <div className="team-matchup-teams">
        <span className={`team-matchup-team${homeIsPick ? "" : " team-matchup-team-picked"}`}>
          {matchup.awayTeamName}
        </span>
        <span className="team-matchup-at">@</span>
        <span className={`team-matchup-team${homeIsPick ? " team-matchup-team-picked" : ""}`}>
          {matchup.homeTeamName}
        </span>
      </div>
      <div className="team-matchup-score">
        {matchup.predictedAwayScore} - {matchup.predictedHomeScore}
      </div>
      <div className="team-matchup-meta">
        {matchup.predictedTie ? (
          <span className="team-matchup-pick">Predicted tie</span>
        ) : (
          <>
            <span className="team-matchup-pick">
              Pick: {homeIsPick ? matchup.homeTeamAbbreviation : matchup.awayTeamAbbreviation}
            </span>
            <span className="subtle">{confidence}% confidence</span>
          </>
        )}
      </div>
    </article>
  );
}

function MatchupsPage() {
  const [matchups, setMatchups] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let canceled = false;
    setLoading(true);
    setError(null);

    getUpcomingTeamMatchups()
      .then((data) => {
        if (!canceled) {
          setMatchups(data ?? []);
        }
      })
      .catch((fetchError) => {
        if (!canceled) {
          setError(fetchError.message ?? "Could not load matchup predictions.");
        }
      })
      .finally(() => {
        if (!canceled) {
          setLoading(false);
        }
      });

    return () => {
      canceled = true;
    };
  }, []);

  const weeks = groupMatchupsByWeek(matchups);

  return (
    <AppShell>
      <main className="content">
        <section className="panel section">
          <Link className="back-link" to="/">
            Back to search
          </Link>
          <div className="section-head">
            <div>
              <span className="section-kicker">Team matchups</span>
              <h2>Who we're picking to win</h2>
            </div>
          </div>

          {loading ? <p className="subtle">Loading matchup predictions...</p> : null}
          {error ? <p className="inline-error">{error}</p> : null}
          {!loading && !error && weeks.length === 0 ? (
            <p className="empty-state">No upcoming matchups on file yet.</p>
          ) : null}

          {weeks.map(([weekLabel, weekMatchups]) => (
            <div key={weekLabel} className="team-matchups-week">
              <h3 className="team-matchups-week-heading">{weekLabel}</h3>
              <div className="team-matchups-grid">
                {weekMatchups.map((matchup) => (
                  <TeamMatchupCard key={matchup.gameId} matchup={matchup} />
                ))}
              </div>
            </div>
          ))}
        </section>
      </main>
    </AppShell>
  );
}

function PicksAccuracyBanner({ accuracy }) {
  const hasGraded = accuracy && accuracy.gradedPicks > 0;
  return (
    <div className="picks-accuracy-banner">
      <span className="card-label">Your record</span>
      <strong>{hasGraded ? `${accuracy.correctPicks}-${accuracy.gradedPicks - accuracy.correctPicks}` : "N/A"}</strong>
      <span className="subtle">
        {hasGraded ? `${accuracy.accuracyPct.toFixed(1)}% straight up (${accuracy.gradedPicks} graded picks)` : "No graded picks yet"}
      </span>
    </div>
  );
}

function PickGameCard({ game, selectedTeam, onSelect, modelPick }) {
  const isDecided = game.homeScore !== null && game.awayScore !== null;
  // locked (real kickoff has passed) can be true before isDecided (a final score can take a while
  // to sync in) - buttons disable at kickoff either way, not only once a score exists.
  const isLocked = game.locked || isDecided;
  const modelPickedTeam =
    modelPick && !modelPick.isTie ? (modelPick.isHomePick ? game.homeTeamAbbreviation : game.awayTeamAbbreviation) : null;
  const agreesWithModel = selectedTeam && modelPickedTeam && selectedTeam === modelPickedTeam;
  const disagreesWithModel = selectedTeam && modelPickedTeam && selectedTeam !== modelPickedTeam;

  function teamButtonClass(teamAbbreviation) {
    const isSelected = selectedTeam === teamAbbreviation;
    if (!isLocked) {
      return `pick-team-button${isSelected ? " pick-team-button-selected" : ""}`;
    }
    if (!isSelected) {
      return "pick-team-button pick-team-button-decided";
    }
    if (!isDecided || game.correct === null) {
      return "pick-team-button pick-team-button-selected pick-team-button-decided";
    }
    return `pick-team-button pick-team-button-selected pick-team-button-decided ${
      game.correct ? "pick-team-button-correct" : "pick-team-button-incorrect"
    }`;
  }

  function teamButtonTag(teamAbbreviation) {
    return modelPickedTeam === teamAbbreviation ? <span className="pick-model-tag">Model</span> : null;
  }

  return (
    <article className="pick-game-card">
      <div className="pick-game-date">
        {formatShortDate(game.gameday)}
        {isLocked && !isDecided ? <span className="pick-locked-tag">Locked</span> : null}
      </div>
      <div className="pick-game-teams">
        <button
          type="button"
          className={teamButtonClass(game.awayTeamAbbreviation)}
          disabled={isLocked}
          onClick={() => onSelect(game.gameId, game.awayTeamAbbreviation)}
        >
          {game.awayTeamLogoUrl ? <img src={game.awayTeamLogoUrl} alt="" className="team-logo pick-team-logo" /> : null}
          <span>{game.awayTeamName}</span>
          {teamButtonTag(game.awayTeamAbbreviation)}
          {isDecided ? <strong>{game.awayScore}</strong> : null}
        </button>
        <span className="team-matchup-at">@</span>
        <button
          type="button"
          className={teamButtonClass(game.homeTeamAbbreviation)}
          disabled={isLocked}
          onClick={() => onSelect(game.gameId, game.homeTeamAbbreviation)}
        >
          {game.homeTeamLogoUrl ? <img src={game.homeTeamLogoUrl} alt="" className="team-logo pick-team-logo" /> : null}
          <span>{game.homeTeamName}</span>
          {teamButtonTag(game.homeTeamAbbreviation)}
          {isDecided ? <strong>{game.homeScore}</strong> : null}
        </button>
      </div>
      {isDecided && game.correct !== null ? (
        <span className={`pick-result-tag ${game.correct ? "pick-result-correct" : "pick-result-incorrect"}`}>
          {game.correct ? "Correct" : "Missed"}
        </span>
      ) : null}
      {modelPick && modelPick.isTie ? <div className="pick-model-line">Model: predicted tie</div> : null}
      {modelPick && !modelPick.isTie && agreesWithModel ? <div className="pick-model-line pick-model-line-agrees">Model agrees</div> : null}
      {modelPick && !modelPick.isTie && disagreesWithModel ? (
        <div className="pick-model-line pick-model-line-disagrees">Model disagrees</div>
      ) : null}
    </article>
  );
}

function PicksPage() {
  const [state, setState] = useState({ loading: true, error: null, season: null, gameType: null, week: null, games: [], accuracy: null });
  const [selections, setSelections] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [submitMessage, setSubmitMessage] = useState(null);
  const [modelPicksByGameId, setModelPicksByGameId] = useState({});

  function applyResponse(data) {
    setState({
      loading: false,
      error: null,
      season: data.season,
      gameType: data.gameType,
      week: data.week,
      games: data.games ?? [],
      accuracy: data.accuracy,
    });
    const existing = {};
    for (const game of data.games ?? []) {
      if (game.pickedTeamAbbreviation) {
        existing[game.gameId] = game.pickedTeamAbbreviation;
      }
    }
    setSelections(existing);
  }

  useEffect(() => {
    let canceled = false;
    getCurrentWeekPicks()
      .then((data) => {
        if (!canceled) {
          applyResponse(data);
        }
      })
      .catch((error) => {
        if (!canceled) {
          setState((current) => ({ ...current, loading: false, error: error.message ?? "Could not load this week's games." }));
        }
      });

    // Fetched independently, deliberately not blocking the picks page on failure - the model's
    // pick is a "nice to have" comparison here, not something this feature depends on (see
    // UserPick's own doc: this whole page is intentionally independent of the prediction model).
    getUpcomingTeamMatchups()
      .then((matchups) => {
        if (canceled) {
          return;
        }
        const byGameId = {};
        for (const matchup of matchups ?? []) {
          byGameId[matchup.gameId] = matchup.predictedTie
            ? { isTie: true }
            : { isTie: false, isHomePick: matchup.predictedWinnerAbbreviation === matchup.homeTeamAbbreviation };
        }
        setModelPicksByGameId(byGameId);
      })
      .catch(() => {
        // Silently leave modelPicksByGameId empty - PickGameCard already treats a missing entry as
        // "no model comparison available for this game" rather than an error state.
      });

    return () => {
      canceled = true;
    };
  }, []);

  function onSelect(gameId, teamAbbreviation) {
    setSelections((current) => ({ ...current, [gameId]: teamAbbreviation }));
    setSubmitMessage(null);
  }

  async function onSubmit() {
    const picks = Object.entries(selections).map(([gameId, pickedTeamAbbreviation]) => ({ gameId, pickedTeamAbbreviation }));
    if (picks.length === 0) {
      return;
    }

    setSubmitting(true);
    setSubmitMessage(null);
    try {
      const data = await submitPicks(picks);
      applyResponse(data);
      setSubmitMessage("Picks saved.");
    } catch (error) {
      setSubmitMessage(error.message ?? "Could not save picks.");
    } finally {
      setSubmitting(false);
    }
  }

  // "Remaining" means still pickable - a game whose kickoff has passed no longer counts even if a
  // final score hasn't synced in yet, matching PickGameCard's own lock logic.
  const pickableCount = state.games.filter((game) => !game.locked).length;
  const selectedPickableCount = state.games.filter((game) => !game.locked && selections[game.gameId]).length;

  return (
    <AppShell>
      <main className="content">
        <section className="panel section">
          <Link className="back-link" to="/">
            Back to search
          </Link>
          <div className="section-head">
            <div>
              <span className="section-kicker">My picks</span>
              <h2>{state.week ? `Week ${state.week}, ${state.season}` : "This week's games"}</h2>
              <p>Pick a winner for each game, then submit. Miss a week and it just won't count toward your record.</p>
            </div>
          </div>

          {state.accuracy ? <PicksAccuracyBanner accuracy={state.accuracy} /> : null}

          {state.loading ? (
            <div className="empty-state">
              <div className="loading-row">
                <span className="loading-spinner" />
                <span>Loading this week's games...</span>
              </div>
            </div>
          ) : null}
          {state.error ? <p className="inline-error">{state.error}</p> : null}
          {!state.loading && !state.error && state.games.length === 0 ? (
            <p className="empty-state">No games on file for an upcoming week yet.</p>
          ) : null}

          {!state.loading && state.games.length > 0 ? (
            <>
              <div className="picks-grid">
                {state.games.map((game) => (
                  <PickGameCard
                    key={game.gameId}
                    game={game}
                    selectedTeam={selections[game.gameId]}
                    onSelect={onSelect}
                    modelPick={modelPicksByGameId[game.gameId]}
                  />
                ))}
              </div>

              <div className="picks-submit-row">
                <span className="subtle">
                  {selectedPickableCount} of {pickableCount} remaining picks selected
                </span>
                <button type="button" onClick={onSubmit} disabled={submitting || Object.keys(selections).length === 0}>
                  {submitting ? "Saving..." : "Submit picks"}
                </button>
                {submitMessage ? <span className="subtle">{submitMessage}</span> : null}
              </div>
            </>
          ) : null}
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
        <Route path="/matchups" element={<MatchupsPage />} />
        <Route path="/picks" element={<PicksPage />} />
        <Route path="/faq" element={<FaqPage />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;

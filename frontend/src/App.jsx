import { useEffect, useMemo, useState } from "react";
import {
  BrowserRouter,
  Link,
  Route,
  Routes,
  useLocation,
  useNavigate,
  useParams,
} from "react-router-dom";
import { featuredPlayers } from "./data/featuredPlayers";
import {
  checkBackend,
  getPlayer,
  getPlayerInsights,
  searchPlayers,
  syncPlayerByAthleteId,
  syncPlayerStats,
} from "./lib/api";

const SEARCH_RESULT_LIMIT = 5;
const SEARCH_RESULT_THRESHOLD = 0.25;

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
    return "—";
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

function formatPace(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return "—";
  }

  return Number(value).toFixed(1);
}

function summarizeGame(stat) {
  const yards =
    (stat.passingYards ?? 0) + (stat.rushingYards ?? 0) + (stat.receivingYards ?? 0);

  return [
    `Yds ${formatNumber(yards)}`,
    `TD ${formatNumber(stat.totalTouchdowns ?? stat.touchdowns ?? 0)}`,
    `TO ${formatNumber(stat.turnovers ?? 0)}`,
  ].join(" · ");
}

function normalizeCandidateResults(results) {
  return results
    .filter((candidate) => (candidate.score ?? 0) >= SEARCH_RESULT_THRESHOLD)
    .sort((left, right) => (right.score ?? 0) - (left.score ?? 0))
    .slice(0, SEARCH_RESULT_LIMIT);
}

function StatusPill({ backendStatus }) {
  return (
    <span className={`status-pill ${backendStatus === "Backend online" ? "live" : "offline"}`}>
      {backendStatus}
    </span>
  );
}

function AppShell({ backendStatus, children }) {
  return (
    <div className="page-shell">
      <div className="background background-left" />
      <div className="background background-right" />

      <header className="topbar">
        <Link className="brand-lockup" to="/">
          <span className="brand-mark">AGP</span>
          <div>
            <strong>AGP Bets</strong>
            <p>Player data now. Prediction engine next.</p>
          </div>
        </Link>

        <div className="topbar-actions">
          <StatusPill backendStatus={backendStatus} />
          <nav className="topnav" aria-label="Primary">
            <Link to="/">Search</Link>
            <a href="#featured">Featured</a>
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
        {player.teamName ?? "Unknown team"} · {player.position ?? "Unknown position"}
      </p>

      <div className="result-meta">
        <span>{player.espnAthleteId ?? "No ESPN ID"}</span>
        <span>{player.stored ? "Stored already" : "Needs sync"}</span>
      </div>
    </Link>
  );
}

function HomePage({ backendStatus }) {
  const navigate = useNavigate();
  const [searchTerm, setSearchTerm] = useState("");
  const [searchState, setSearchState] = useState({
    loading: false,
    error: null,
    results: [],
  });
  const [selectedPreview, setSelectedPreview] = useState(featuredPlayers[0]);

  const heroMetrics = useMemo(
    () => [
      { label: "Player search", value: "ESPN-backed" },
      { label: "Stored history", value: "Postgres" },
      { label: "Future layer", value: "Prediction-ready" },
    ],
    [],
  );

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
    <AppShell backendStatus={backendStatus}>
      <main className="content">
        <section className="hero panel panel-hero">
          <div className="hero-copy">
            <div className="eyebrow-row">
              <span className="eyebrow">NFL player intelligence</span>
              <StatusPill backendStatus={backendStatus} />
            </div>
            <h1>Search players, explore their history, and build toward smarter betting views.</h1>
            <p className="lede">
              AGP Bets starts with search and stored player history, then grows into a player
              detail experience with insights, visuals, and projections built on top of the same
              backend data.
            </p>

            <div className="metric-row">
              {heroMetrics.map((metric) => (
                <div className="metric-card" key={metric.label}>
                  <span>{metric.label}</span>
                  <strong>{metric.value}</strong>
                </div>
              ))}
            </div>

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
                {selectedPreview.team} · {selectedPreview.position}
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
                These cards keep the landing page alive and give us a place to plug in live data
                as the backend grows more opinionated.
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
                  {player.team} · {player.position}
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

function PlayerDetailPage({ backendStatus }) {
  const navigate = useNavigate();
  const { athleteId } = useParams();
  const location = useLocation();
  const [player, setPlayer] = useState(null);
  const [insights, setInsights] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [statusMessage, setStatusMessage] = useState(null);
  const [headshotFailed, setHeadshotFailed] = useState(false);

  const headshotUrl = buildEspnHeadshotUrl(player?.espnAthleteId ?? athleteId);

  useEffect(() => {
    let canceled = false;

    async function loadPlayerPage() {
      setLoading(true);
      setError(null);
      setStatusMessage(null);
      setHeadshotFailed(false);

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

          setStatusMessage("Loading player data from ESPN...");
          playerResponse = await syncPlayerByAthleteId(athleteId);
        }

        if (!canceled) {
          setPlayer(playerResponse);
        }

        try {
          setStatusMessage("Syncing player stats...");
          await syncPlayerStats(athleteId);
        } catch {
          setStatusMessage("Player loaded. Stats are still catching up in the background.");
        }

        const [freshPlayer, insightsResponse] = await Promise.all([
          getPlayer(athleteId),
          getPlayerInsights(athleteId),
        ]);

        if (!canceled) {
          setPlayer(freshPlayer);
          setInsights(insightsResponse);
        }
      } catch (fetchError) {
        if (!canceled) {
          setError("Failed to load player data right now. Please try again.");
          setStatusMessage(null);
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
    <AppShell backendStatus={backendStatus}>
      <main className="content">
        <section className="panel section player-hero">
          <div className="player-hero-main">
            <button type="button" className="back-link" onClick={() => navigate("/")}>
              Back to search
            </button>
            <div className="eyebrow-row">
              <span className="eyebrow">Player detail</span>
              <StatusPill backendStatus={backendStatus} />
            </div>

            <h1>{player?.displayName ?? "Loading player..."}</h1>
            <p className="lede">
              {player?.teamName ?? "Unknown team"} · {player?.position ?? "Unknown position"} ·{" "}
              ESPN ID {athleteId}
            </p>
            {location.state?.candidate ? (
              <p className="player-meta">
                Search match loaded from the candidate list, then synced into your stored player
                records.
              </p>
            ) : null}
            {statusMessage ? <p className="player-meta">{statusMessage}</p> : null}

            <div className="player-action-row">
              <button type="button" className="secondary" onClick={() => navigate("/")}>
                Search another player
              </button>
            </div>
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
              <span className="card-label">Team branding</span>
              <h2>{player?.teamName ?? "Unknown team"}</h2>
              <p>{player?.position ?? "Unknown position"}</p>
            </div>
          </div>
        </section>

        {error ? <p className="inline-error">{error}</p> : null}

        {loading ? <div className="empty-state">Loading player detail...</div> : null}

        {!loading && insights ? (
          <>
            <section className="panel section">
              <div className="section-head">
                <div>
                  <span className="section-kicker">Insight snapshot</span>
                  <h2>Derived summary from stored game logs</h2>
                  <p>
                    These values are calculated from the raw stat rows and will stay the source of
                    truth for future betting and prediction features.
                  </p>
                </div>
              </div>

              <div className="insight-grid">
                <SummaryCard
                  label="Games loaded"
                  value={formatNumber(insights.gamesLoaded)}
                  detail="Game logs currently available for this player."
                />
                <SummaryCard
                  label="Yards per game"
                  value={formatPace(insights.overallSummary?.totalYardsPerGame)}
                  detail="Combined passing, rushing, and receiving yards."
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
                  <span className="summary-label">Overall</span>
                  <strong>{formatNumber(insights.overallSummary?.games ?? 0)} games</strong>
                  <p>
                    {formatNumber(insights.overallSummary?.totalYardsTotal ?? 0)} total yards and{" "}
                    {formatNumber(insights.overallSummary?.totalTouchdownsTotal ?? 0)} total touchdowns
                  </p>
                </article>
                <article className="summary-card">
                  <span className="summary-label">Last 5</span>
                  <strong>{formatNumber(insights.lastFiveSummary?.games ?? 0)} games</strong>
                  <p>
                    {formatPace(insights.lastFiveSummary?.totalYardsPerGame)} yards per game over the
                    recent window
                  </p>
                </article>
                <article className="summary-card">
                  <span className="summary-label">Last 3</span>
                  <strong>{formatNumber(insights.lastThreeSummary?.games ?? 0)} games</strong>
                  <p>
                    {formatPace(insights.lastThreeSummary?.totalYardsPerGame)} yards per game over the
                    last three
                  </p>
                </article>
                <article className="summary-card">
                  <span className="summary-label">Updated</span>
                  <strong>{formatDate(insights.generatedAt)}</strong>
                  <p>Latest derived summary from the stored records.</p>
                </article>
              </div>
            </section>

            <section className="panel section">
              <div className="section-head">
                <div>
                  <span className="section-kicker">Recent games</span>
                  <h2>Game-by-game history</h2>
                  <p>
                    Use this table to inspect the raw lines that drive the later trend and betting
                    views.
                  </p>
                </div>
              </div>

              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Opponent</th>
                      <th>Home/Away</th>
                      <th>Yards</th>
                      <th>TDs</th>
                      <th>Turnovers</th>
                      <th>Snaps</th>
                    </tr>
                  </thead>
                  <tbody>
                    {insights.recentGames?.length ? (
                      insights.recentGames.map((stat) => (
                        <tr key={stat.id}>
                          <td>
                            <strong>{formatShortDate(stat.gameDate)}</strong>
                            <div className="subtle">
                              Season {stat.season ?? "—"} Week {stat.week ?? "—"}
                            </div>
                          </td>
                          <td>{stat.opponentName ?? "Unknown"}</td>
                          <td>{stat.homeAway ?? "Unknown"}</td>
                          <td>{summarizeGame(stat)}</td>
                          <td>{formatNumber(stat.totalTouchdowns ?? stat.touchdowns ?? 0)}</td>
                          <td>{formatNumber(stat.turnovers ?? 0)}</td>
                          <td>{formatNumber(stat.snapCount ?? 0)}</td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan="7" className="candidate-meta">
                          No game stats loaded for this player yet.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </section>
          </>
        ) : null}
      </main>
    </AppShell>
  );
}

function App() {
  const [backendStatus, setBackendStatus] = useState("Checking backend...");

  useEffect(() => {
    let canceled = false;

    async function loadStatus() {
      try {
        await checkBackend();
        if (!canceled) {
          setBackendStatus("Backend online");
        }
      } catch {
        if (!canceled) {
          setBackendStatus("Backend offline");
        }
      }
    }

    loadStatus();

    return () => {
      canceled = true;
    };
  }, []);

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<HomePage backendStatus={backendStatus} />} />
        <Route path="/players/:athleteId" element={<PlayerDetailPage backendStatus={backendStatus} />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;

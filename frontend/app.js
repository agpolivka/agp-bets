const API_BASE = "http://localhost:8080";

const state = {
  players: [],
  filteredPlayers: [],
  candidates: [],
  selectedPlayer: null,
  selectedInsights: null,
};

const elements = {
  connectionStatus: document.getElementById("connectionStatus"),
  connectionDetail: document.getElementById("connectionDetail"),
  syncForm: document.getElementById("syncForm"),
  playerName: document.getElementById("playerName"),
  refreshButton: document.getElementById("refreshButton"),
  filterInput: document.getElementById("filterInput"),
  playerCount: document.getElementById("playerCount"),
  playerTableBody: document.getElementById("playerTableBody"),
  candidateCount: document.getElementById("candidateCount"),
  candidateTableBody: document.getElementById("candidateTableBody"),
  message: document.getElementById("message"),
  syncStatsButton: document.getElementById("syncStatsButton"),
  refreshStatsButton: document.getElementById("refreshStatsButton"),
  selectedPlayerName: document.getElementById("selectedPlayerName"),
  selectedPlayerMeta: document.getElementById("selectedPlayerMeta"),
  recentGamesCount: document.getElementById("recentGamesCount"),
  summaryModeLabel: document.getElementById("summaryModeLabel"),
  summaryCards: document.getElementById("summaryCards"),
  statsTableBody: document.getElementById("statsTableBody"),
};

function showMessage(text, type = "success") {
  elements.message.textContent = text;
  elements.message.className = `message ${type}`;
  elements.message.classList.remove("hidden");
}

function hideMessage() {
  elements.message.className = "message hidden";
  elements.message.textContent = "";
}

function formatDate(value) {
  if (!value) {
    return "Unknown";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Unknown";
  }

  return date.toLocaleString();
}

function formatShortDate(value) {
  if (!value) {
    return "Unknown";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "Unknown";
  }

  return date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function formatNumber(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) {
    return "—";
  }

  return new Intl.NumberFormat().format(Number(value));
}

function summarizeValue(stat) {
  const parts = [];
  if (stat.passingYards || stat.rushingYards || stat.receivingYards) {
    parts.push(`Yds ${formatNumber((stat.passingYards ?? 0) + (stat.rushingYards ?? 0) + (stat.receivingYards ?? 0))}`);
  }
  if (stat.totalTouchdowns !== undefined || stat.touchdowns !== undefined) {
    parts.push(`TD ${formatNumber(stat.totalTouchdowns ?? stat.touchdowns)}`);
  }
  if (stat.turnovers !== undefined) {
    parts.push(`TO ${formatNumber(stat.turnovers)}`);
  }
  if (stat.snapCount !== undefined) {
    parts.push(`Snaps ${formatNumber(stat.snapCount)}`);
  }
  return parts.length ? parts.join(" · ") : "No summary available";
}

function compactSummaryValue(summary, key) {
  if (!summary) {
    return "—";
  }

  const value = summary[key];
  if (value === null || value === undefined) {
    return "—";
  }

  if (typeof value === "number") {
    return Number.isInteger(value) ? formatNumber(value) : value.toFixed(1);
  }

  return String(value);
}

function renderInsightSummaryCard(title, summary, metricKey, metricLabel, extraLabel) {
  if (!summary) {
    return `
      <div class="summary-card empty">
        <span class="summary-label">${escapeHtml(title)}</span>
        <strong>—</strong>
        <p>No data loaded yet.</p>
      </div>
    `;
  }

  const gamesLabel = summary.games ? `${summary.games} games` : "No games";
  return `
    <div class="summary-card">
      <span class="summary-label">${escapeHtml(title)}</span>
      <strong>${escapeHtml(compactSummaryValue(summary, metricKey))}</strong>
      <p>${escapeHtml(metricLabel)} · ${escapeHtml(gamesLabel)}${extraLabel ? ` · ${escapeHtml(extraLabel)}` : ""}</p>
    </div>
  `;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function renderPlayers(players) {
  elements.playerCount.textContent = String(players.length);
  elements.playerTableBody.innerHTML = players
    .map(
      (player) => `
        <tr>
          <td>
            <strong>${escapeHtml(player.displayName ?? "Unknown")}</strong>
            <div class="subtle">${escapeHtml(player.espnAthleteId ?? "")}</div>
          </td>
          <td>${escapeHtml(player.teamName ?? "Free Agent")}</td>
          <td>${escapeHtml(player.position ?? "Unknown")}</td>
          <td>${escapeHtml(player.jerseyNumber ?? "N/A")}</td>
          <td>
            <span class="pill ${player.active ? "active" : "inactive"}">
              ${player.active ? "Active" : "Inactive"}
            </span>
          </td>
          <td>${escapeHtml(formatDate(player.updatedAt))}</td>
          <td>
            <button
              type="button"
              class="ghost"
              data-view-stats-athlete-id="${escapeHtml(player.espnAthleteId ?? "")}"
            >
              View stats
            </button>
          </td>
        </tr>
      `,
    )
    .join("");
}

function compareIsoDates(left, right) {
  const leftTime = left ? new Date(left).getTime() : 0;
  const rightTime = right ? new Date(right).getTime() : 0;
  if (Number.isNaN(leftTime) && Number.isNaN(rightTime)) {
    return 0;
  }
  if (Number.isNaN(leftTime)) {
    return -1;
  }
  if (Number.isNaN(rightTime)) {
    return 1;
  }
  return leftTime - rightTime;
}

function playerCompletenessScore(player) {
  return [
    player?.displayName,
    player?.teamName,
    player?.position,
    player?.jerseyNumber,
  ].reduce((score, value) => score + (value ? 1 : 0), 0);
}

function normalizePlayers(players) {
  const byAthleteId = new Map();
  const withoutAthleteId = [];

  for (const player of players) {
    const athleteId = player.espnAthleteId;
    if (!athleteId) {
      withoutAthleteId.push(player);
      continue;
    }

    const existing = byAthleteId.get(athleteId);
    if (!existing) {
      byAthleteId.set(athleteId, player);
      continue;
    }

    const existingScore = playerCompletenessScore(existing);
    const candidateScore = playerCompletenessScore(player);
    if (candidateScore > existingScore) {
      byAthleteId.set(athleteId, player);
      continue;
    }
    if (candidateScore < existingScore) {
      continue;
    }

    const existingUpdatedAt = existing.updatedAt ?? existing.createdAt ?? "";
    const candidateUpdatedAt = player.updatedAt ?? player.createdAt ?? "";
    if (compareIsoDates(existingUpdatedAt, candidateUpdatedAt) <= 0) {
      byAthleteId.set(athleteId, player);
    }
  }

  return [...byAthleteId.values(), ...withoutAthleteId];
}

function findStoredPlayer(athleteId) {
  return state.players.find((item) => item.espnAthleteId === athleteId) ?? null;
}

function renderCandidates(candidates) {
  elements.candidateCount.textContent = String(candidates.length);

  if (!candidates.length) {
    elements.candidateTableBody.innerHTML = `
      <tr>
        <td colspan="6" class="candidate-meta">Search for a player to see matching candidates here.</td>
      </tr>
    `;
    return;
  }

  elements.candidateTableBody.innerHTML = candidates
    .map((candidate) => {
      const matchPercent = Math.round((candidate.score ?? 0) * 100);
      const storedLabel = candidate.stored ? "Stored" : "Not yet";
      const storedClass = candidate.stored ? "stored" : "pending";
      const actionLabel = candidate.stored ? "Refresh player" : "Sync player";

      return `
        <tr>
          <td>
            <strong>${escapeHtml(candidate.displayName ?? "Unknown")}</strong>
            <div class="candidate-meta">${escapeHtml(candidate.espnAthleteId ?? "")}</div>
          </td>
          <td>${escapeHtml(candidate.teamName ?? "Unknown")}</td>
          <td>${escapeHtml(candidate.position ?? "Unknown")}</td>
          <td>
            <span class="pill inactive">${matchPercent}%</span>
          </td>
          <td>
            <span class="pill ${storedClass}">${storedLabel}</span>
          </td>
          <td>
            <div class="candidate-actions">
              <button
                type="button"
                class="ghost"
                data-sync-athlete-id="${escapeHtml(candidate.espnAthleteId ?? "")}"
                data-sync-player-name="${escapeHtml(candidate.displayName ?? "")}"
              >
                ${actionLabel}
              </button>
            </div>
          </td>
        </tr>
      `;
    })
    .join("");
}

function applyFilter() {
  const query = elements.filterInput.value.trim().toLowerCase();
  if (!query) {
    state.filteredPlayers = state.players;
    renderPlayers(state.players);
    return;
  }

  state.filteredPlayers = state.players.filter((player) => {
    return [player.displayName, player.teamName, player.position]
      .filter(Boolean)
      .some((field) => field.toLowerCase().includes(query));
  });

  renderPlayers(state.filteredPlayers);
}

function clearStatsPanel() {
  state.selectedPlayer = null;
  state.selectedInsights = null;
  elements.selectedPlayerName.textContent = "No player selected";
  elements.selectedPlayerMeta.textContent = "Choose a player from the stored list or sync a new one.";
  elements.recentGamesCount.textContent = "0";
  elements.summaryModeLabel.textContent = "All offense";
  elements.syncStatsButton.disabled = true;
  elements.refreshStatsButton.disabled = true;
  elements.summaryCards.innerHTML = `<div class="summary-card empty">Select a player to see computed summaries.</div>`;
  elements.statsTableBody.innerHTML = `<tr><td colspan="7" class="candidate-meta">No player selected yet.</td></tr>`;
}

function getStatModeLabel(player) {
  const position = String(player?.position ?? "").toUpperCase();
  if (position === "QB") {
    return "Quarterback";
  }
  if (["RB", "WR", "TE"].includes(position)) {
    return "Skill player";
  }
  return "All offense";
}

function renderSummaryCards(insights) {
  if (!insights) {
    elements.summaryCards.innerHTML = `<div class="summary-card empty">No game stats loaded for this player yet.</div>`;
    return;
  }

  const recentGameWindow = insights.recentGameWindow ?? 5;
  const overall = insights.overallSummary;
  const lastFive = insights.lastFiveSummary;
  const lastThree = insights.lastThreeSummary;
  const homeAway = insights.homeAwaySplits?.[0]?.summary ?? null;
  const opponent = insights.opponentSplits?.[0]?.summary ?? null;

  elements.summaryCards.innerHTML = [
    renderInsightSummaryCard(`Last ${recentGameWindow} games`, lastFive, "games", "games loaded", "derived from stored logs"),
    renderInsightSummaryCard("Season average yards", overall, "totalYardsPerGame", "combined yards per game", "overall"),
    renderInsightSummaryCard("Last 3 yards", lastThree, "totalYardsPerGame", "combined yards per game", "recent form"),
    renderInsightSummaryCard("Home split", homeAway, "totalYardsPerGame", "combined yards per game", "home/away"),
    renderInsightSummaryCard("Top opponent", opponent, "totalYardsPerGame", "combined yards per game", "most frequent opponent"),
  ].join("");
}

function renderStatsTable(stats) {
  elements.recentGamesCount.textContent = String(stats.length);

  if (!stats.length) {
    elements.statsTableBody.innerHTML = `
      <tr>
        <td colspan="7" class="candidate-meta">No player game stats loaded yet.</td>
      </tr>
    `;
    return;
  }

  elements.statsTableBody.innerHTML = stats
    .map(
      (stat) => `
        <tr>
          <td>
            <strong>${escapeHtml(formatShortDate(stat.gameDate))}</strong>
            <div class="subtle">Season ${escapeHtml(stat.season ?? "—")} Week ${escapeHtml(stat.week ?? "—")}</div>
          </td>
          <td>${escapeHtml(stat.opponentName ?? "Unknown")}</td>
          <td>${escapeHtml(stat.homeAway ?? "Unknown")}</td>
          <td>
            ${escapeHtml(summarizeValue(stat))}
          </td>
          <td>${escapeHtml(formatNumber(stat.totalTouchdowns ?? stat.touchdowns ?? 0))}</td>
          <td>${escapeHtml(formatNumber(stat.turnovers ?? 0))}</td>
          <td>${escapeHtml(formatNumber(stat.snapCount ?? 0))}</td>
        </tr>
      `,
    )
    .join("");
}

function setSelectedPlayer(player) {
  if (!player) {
    clearStatsPanel();
    return;
  }

  state.selectedPlayer = player;
  elements.selectedPlayerName.textContent = player.displayName ?? "Unknown player";
  elements.selectedPlayerMeta.textContent = [
    player.teamName ?? "Free Agent",
    player.position ?? "Unknown position",
    player.espnAthleteId ?? "",
  ]
    .filter(Boolean)
    .join(" · ");
  elements.summaryModeLabel.textContent = getStatModeLabel(player);
  elements.syncStatsButton.disabled = false;
  elements.refreshStatsButton.disabled = false;
}

async function loadPlayers() {
  const response = await fetch(`${API_BASE}/api/players`);
  if (!response.ok) {
    throw new Error(`Failed to load players (${response.status})`);
  }

  state.players = normalizePlayers(await response.json());
  state.filteredPlayers = state.players;
  applyFilter();
}

async function loadPlayerStats(athleteId) {
  const response = await fetch(`${API_BASE}/api/players/${encodeURIComponent(athleteId)}/stats`);
  if (!response.ok) {
    throw new Error(`Failed to load player stats (${response.status})`);
  }

  return response.json();
}

async function syncPlayerStats(athleteId) {
  const response = await fetch(`${API_BASE}/api/players/${encodeURIComponent(athleteId)}/stats/sync`, {
    method: "POST",
  });

  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message ?? `Failed to sync player stats (${response.status})`);
  }

  return response.json();
}

async function loadPlayerInsights(athleteId) {
  const response = await fetch(`${API_BASE}/api/players/${encodeURIComponent(athleteId)}/insights`);
  if (!response.ok) {
    throw new Error(`Failed to load player insights (${response.status})`);
  }

  return response.json();
}

async function showPlayerInsights(athleteId) {
  const player = findStoredPlayer(athleteId) ?? {
    espnAthleteId: athleteId,
  };
  setSelectedPlayer(player);
  showMessage(`Loading insights for ${player.displayName ?? athleteId}...`);
  const insights = await loadPlayerInsights(athleteId);
  state.selectedInsights = insights;
  renderSummaryCards(insights);
  renderStatsTable(insights.recentGames ?? []);
  elements.selectedPlayerName.textContent = insights.player?.displayName ?? player.displayName ?? "Unknown player";
  elements.selectedPlayerMeta.textContent = [
    insights.player?.teamName ?? player.teamName ?? "Free Agent",
    insights.player?.position ?? player.position ?? "Unknown position",
    insights.player?.espnAthleteId ?? athleteId,
  ]
    .filter(Boolean)
    .join(" · ");
  elements.summaryModeLabel.textContent = getStatModeLabel(insights.player ?? player);
  hideMessage();
}

async function searchCandidatesByName(name) {
  const response = await fetch(`${API_BASE}/api/players/search?name=${encodeURIComponent(name)}`);

  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message ?? `Failed to search players (${response.status})`);
  }

  return response.json();
}

async function syncPlayerByAthleteId(athleteId) {
  const response = await fetch(`${API_BASE}/api/players/sync/${encodeURIComponent(athleteId)}`, {
    method: "POST",
  });

  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message ?? `Failed to sync player (${response.status})`);
  }

  return response.json();
}

async function checkBackend() {
  try {
    const response = await fetch(`${API_BASE}/api/players`);
    if (!response.ok) {
      throw new Error();
    }
    elements.connectionStatus.textContent = "Connected";
    elements.connectionDetail.textContent = "The backend is responding and ready.";
  } catch {
    elements.connectionStatus.textContent = "Offline";
    elements.connectionDetail.textContent = "Start the backend on port 8080 to use this UI.";
  }
}

elements.syncForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const name = elements.playerName.value.trim();
  if (!name) {
    showMessage("Enter a player name to search.", "error");
    return;
  }

  try {
    const button = elements.syncForm.querySelector("button");
    button.disabled = true;
    showMessage(`Searching candidates for ${name}...`);
    state.candidates = await searchCandidatesByName(name);
    renderCandidates(state.candidates);
    if (state.candidates.length === 0) {
      showMessage(`No ESPN candidates found for ${name}. Try another spelling.`, "error");
    } else {
      showMessage(`Found ${state.candidates.length} candidate(s) for ${name}.`, "success");
    }
  } catch (error) {
    showMessage(error.message, "error");
  } finally {
    elements.syncForm.querySelector("button").disabled = false;
  }
});

elements.candidateTableBody.addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-sync-athlete-id]");
  if (!button) {
    return;
  }

  const athleteId = button.dataset.syncAthleteId;
  const playerName = button.dataset.syncPlayerName || athleteId;

  try {
    button.disabled = true;
    showMessage(`Syncing ${playerName}...`);
    await syncPlayerByAthleteId(athleteId);
    state.candidates = state.candidates.map((candidate) =>
      candidate.espnAthleteId === athleteId ? { ...candidate, stored: true } : candidate,
    );
    renderCandidates(state.candidates);
    await loadPlayers();
    await syncPlayerStats(athleteId).catch(() => null);
    await showPlayerInsights(athleteId).catch(() => null);
    showMessage(`Synced ${playerName} into the database.`, "success");
  } catch (error) {
    showMessage(error.message, "error");
  } finally {
    button.disabled = false;
  }
});

elements.playerTableBody.addEventListener("click", async (event) => {
  const button = event.target.closest("button[data-view-stats-athlete-id]");
  if (!button) {
    return;
  }

  const athleteId = button.dataset.viewStatsAthleteId;
  try {
    button.disabled = true;
    await showPlayerInsights(athleteId);
  } catch (error) {
    showMessage(error.message, "error");
  } finally {
    button.disabled = false;
  }
});

elements.refreshButton.addEventListener("click", async () => {
  try {
    hideMessage();
    await loadPlayers();
    showMessage("Player list refreshed.", "success");
  } catch (error) {
    showMessage(error.message, "error");
  }
});

elements.syncStatsButton.addEventListener("click", async () => {
  if (!state.selectedPlayer?.espnAthleteId) {
    return;
  }

  try {
    elements.syncStatsButton.disabled = true;
    elements.refreshStatsButton.disabled = true;
    showMessage(`Syncing stats for ${state.selectedPlayer.displayName ?? state.selectedPlayer.espnAthleteId}...`);
    await syncPlayerStats(state.selectedPlayer.espnAthleteId);
    await showPlayerInsights(state.selectedPlayer.espnAthleteId);
    showMessage("Player stats synced.", "success");
  } catch (error) {
    showMessage(error.message, "error");
  } finally {
    elements.syncStatsButton.disabled = false;
    elements.refreshStatsButton.disabled = false;
  }
});

elements.refreshStatsButton.addEventListener("click", async () => {
  if (!state.selectedPlayer?.espnAthleteId) {
    return;
  }

  try {
    elements.syncStatsButton.disabled = true;
    elements.refreshStatsButton.disabled = true;
    await showPlayerInsights(state.selectedPlayer.espnAthleteId);
  } catch (error) {
    showMessage(error.message, "error");
  } finally {
    elements.syncStatsButton.disabled = false;
    elements.refreshStatsButton.disabled = false;
  }
});

elements.filterInput.addEventListener("input", applyFilter);

async function bootstrap() {
  renderCandidates([]);
  clearStatsPanel();
  await checkBackend();
  try {
    await loadPlayers();
  } catch (error) {
    showMessage(error.message, "error");
  }
}

bootstrap();

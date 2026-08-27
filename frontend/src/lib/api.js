const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

async function request(path, options) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      "Content-Type": "application/json",
      ...(options?.headers ?? {}),
    },
    ...options,
  });

  if (!response.ok) {
    const raw = await response.text().catch(() => "");
    let message = `Request failed with status ${response.status}`;

    if (raw) {
      try {
        const body = JSON.parse(raw);
        message = body.message ?? body.error ?? message;
      } catch {
        message = raw;
      }
    }

    const error = new Error(message);
    error.status = response.status;
    error.responseText = raw;
    throw error;
  }

  if (response.status === 204) {
    return null;
  }

  return response.json();
}

export function getApiBaseUrl() {
  return API_BASE_URL;
}

export function checkBackend() {
  return request("/api/players");
}

export function searchPlayers(name) {
  return request(`/api/players/search?name=${encodeURIComponent(name)}`);
}

export function getPlayer(athleteId) {
  return request(`/api/players/${encodeURIComponent(athleteId)}`);
}

export function getPlayerInsights(athleteId) {
  return request(`/api/players/${encodeURIComponent(athleteId)}/insights`);
}

export function getPlayerPredictions(athleteId) {
  return request(`/api/players/${encodeURIComponent(athleteId)}/predictions`);
}

export function getTeam(teamId) {
  return request(`/api/teams/${encodeURIComponent(teamId)}`);
}

export function getTeamDefenseSummary(teamId) {
  return request(`/api/teams/${encodeURIComponent(teamId)}/defense-summary`);
}

export function syncPlayerStats(athleteId) {
  return request(`/api/players/${encodeURIComponent(athleteId)}/stats/sync`, {
    method: "POST",
  });
}

export function syncPlayerByAthleteId(athleteId) {
  return request(`/api/players/sync/${encodeURIComponent(athleteId)}`, {
    method: "POST",
  });
}

export function getUpcomingTeamMatchups() {
  return request("/api/team-matchups/upcoming");
}

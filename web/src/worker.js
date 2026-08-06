/* ═══════════════════════════════════════════
   VistaCore — Cloudflare Worker
   Proxies Xtream/M3U/EPG/Stream requests to
   avoid CORS and keep credentials server-side.
   Static assets served via [assets] in wrangler.
   ═══════════════════════════════════════════ */

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, PUT, OPTIONS',
  'Access-Control-Allow-Headers': '*',
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    // API routes
    if (url.pathname.startsWith('/api/')) {
      try {
        return await handleApi(url, request, env);
      } catch (err) {
        return jsonResponse({ error: err.message }, 500);
      }
    }

    // Everything else → static assets (handled by wrangler [assets])
    return env.ASSETS.fetch(request);
  },
};

// ─── Route handler ───
async function handleApi(url, request, env) {
  const path = url.pathname;

  if (path === '/api/config')        return handleConfig(env);
  if (path === '/api/auth/google')   return handleGoogleAuth(request, env);
  if (path === '/api/settings')      return handleSettings(request, env);
  if (path === '/api/xtream')        return handleXtream(url);
  if (path === '/api/m3u')           return handleM3U(url);
  if (path === '/api/epg')           return handleEpg(url);
  if (path === '/api/stream')        return handleStream(url, request);
  if (path === '/api/image')         return handleImage(url);
  if (path === '/api/tmdb')          return handleTmdb(url, env);

  return jsonResponse({ error: 'Unknown API route' }, 404);
}

// ─── TMDB proxy (for actor photos & credits) ───
async function handleTmdb(url, env) {
  const key = env && env.TMDB_KEY;
  if (!key) return jsonResponse({ error: 'TMDB not configured' }, 501);

  const tmdbPath = url.searchParams.get('path');
  if (!tmdbPath) return jsonResponse({ error: 'Missing path' }, 400);

  // Only allow TMDB paths, no arbitrary host
  const cleanPath = tmdbPath.replace(/^\/+/, '');
  const qs = new URLSearchParams();
  for (const [k, v] of url.searchParams) {
    if (k !== 'path') qs.set(k, v);
  }
  qs.set('api_key', key);

  const tmdbUrl = `https://api.themoviedb.org/3/${cleanPath}?${qs.toString()}`;
  try {
    const resp = await fetch(tmdbUrl);
    const body = await resp.text();
    return new Response(body, {
      status: resp.status,
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'public, max-age=86400',
        ...CORS_HEADERS,
      },
    });
  } catch (err) {
    return jsonResponse({ error: String(err.message || err) }, 502);
  }
}

// ─── Config (client reads Google client ID from here) ───
async function handleConfig(env) {
  return jsonResponse({
    googleClientId: (env && env.GOOGLE_CLIENT_ID) || '',
    settingsSync: !!(env && env.SETTINGS && env.SESSION_SECRET),
  });
}

/* ─── Session tokens ───
   Google ID tokens expire after ~1 hour, which is useless for "stay signed in".
   So on successful Google verification we mint our own token — the account's
   subject ID plus an expiry, signed with HMAC-SHA256 — and verify that on
   settings requests. The client never gets to assert who it is. */
const SESSION_TTL_MS = 90 * 24 * 60 * 60 * 1000; // 90 days

function b64urlEncode(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlDecode(str) {
  const pad = str.length % 4 ? '='.repeat(4 - (str.length % 4)) : '';
  const bin = atob(str.replace(/-/g, '+').replace(/_/g, '/') + pad);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function hmacKey(secret) {
  return crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign', 'verify']
  );
}

async function mintSession(env, payload) {
  const body = b64urlEncode(new TextEncoder().encode(JSON.stringify(payload)));
  const sig = await crypto.subtle.sign(
    'HMAC', await hmacKey(env.SESSION_SECRET), new TextEncoder().encode(body)
  );
  return `${body}.${b64urlEncode(new Uint8Array(sig))}`;
}

async function verifySession(env, token) {
  if (!token || !env || !env.SESSION_SECRET) return null;
  const [body, sig] = String(token).split('.');
  if (!body || !sig) return null;

  try {
    // crypto.subtle.verify compares in constant time — don't hand-roll this.
    const ok = await crypto.subtle.verify(
      'HMAC', await hmacKey(env.SESSION_SECRET),
      b64urlDecode(sig), new TextEncoder().encode(body)
    );
    if (!ok) return null;
    const payload = JSON.parse(new TextDecoder().decode(b64urlDecode(body)));
    if (!payload.sub || !payload.exp || Date.now() > payload.exp) return null;
    return payload;
  } catch {
    return null;
  }
}

/* ─── Per-account settings (KV) ───
   Keyed by the Google subject ID rather than email — the subject is stable
   even if the account's email address changes. */
async function handleSettings(request, env) {
  if (!env || !env.SETTINGS || !env.SESSION_SECRET) {
    return jsonResponse({ error: 'Settings sync not configured' }, 501);
  }

  const header = request.headers.get('authorization') || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  const session = await verifySession(env, token);
  if (!session) return jsonResponse({ error: 'Not authenticated' }, 401);

  const kvKey = `settings:${session.sub}`;

  if (request.method === 'GET') {
    const stored = await env.SETTINGS.get(kvKey);
    return jsonResponse({ settings: stored ? JSON.parse(stored) : null });
  }

  if (request.method === 'PUT' || request.method === 'POST') {
    let body;
    try { body = await request.json(); } catch { body = null; }
    const settings = body && body.settings;
    if (!settings || typeof settings !== 'object' || Array.isArray(settings)) {
      return jsonResponse({ error: 'Missing settings object' }, 400);
    }
    await env.SETTINGS.put(kvKey, JSON.stringify(settings));
    return jsonResponse({ ok: true, savedAt: Date.now() });
  }

  return jsonResponse({ error: 'Method not allowed' }, 405);
}

// ─── Google Sign-In verification ───
async function handleGoogleAuth(request, env) {
  if (request.method !== 'POST') {
    return jsonResponse({ error: 'Method not allowed' }, 405);
  }

  let body;
  try { body = await request.json(); } catch { body = {}; }
  const credential = body.credential;
  if (!credential) return jsonResponse({ error: 'Missing credential' }, 400);

  // Verify via Google's tokeninfo endpoint (validates signature + expiry)
  const tokenInfoResp = await fetch(
    `https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(credential)}`
  );
  if (!tokenInfoResp.ok) {
    return jsonResponse({ error: 'Invalid Google token' }, 401);
  }
  const info = await tokenInfoResp.json();

  // Validate audience (must match our client ID)
  if (env && env.GOOGLE_CLIENT_ID && info.aud !== env.GOOGLE_CLIENT_ID) {
    return jsonResponse({ error: 'Token audience mismatch' }, 401);
  }

  // Optional email allowlist (comma-separated in env.ALLOWED_EMAILS)
  if (env && env.ALLOWED_EMAILS) {
    const allowed = env.ALLOWED_EMAILS.split(',').map(s => s.trim().toLowerCase());
    if (!allowed.includes(String(info.email || '').toLowerCase())) {
      return jsonResponse({ error: 'Email not authorized' }, 403);
    }
  }

  // Mint a long-lived session so settings sync survives the Google token's
  // ~1h lifetime. Absent SESSION_SECRET the app still works, just device-local.
  const token = (env && env.SESSION_SECRET)
    ? await mintSession(env, {
        sub: info.sub,
        email: info.email,
        exp: Date.now() + SESSION_TTL_MS,
      })
    : '';

  return jsonResponse({
    email: info.email,
    name: info.name || info.email,
    picture: info.picture || '',
    sub: info.sub,
    token,
  });
}

// ─── Xtream Codes API proxy ───
async function handleXtream(url) {
  const server   = url.searchParams.get('server');
  const username = url.searchParams.get('username');
  const password = url.searchParams.get('password');
  const action   = url.searchParams.get('action');

  if (!server || !username || !password) {
    return jsonResponse({ error: 'Missing server/username/password' }, 400);
  }

  const target = new URL(`${server}/player_api.php`);
  target.searchParams.set('username', username);
  target.searchParams.set('password', password);
  if (action) target.searchParams.set('action', action);

  // Forward any extra params (vod_id, series_id, etc.)
  for (const [k, v] of url.searchParams.entries()) {
    if (!['server', 'username', 'password', 'action'].includes(k)) {
      target.searchParams.set(k, v);
    }
  }

  try {
    const resp = await fetch(target.toString(), {
      headers: {
        'User-Agent': BROWSER_UA,
        'Accept': 'application/json, text/plain, */*',
        'Accept-Language': 'en-US,en;q=0.9',
        'Referer': server.replace(/\/+$/, '') + '/',
      },
      redirect: 'follow',
    });

    // Decode body to text so we can pass through upstream error messages
    const bodyText = await resp.text();

    return new Response(bodyText, {
      status: resp.status,
      headers: {
        'Content-Type': resp.ok
          ? 'application/json; charset=utf-8'
          : 'text/plain; charset=utf-8',
        ...CORS_HEADERS,
      },
    });
  } catch (err) {
    return jsonResponse(
      { error: `Xtream proxy failed: ${err.message}` },
      502
    );
  }
}

// Browser-like UA — many IPTV providers reject non-browser agents
const BROWSER_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36';

// ─── M3U playlist proxy ───
async function handleM3U(url) {
  const target = url.searchParams.get('url');
  if (!target) return jsonResponse({ error: 'Missing url param' }, 400);

  try {
    const resp = await fetch(target, {
      headers: {
        'User-Agent': BROWSER_UA,
        'Accept': '*/*',
      },
      redirect: 'follow',
    });

    if (!resp.ok) {
      return new Response(`Upstream error ${resp.status}: ${resp.statusText}`, {
        status: resp.status,
        headers: { 'Content-Type': 'text/plain', ...CORS_HEADERS },
      });
    }

    // Decode to text so gzip/br encoding is handled correctly,
    // then re-emit as plain UTF-8 with no Content-Encoding mismatch.
    const text = await resp.text();
    return new Response(text, {
      headers: {
        'Content-Type': 'text/plain; charset=utf-8',
        ...CORS_HEADERS,
      },
    });
  } catch (err) {
    return new Response(`M3U proxy error: ${err.message}`, {
      status: 502,
      headers: { 'Content-Type': 'text/plain', ...CORS_HEADERS },
    });
  }
}

// ─── EPG XML proxy ───
async function handleEpg(url) {
  const target = url.searchParams.get('url');
  if (!target) return jsonResponse({ error: 'Missing url param' }, 400);

  try {
    const resp = await fetch(target, {
      headers: {
        'User-Agent': BROWSER_UA,
        'Accept': '*/*',
      },
      redirect: 'follow',
    });

    if (!resp.ok) {
      return new Response(`Upstream error ${resp.status}: ${resp.statusText}`, {
        status: resp.status,
        headers: { 'Content-Type': 'text/plain', ...CORS_HEADERS },
      });
    }

    const text = await resp.text();
    return new Response(text, {
      headers: {
        'Content-Type': 'application/xml; charset=utf-8',
        ...CORS_HEADERS,
      },
    });
  } catch (err) {
    return new Response(`EPG proxy error: ${err.message}`, {
      status: 502,
      headers: { 'Content-Type': 'text/plain', ...CORS_HEADERS },
    });
  }
}

// ─── Stream proxy (HLS + direct video) ───
async function handleStream(url, request) {
  const target = url.searchParams.get('url');
  if (!target) return jsonResponse({ error: 'Missing url param' }, 400);

  // Forward Range header from the browser so <video> can seek MP4s.
  const upstreamHeaders = { 'User-Agent': BROWSER_UA, 'Accept': '*/*' };
  const range = request && request.headers.get('range');
  if (range) upstreamHeaders['Range'] = range;

  // Follow redirects manually — Workers' automatic follow can drop the
  // Range header, and some VOD providers 301 to a session-scoped URL
  // (e.g. ?session_id=...) where that matters for 206 framing.
  let resp;
  try {
    resp = await fetchFollow(target, upstreamHeaders);
  } catch (err) {
    return new Response(`Stream proxy error: ${err.message}`, {
      status: 502,
      headers: { 'Content-Type': 'text/plain', ...CORS_HEADERS },
    });
  }

  // Some providers reject Range requests with 5xx — retry without Range once.
  if (range && resp.status >= 500) {
    try {
      resp = await fetchFollow(target, { 'User-Agent': BROWSER_UA, 'Accept': '*/*' });
    } catch (err) {
      return new Response(`Stream proxy error: ${err.message}`, {
        status: 502,
        headers: { 'Content-Type': 'text/plain', ...CORS_HEADERS },
      });
    }
  }

  // Accept both 200 (full) and 206 (partial) — anything else is an error
  if (!resp.ok && resp.status !== 206) {
    return new Response(`Upstream error: ${resp.status}`, {
      status: resp.status,
      headers: CORS_HEADERS,
    });
  }

  const contentType = resp.headers.get('content-type') || '';
  const isManifest =
    contentType.includes('mpegurl') ||
    contentType.includes('x-mpegurl') ||
    target.endsWith('.m3u8') ||
    target.includes('.m3u8?');

  if (isManifest) {
    // Rewrite URLs inside HLS manifests so segments also proxy through us
    let text = await resp.text();
    const baseUrl = target.substring(0, target.lastIndexOf('/') + 1);

    text = text.replace(/^(?!#)(\S+)$/gm, (match) => {
      if (!match.trim()) return match;
      const absolute = match.startsWith('http') ? match : baseUrl + match;
      return `/api/stream?url=${encodeURIComponent(absolute)}`;
    });

    return new Response(text, {
      headers: {
        'Content-Type': 'application/vnd.apple.mpegurl',
        ...CORS_HEADERS,
      },
    });
  }

  // Pass through video segments, MP4, etc. Copy upstream headers so
  // Content-Length / Content-Range / Accept-Ranges all line up with the
  // body — mismatches cause a 520 from the Cloudflare edge.
  const headers = new Headers(resp.headers);
  for (const [k, v] of Object.entries(CORS_HEADERS)) headers.set(k, v);
  if (!headers.has('Content-Type')) headers.set('Content-Type', 'video/mp4');
  // Strip hop-by-hop headers that don't belong in a proxied response.
  for (const h of ['connection', 'keep-alive', 'transfer-encoding', 'upgrade', 'proxy-authenticate', 'proxy-authorization', 'te', 'trailer']) {
    headers.delete(h);
  }

  return new Response(resp.body, {
    status: resp.status,
    statusText: resp.statusText,
    headers,
  });
}

// Manual redirect follower that re-sends the given headers (including Range)
// on every hop. Max 5 redirects.
async function fetchFollow(url, headers) {
  let currentUrl = url;
  for (let i = 0; i < 5; i++) {
    const resp = await fetch(currentUrl, { headers, redirect: 'manual' });
    if (resp.status >= 300 && resp.status < 400) {
      const loc = resp.headers.get('location');
      if (!loc) return resp;
      currentUrl = new URL(loc, currentUrl).toString();
      continue;
    }
    return resp;
  }
  throw new Error('Too many redirects');
}

// ─── Image proxy (posters, channel logos, thumbnails) ───
// Needed because many Xtream providers serve images over plain HTTP,
// which browsers block as mixed content on an HTTPS page.
async function handleImage(url) {
  const raw = url.searchParams.get('url');
  if (!raw) return new Response('Missing url param', { status: 400, headers: CORS_HEADERS });

  // Defensive: some providers return values like "https, https://host/path".
  // Extract the last http(s) URL in the string.
  const matches = raw.match(/https?:\/\/[^\s,"']+/g);
  const target = matches && matches.length ? matches[matches.length - 1] : null;
  if (!target) return new Response(`Invalid image URL: ${raw}`, { status: 400, headers: CORS_HEADERS });

  try {
    const resp = await fetch(target, {
      headers: {
        'User-Agent': BROWSER_UA,
        'Accept': 'image/*,*/*;q=0.8',
      },
      redirect: 'follow',
      cf: { cacheTtl: 86400, cacheEverything: true },
    });

    if (!resp.ok) {
      return new Response(`Upstream error ${resp.status}`, {
        status: resp.status,
        headers: CORS_HEADERS,
      });
    }

    const contentType = resp.headers.get('content-type') || 'image/jpeg';
    return new Response(resp.body, {
      headers: {
        'Content-Type': contentType,
        'Cache-Control': 'public, max-age=86400',
        ...CORS_HEADERS,
      },
    });
  } catch (err) {
    return new Response(`Image proxy error: ${err.message}`, {
      status: 502,
      headers: { 'Content-Type': 'text/plain', ...CORS_HEADERS },
    });
  }
}

// ─── Helpers ───
function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...CORS_HEADERS },
  });
}

/* ═══════════════════════════════════════════
   VistaCore — Cloudflare Worker
   Proxies Xtream/M3U/EPG/Stream requests to
   avoid CORS and keep credentials server-side.
   Static assets served via [assets] in wrangler.
   ═══════════════════════════════════════════ */

const CORS_HEADERS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
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
  if (path === '/api/xtream')        return handleXtream(url);
  if (path === '/api/m3u')           return handleM3U(url);
  if (path === '/api/epg')           return handleEpg(url);
  if (path === '/api/stream')        return handleStream(url);

  return jsonResponse({ error: 'Unknown API route' }, 404);
}

// ─── Config (client reads Google client ID from here) ───
async function handleConfig(env) {
  return jsonResponse({
    googleClientId: (env && env.GOOGLE_CLIENT_ID) || '',
  });
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

  return jsonResponse({
    email: info.email,
    name: info.name || info.email,
    picture: info.picture || '',
    sub: info.sub,
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
async function handleStream(url) {
  const target = url.searchParams.get('url');
  if (!target) return jsonResponse({ error: 'Missing url param' }, 400);

  const resp = await fetch(target, {
    headers: { 'User-Agent': BROWSER_UA },
    redirect: 'follow',
  });

  if (!resp.ok) {
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

  // Pass through video segments, MP4, etc.
  return new Response(resp.body, {
    headers: {
      'Content-Type': contentType || 'video/mp2t',
      ...CORS_HEADERS,
    },
  });
}

// ─── Helpers ───
function jsonResponse(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...CORS_HEADERS },
  });
}

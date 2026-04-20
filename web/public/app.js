/* ═══════════════════════════════════════════
   VistaCore Web — Main Application
   ═══════════════════════════════════════════ */

// ─── Globals ───
const iptv = new IPTVService();
const favorites = new FavoritesManager();
const watchHistory = new WatchHistoryManager();
const recentChannels = new RecentChannelsManager();
window.watchHistory = watchHistory; // expose for player.js

let liveTvPlayer = null;
let detailPlayer = null;
let currentView = 'home';
let vodPageSize = 60;
let moviesPage = 0;
let tvshowsPage = 0;
let moviesCategory = 'all';
let tvshowsCategory = 'all';
let kidsCategory = 'all';
let currentDetailItem = null;
let currentDetailType = null; // 'movie' or 'series'
let activeChannelId = null;

// ─── Auth (Google Sign-In) ───
function isLoggedIn() { return sessionStorage.getItem('vc_user') !== null; }

function logout() {
  sessionStorage.removeItem('vc_user');
  if (window.google && google.accounts && google.accounts.id) {
    google.accounts.id.disableAutoSelect();
  }
  showScreen('login');
}

function currentUser() {
  const r = sessionStorage.getItem('vc_user');
  return r ? JSON.parse(r) : null;
}

let _googleClientId = null;

async function initGoogleSignIn() {
  // Fetch client ID from Worker config
  if (!_googleClientId) {
    try {
      const resp = await fetch('/api/config');
      const cfg = await resp.json();
      _googleClientId = cfg.googleClientId;
    } catch (e) {
      console.error('Failed to fetch config', e);
    }
  }

  const hintEl = document.getElementById('login-hint');
  if (!_googleClientId) {
    if (hintEl) {
      hintEl.textContent = 'Google Sign-In is not configured. Set GOOGLE_CLIENT_ID on the Worker.';
      hintEl.style.color = 'var(--status-off)';
    }
    return;
  }

  // Wait for GSI library (loaded async in head)
  await waitForGoogle();

  google.accounts.id.initialize({
    client_id: _googleClientId,
    callback: handleGoogleCredential,
    auto_select: true,
    cancel_on_tap_outside: false,
  });

  google.accounts.id.renderButton(
    document.getElementById('g_signin_button'),
    {
      type: 'standard',
      theme: 'filled_black',
      size: 'large',
      text: 'signin_with',
      shape: 'pill',
      logo_alignment: 'left',
    }
  );

  // Also show the One Tap prompt
  google.accounts.id.prompt();
}

function waitForGoogle() {
  return new Promise((resolve) => {
    if (window.google && google.accounts && google.accounts.id) return resolve();
    const check = setInterval(() => {
      if (window.google && google.accounts && google.accounts.id) {
        clearInterval(check);
        resolve();
      }
    }, 100);
    setTimeout(() => { clearInterval(check); resolve(); }, 10000);
  });
}

async function handleGoogleCredential(response) {
  const errEl = document.getElementById('login-error');
  errEl.hidden = true;

  try {
    const verifyResp = await fetch('/api/auth/google', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ credential: response.credential }),
    });

    if (!verifyResp.ok) {
      const data = await verifyResp.json().catch(() => ({}));
      errEl.textContent = data.error || 'Sign-in failed';
      errEl.hidden = false;
      return;
    }

    const user = await verifyResp.json();
    sessionStorage.setItem('vc_user', JSON.stringify({
      email: user.email,
      name: user.name,
      picture: user.picture,
    }));
    showScreen('dashboard');
  } catch (err) {
    errEl.textContent = 'Sign-in failed: ' + err.message;
    errEl.hidden = false;
  }
}

// ─── Screens ───
function showScreen(name) {
  document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
  const id = name === 'login' ? 'login-screen' : 'dashboard-screen';
  document.getElementById(id).classList.add('active');
  document.body.classList.toggle('login-page', name === 'login');
  if (name === 'dashboard') initDashboard();
  if (name === 'login') initGoogleSignIn();
}

// ─── View Router ───
function navigateTo(view, params = {}) {
  // Hide all views
  document.querySelectorAll('.view').forEach(v => v.style.display = 'none');

  const target = document.getElementById(`view-${view}`);
  if (target) target.style.display = '';

  currentView = view;

  // Update topbar
  const backBtn = document.getElementById('btn-back');
  const viewTitle = document.getElementById('topbar-view-title');
  if (view === 'home') {
    backBtn.style.display = 'none';
    viewTitle.style.display = 'none';
  } else {
    backBtn.style.display = '';
    viewTitle.style.display = '';
    const titles = {
      livetv: 'Live TV', movies: 'Movies', tvshows: 'TV Shows',
      kids: 'Kids', detail: params.title || 'Details', search: 'Search'
    };
    viewTitle.textContent = titles[view] || '';
  }

  // Initialize view-specific content
  switch (view) {
    case 'livetv': initLiveTv(); break;
    case 'movies': initMovies(); break;
    case 'tvshows': initTvShows(); break;
    case 'kids': initKids(); break;
    case 'detail': initDetail(params); break;
    case 'search': initSearchResults(params.query); break;
  }

  window.scrollTo(0, 0);
}

// (Google Sign-In is initialized in initGoogleSignIn via showScreen('login'))

// ─── Back / Brand click ───
document.getElementById('btn-back').addEventListener('click', () => {
  if (currentView === 'detail' && detailPlayer) {
    detailPlayer.stop();
    document.getElementById('detail-player-wrap').style.display = 'none';
  }
  // If we came from a sub-view, go back to it
  if (currentView === 'detail' && currentDetailType === 'movie') navigateTo('movies');
  else if (currentView === 'detail' && currentDetailType === 'series') navigateTo('tvshows');
  else navigateTo('home');
});
document.getElementById('topbar-brand-link').addEventListener('click', () => {
  if (currentView !== 'home') {
    if (detailPlayer) detailPlayer.stop();
    if (liveTvPlayer) liveTvPlayer.stop();
    navigateTo('home');
  }
});

// ─── App Card Data ───
const APPS = [
  { id: 'iptv',    label: 'IPTV',      color: 'iptv',    action: () => navigateTo('livetv') },
  { id: 'movies',  label: 'Movies',    color: 'movies',  action: () => navigateTo('movies') },
  { id: 'tvshows', label: 'TV Shows',  color: 'tvshows', action: () => navigateTo('tvshows') },
  { id: 'kids',    label: 'Kids',      color: 'kids',    action: () => navigateTo('kids') },
  { id: 'espn',    label: 'ESPN',      color: 'espn',    action: () => window.open('https://www.espn.com', '_blank') },
  { id: 'roku',    label: 'Roku',      color: 'roku',    action: () => window.open('https://therokuchannel.roku.com', '_blank') },
  { id: 'disney',  label: 'Disney+',   color: 'disney',  action: () => window.open('https://www.disneyplus.com', '_blank') },
];

const APP_ICONS = {
  iptv: `<svg class="app-card-icon svg-icon" viewBox="0 0 64 64" fill="none"><rect x="6" y="12" width="52" height="34" rx="4" stroke="white" stroke-width="2.5"/><path d="M6 42h52M26 46h12" stroke="white" stroke-width="2.5" stroke-linecap="round"/><circle cx="32" cy="29" r="8" stroke="white" stroke-width="2"/><path d="M30 26l6 3-6 3z" fill="white"/></svg>`,
  movies: `<svg class="app-card-icon svg-icon" viewBox="0 0 64 64" fill="none"><rect x="10" y="8" width="44" height="48" rx="3" stroke="white" stroke-width="2.5"/><path d="M10 18h44M18 8v10M28 8v10M38 8v10M48 8v10" stroke="white" stroke-width="2"/><circle cx="32" cy="38" r="8" stroke="white" stroke-width="2"/><path d="M30 35l6 3-6 3z" fill="white"/></svg>`,
  tvshows: `<svg class="app-card-icon svg-icon" viewBox="0 0 64 64" fill="none"><rect x="4" y="16" width="56" height="36" rx="4" stroke="white" stroke-width="2.5"/><path d="M24 8l8 8 8-8" stroke="white" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/><line x1="14" y1="24" x2="14" y2="44" stroke="white" stroke-width="2.5"/><rect x="22" y="24" width="28" height="20" rx="2" stroke="white" stroke-width="1.5"/></svg>`,
  kids: `<svg class="app-card-icon svg-icon" viewBox="0 0 64 64" fill="none"><circle cx="32" cy="26" r="14" stroke="white" stroke-width="2.5"/><path d="M22 52c0-8 4.5-14 10-14s10 6 10 14" stroke="white" stroke-width="2.5" stroke-linecap="round"/><circle cx="27" cy="24" r="2" fill="white"/><circle cx="37" cy="24" r="2" fill="white"/><path d="M28 30c2 2 6 2 8 0" stroke="white" stroke-width="1.5" stroke-linecap="round"/></svg>`,
  espn: `<img class="app-card-icon" src="/assets/ic_espn.png" alt="ESPN" onerror="this.outerHTML='<span class=\\'app-card-icon svg-icon\\' style=\\'font-size:2rem;font-weight:800;font-family:var(--font-display)\\'>ESPN</span>'">`,
  roku: `<img class="app-card-icon" src="/assets/ic_roku.png" alt="Roku" onerror="this.outerHTML='<span class=\\'app-card-icon svg-icon\\' style=\\'font-size:1.6rem;font-weight:700;font-family:var(--font-display)\\'>ROKU</span>'">`,
  disney: `<img class="app-card-icon" src="/assets/ic_disney.webp" alt="Disney+" onerror="this.outerHTML='<span class=\\'app-card-icon svg-icon\\' style=\\'font-size:1.4rem;font-weight:700;font-family:var(--font-display)\\'>Disney+</span>'">`,
};

// ─── Wallpapers ───
const WALLPAPERS = [
  '/assets/wallpapers/pexels-eberhardgross-640781.jpg',
  '/assets/wallpapers/pexels-didsss-3561009.jpg',
  '/assets/wallpapers/pexels-david-bartus-43782-2873479.jpg',
  '/assets/wallpapers/pexels-connorscottmcmanus-28818658.jpg',
  '/assets/wallpapers/pexels-alinluna-15576516.jpg',
];

// ═══════════════════════════════════════════
// DASHBOARD INIT
// ═══════════════════════════════════════════
let dashboardInitialized = false;

async function initDashboard() {
  const user = currentUser();
  if (!user) return showScreen('login');

  document.getElementById('user-name').textContent = user.name || user.email || 'User';
  const avatarEl = document.getElementById('user-avatar');
  if (user.picture) {
    avatarEl.innerHTML = `<img src="${user.picture}" alt="" style="width:100%;height:100%;object-fit:cover;border-radius:50%" referrerpolicy="no-referrer">`;
  } else {
    avatarEl.textContent = (user.name || user.email || 'U').charAt(0).toUpperCase();
  }

  if (dashboardInitialized) {
    navigateTo('home');
    return;
  }
  dashboardInitialized = true;

  // Hero background
  const wallpaper = WALLPAPERS[Math.floor(Math.random() * WALLPAPERS.length)];
  document.getElementById('hero-bg').style.backgroundImage = `url('${wallpaper}')`;

  // Clock
  updateClock();
  setInterval(updateClock, 10000);

  // Build apps row
  buildAppsRow();

  // Load settings and IPTV data
  loadSettings();
  await loadIPTVData();

  // Build dynamic home sections
  buildHomeSections();

  navigateTo('home');
}

function updateClock() {
  const now = new Date();
  let h = now.getHours();
  const ampm = h >= 12 ? 'PM' : 'AM';
  h = h % 12 || 12;
  const m = String(now.getMinutes()).padStart(2, '0');
  document.getElementById('clock-time').textContent = `${h}:${m}`;
  document.getElementById('clock-ampm').textContent = ampm;
  const days = ['Sunday','Monday','Tuesday','Wednesday','Thursday','Friday','Saturday'];
  const months = ['January','February','March','April','May','June','July','August','September','October','November','December'];
  document.getElementById('clock-date').textContent = `${days[now.getDay()]}, ${months[now.getMonth()]} ${now.getDate()}`;
  const hour = now.getHours();
  document.getElementById('greeting').textContent = hour < 12 ? 'Good Morning' : hour < 17 ? 'Good Afternoon' : 'Good Evening';
}

function buildAppsRow() {
  const row = document.getElementById('apps-row');
  row.innerHTML = APPS.map(app => `
    <div class="app-card" data-app="${app.id}" data-action="${app.id}">
      <div class="app-card-ring"></div>
      ${APP_ICONS[app.id]}
      <span class="app-card-label">${app.label}</span>
    </div>
  `).join('');
  // Bind clicks
  row.querySelectorAll('.app-card').forEach(card => {
    card.addEventListener('click', () => {
      const appId = card.dataset.action;
      const app = APPS.find(a => a.id === appId);
      if (app) app.action();
    });
  });
}

// ─── IPTV Data Loading ───
async function loadIPTVData() {
  const settings = getSettings();
  iptv.configure(settings);

  if (!iptv.isConfigured) {
    document.getElementById('setup-prompt').style.display = '';
    return;
  }

  document.getElementById('setup-prompt').style.display = 'none';
  document.getElementById('loading-section').style.display = '';

  iptv.on('loading', (data) => {
    const el = document.getElementById('loading-text');
    if (el) el.textContent = data.phase;
  });

  iptv.on('refreshed', () => {
    toast('Catalog updated in the background');
    buildHomeSections();
  });

  try {
    const start = performance.now();
    await iptv.loadAll();
    const elapsed = Math.round(performance.now() - start);
    document.getElementById('loading-section').style.display = 'none';
    const counts = `${iptv.channels.length} channels, ${iptv.movies.length} movies, ${iptv.series.length} series`;
    toast(elapsed < 500 ? `Loaded ${counts} from cache` : `Loaded ${counts}`);

    // Load EPG in background
    if (settings.epg) {
      iptv.loadEPG(settings.epg).catch(() => {});
    }
  } catch (err) {
    document.getElementById('loading-section').style.display = 'none';
    document.getElementById('setup-prompt').style.display = '';
    toast('Failed to load IPTV data: ' + err.message, true);
    console.error('IPTV load error:', err);
  }
}

function buildHomeSections() {
  buildContinueWatching();
  buildLiveNow();
  buildFavoriteChannels();
}

// ─── Continue Watching (from real watch history) ───
function buildContinueWatching() {
  const items = watchHistory.getContinueWatching();
  const section = document.getElementById('continue-section');
  if (items.length === 0) { section.style.display = 'none'; return; }
  section.style.display = '';

  const row = document.getElementById('continue-row');
  row.innerHTML = items.map(item => {
    const pct = item.duration > 0 ? Math.round((item.position / item.duration) * 100) : 0;
    const remaining = item.duration > 0 ? Math.ceil((item.duration - item.position) / 60) : 0;
    const bgColor = item.type === 'movie' ? '#E65100' : item.type === 'series' ? '#3949AB' : '#00897B';
    return `
    <div class="content-card" data-id="${item.id}" data-type="${item.type}">
      <div class="content-card-thumb" style="background:linear-gradient(135deg, ${bgColor}, ${bgColor}88);display:flex;align-items:center;justify-content:center;">
        ${item.poster ? `<img src="${item.poster}" style="width:100%;height:100%;object-fit:cover;" onerror="this.remove()">` : ''}
        <svg width="36" height="36" viewBox="0 0 24 24" fill="white" opacity="0.7" style="position:absolute"><polygon points="5,3 19,12 5,21"/></svg>
      </div>
      <div class="content-card-info">
        <div class="content-card-title">${escHtml(item.name)}</div>
        <div class="content-card-meta">${remaining} min left</div>
        <div class="content-card-progress"><div class="content-card-progress-bar" style="width:${pct}%"></div></div>
      </div>
    </div>`;
  }).join('');

  row.querySelectorAll('.content-card').forEach(card => {
    card.addEventListener('click', () => {
      const id = card.dataset.id;
      const type = card.dataset.type;
      if (type === 'channel') {
        navigateTo('livetv');
        setTimeout(() => playChannel(id), 300);
      } else if (type === 'movie') {
        const movie = iptv.movies.find(m => m.id === id);
        if (movie) navigateTo('detail', { item: movie, type: 'movie' });
      }
    });
  });
}

// ─── Live Now (real channels) ───
function buildLiveNow() {
  const section = document.getElementById('live-section');
  if (iptv.channels.length === 0) { section.style.display = 'none'; return; }
  section.style.display = '';

  // Show recent channels or first N
  const recentIds = recentChannels.getAll();
  let displayChannels;
  if (recentIds.length > 0) {
    displayChannels = recentIds.map(id => iptv.channels.find(c => c.id === id)).filter(Boolean).slice(0, 10);
  } else {
    displayChannels = iptv.channels.slice(0, 10);
  }

  const row = document.getElementById('epg-row');
  row.innerHTML = displayChannels.map(ch => {
    const epg = iptv.getNowPlaying(ch.epgId, ch.name);
    const logo = ch.logo ? `<img src="${ch.logo}" class="epg-card-logo" onerror="this.outerHTML='<div class=\\'epg-card-logo\\'>${ch.name.slice(0,2).toUpperCase()}</div>'">` :
      `<div class="epg-card-logo" style="display:flex;align-items:center;justify-content:center;font-family:var(--font-display);font-weight:700;font-size:0.7rem;color:var(--text-hint);">${ch.name.slice(0,2).toUpperCase()}</div>`;
    return `
    <div class="epg-card" data-channel-id="${ch.id}">
      <div class="epg-card-channel">
        ${logo}
        <span class="epg-card-name">${escHtml(ch.name)}</span>
      </div>
      <div class="epg-card-program">${epg ? escHtml(epg.title) : 'Live'}</div>
      <div class="epg-card-time">${epg ? formatTimeRange(epg.start, epg.stop) : ''}</div>
      ${epg ? `<div class="epg-card-bar"><div class="epg-card-bar-fill" style="width:${epg.progress}%"></div></div>` : ''}
    </div>`;
  }).join('');

  row.querySelectorAll('.epg-card').forEach(card => {
    card.addEventListener('click', () => {
      navigateTo('livetv');
      setTimeout(() => playChannel(card.dataset.channelId), 300);
    });
  });
}

// ─── Favorite Channels (real) ───
function buildFavoriteChannels() {
  const favIds = favorites.getAll();
  const section = document.getElementById('favorites-section');
  if (favIds.length === 0 || iptv.channels.length === 0) { section.style.display = 'none'; return; }

  const favChannels = favIds.map(id => iptv.channels.find(c => c.id === id)).filter(Boolean);
  if (favChannels.length === 0) { section.style.display = 'none'; return; }
  section.style.display = '';

  const row = document.getElementById('favorites-row');
  row.innerHTML = favChannels.map(ch => `
    <div class="channel-card" data-channel-id="${ch.id}">
      <div class="channel-card-logo">${ch.logo ? `<img src="${ch.logo}" onerror="this.parentElement.textContent='${ch.name.slice(0,3).toUpperCase()}'">` : ch.name.slice(0,3).toUpperCase()}</div>
      <span class="channel-card-name">${escHtml(ch.name)}</span>
      <span class="channel-card-num">CH ${ch.num}</span>
    </div>
  `).join('');

  row.querySelectorAll('.channel-card').forEach(card => {
    card.addEventListener('click', () => {
      navigateTo('livetv');
      setTimeout(() => playChannel(card.dataset.channelId), 300);
    });
  });
}

// ═══════════════════════════════════════════
// LIVE TV VIEW
// ═══════════════════════════════════════════
let liveTvInitialized = false;
let liveTvFilteredChannels = [];

function initLiveTv() {
  if (!liveTvInitialized) {
    liveTvPlayer = new VCPlayer('livetv-player-container');
    liveTvPlayer.onBack(() => navigateTo('home'));
    liveTvPlayer.onChannelChange = (dir) => {
      const idx = liveTvFilteredChannels.findIndex(c => c.id === activeChannelId);
      const next = liveTvFilteredChannels[idx + dir];
      if (next) playChannel(next.id);
    };
    liveTvInitialized = true;
  }

  buildCategoryChips('livetv-categories', iptv.categories, (catId) => {
    filterLiveTvChannels(catId);
  });
  liveTvFilteredChannels = iptv.channels;
  renderChannelList(iptv.channels);

  // Search
  document.getElementById('livetv-search').value = '';
  document.getElementById('livetv-search').oninput = debounce((e) => {
    const q = e.target.value.trim();
    liveTvFilteredChannels = iptv.searchChannels(q);
    renderChannelList(liveTvFilteredChannels);
  }, 250);

  // Show placeholder or resume
  if (activeChannelId) {
    document.getElementById('livetv-placeholder').style.display = 'none';
  } else {
    document.getElementById('livetv-placeholder').style.display = '';
  }
}

function filterLiveTvChannels(catId) {
  liveTvFilteredChannels = iptv.getChannelsByCategory(catId);
  renderChannelList(liveTvFilteredChannels);
}

function renderChannelList(channels) {
  const list = document.getElementById('livetv-channel-list');
  const count = document.getElementById('livetv-channel-count');
  count.textContent = `${channels.length} channels`;

  list.innerHTML = channels.map(ch => {
    const isFav = favorites.has(ch.id);
    const isActive = ch.id === activeChannelId;
    const epg = iptv.getNowPlaying(ch.epgId, ch.name);
    return `
    <div class="channel-item ${isActive ? 'active' : ''}" data-id="${ch.id}">
      <div class="channel-item-logo">
        ${ch.logo ? `<img src="${ch.logo}" onerror="this.parentElement.textContent='${ch.num}'">` : ch.num}
      </div>
      <div class="channel-item-info">
        <div class="channel-item-name">${escHtml(ch.name)}</div>
        <div class="channel-item-epg">${epg ? escHtml(epg.title) : ''}</div>
      </div>
      <button class="channel-item-fav ${isFav ? 'is-fav' : ''}" data-id="${ch.id}" title="Favorite">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="${isFav ? 'var(--gold)' : 'none'}" stroke="${isFav ? 'var(--gold)' : 'var(--text-hint)'}" stroke-width="2">
          <path d="M20.84 4.61a5.5 5.5 0 00-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 00-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 000-7.78z"/>
        </svg>
      </button>
    </div>`;
  }).join('');

  // Bind clicks
  list.querySelectorAll('.channel-item').forEach(item => {
    item.addEventListener('click', (e) => {
      if (e.target.closest('.channel-item-fav')) return;
      playChannel(item.dataset.id);
    });
  });

  // Bind favorites
  list.querySelectorAll('.channel-item-fav').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const id = btn.dataset.id;
      const isFav = favorites.toggle(id);
      btn.classList.toggle('is-fav', isFav);
      const svg = btn.querySelector('svg');
      svg.setAttribute('fill', isFav ? 'var(--gold)' : 'none');
      svg.setAttribute('stroke', isFav ? 'var(--gold)' : 'var(--text-hint)');
    });
  });
}

function playChannel(channelId) {
  const ch = iptv.channels.find(c => c.id === channelId);
  if (!ch) return;

  activeChannelId = channelId;
  recentChannels.add(channelId);

  const url = iptv.getLiveStreamUrl(ch.id);
  document.getElementById('livetv-placeholder').style.display = 'none';

  liveTvPlayer.play(url, ch.name, { id: ch.id, type: 'channel', logo: ch.logo, poster: ch.logo });

  const epg = iptv.getNowPlaying(ch.epgId, ch.name);
  if (epg) liveTvPlayer.setNowPlaying(epg.title);

  // Highlight active in list
  document.querySelectorAll('.channel-item').forEach(item => {
    item.classList.toggle('active', item.dataset.id === channelId);
  });
}

// ═══════════════════════════════════════════
// MOVIES VIEW
// ═══════════════════════════════════════════
function initMovies() {
  moviesPage = 0;
  moviesCategory = 'all';
  buildCategoryChips('movies-categories', iptv.movieCategories, (catId) => {
    moviesCategory = catId;
    moviesPage = 0;
    renderMovies();
  });

  document.getElementById('movies-search').value = '';
  document.getElementById('movies-search').oninput = debounce((e) => {
    const q = e.target.value.trim();
    renderMovies(q);
  }, 300);

  document.getElementById('surprise-btn').onclick = surpriseMeMovie;

  renderMoviesDiscovery();
}

// Default Movies view: stacked rows of curated shelves. No paging, no wall of grid.
function renderMoviesDiscovery() {
  document.getElementById('movies-discover').style.display = '';
  document.getElementById('movies-grid').style.display = 'none';
  document.getElementById('movies-empty').style.display = 'none';
  document.getElementById('movies-load-more').style.display = 'none';

  const rowsEl = document.getElementById('movies-discover-rows');
  const shelves = [];

  // 1. Continue Watching (movies only) — most-likely next click.
  const cw = (watchHistory.getContinueWatching() || []).filter(i => i.type === 'movie');
  if (cw.length) shelves.push({ title: 'Continue Watching', items: cw, idAttr: i => i.id, type: 'movie' });

  // 2. Recommendation rows. Order: top-rated, recent, then moods.
  const top = iptv.getTopRatedMovies(20);
  if (top.length) shelves.push({ title: 'Highly Rated', items: top, type: 'movie' });

  const fresh = iptv.getRecentlyAddedMovies(20);
  if (fresh.length) shelves.push({ title: 'Just Added', items: fresh, type: 'movie' });

  for (const mood of iptv.getMoodList()) {
    const items = iptv.getMoviesByMood(mood.key, 20);
    if (items.length >= 4) {
      shelves.push({ title: `${mood.icon}  ${mood.label}`, items, type: 'movie' });
    }
  }

  // 3. Decade rows last — only if titles in the catalog have year suffixes.
  for (const decade of [1980, 1970, 1960, 1990, 2000]) {
    const items = iptv.getMoviesByDecade(decade, 20);
    if (items.length >= 4) {
      shelves.push({ title: `From the ${String(decade).slice(2)}s`, items, type: 'movie' });
    }
  }

  if (shelves.length === 0) {
    // Fall back to the flat grid if nothing matched the discovery heuristics.
    renderMoviesGrid('');
    return;
  }

  rowsEl.innerHTML = shelves.map(renderShelfHtml).join('');
  rowsEl.querySelectorAll('.discover-row').forEach(row => bindVodCards(row, 'movie'));
}

function renderShelfHtml(shelf) {
  return `
    <section class="discover-shelf">
      <h3 class="discover-shelf-title">${escHtml(shelf.title)}</h3>
      <div class="discover-row">
        ${shelf.items.map(item => posterCardHtml(item, shelf.type)).join('')}
      </div>
    </section>`;
}

function posterCardHtml(item, type) {
  return `
    <div class="vod-card poster-card" data-id="${item.id}" data-type="${type}">
      <div class="vod-card-poster">
        ${item.poster ? `<img src="${item.poster}" loading="lazy" onerror="this.remove()">` : ''}
        <div class="vod-card-overlay">
          <svg width="36" height="36" viewBox="0 0 24 24" fill="white" opacity="0.9"><polygon points="5,3 19,12 5,21"/></svg>
        </div>
      </div>
      <div class="vod-card-title">${escHtml(item.name)}</div>
      ${item.rating ? `<div class="vod-card-rating">★ ${item.rating}</div>` : ''}
    </div>`;
}

function surpriseMeMovie() {
  const seen = (watchHistory.getRecent(50) || []).filter(i => i.type === 'movie').map(i => i.id);
  const preferIds = [...new Set((watchHistory.getRecent(20) || [])
    .filter(i => i.type === 'movie')
    .map(i => {
      const m = iptv.movies.find(x => x.id === i.id);
      return m && m.categoryId;
    })
    .filter(Boolean))];
  const pick = iptv.getRandomMovie({ excludeIds: seen, preferCategoryIds: preferIds });
  if (!pick) return;
  navigateTo('detail', { item: pick, type: 'movie', title: pick.name });
}

// Drill-down view: triggered by category chip or search box.
function renderMovies(searchQuery = '') {
  const filtered = !!searchQuery || (moviesCategory && moviesCategory !== 'all');
  if (!filtered) { renderMoviesDiscovery(); return; }
  renderMoviesGrid(searchQuery);
}

function renderMoviesGrid(searchQuery) {
  document.getElementById('movies-discover').style.display = 'none';
  const grid = document.getElementById('movies-grid');
  const empty = document.getElementById('movies-empty');
  const loadMore = document.getElementById('movies-load-more');
  grid.style.display = '';

  let items = searchQuery ? iptv.searchMovies(searchQuery) : iptv.getMoviesByCategory(moviesCategory);

  if (items.length === 0) {
    grid.innerHTML = '';
    empty.style.display = '';
    loadMore.style.display = 'none';
    return;
  }
  empty.style.display = 'none';

  const end = (moviesPage + 1) * vodPageSize;
  const visible = items.slice(0, end);
  loadMore.style.display = end < items.length ? '' : 'none';
  loadMore.onclick = () => { moviesPage++; renderMoviesGrid(searchQuery); };

  grid.innerHTML = visible.map(m => vodCard(m, 'movie')).join('');
  bindVodCards(grid, 'movie');
}

// ═══════════════════════════════════════════
// TV SHOWS VIEW
// ═══════════════════════════════════════════
function initTvShows() {
  tvshowsPage = 0;
  tvshowsCategory = 'all';
  buildCategoryChips('tvshows-categories', iptv.seriesCategories, (catId) => {
    tvshowsCategory = catId;
    tvshowsPage = 0;
    renderTvShows();
  });
  renderTvShows();

  document.getElementById('tvshows-search').value = '';
  document.getElementById('tvshows-search').oninput = debounce((e) => {
    renderTvShows(e.target.value.trim());
  }, 300);
}

function renderTvShows(searchQuery = '') {
  let items = searchQuery ? iptv.searchSeries(searchQuery) : iptv.getSeriesByCategory(tvshowsCategory);
  const grid = document.getElementById('tvshows-grid');
  const empty = document.getElementById('tvshows-empty');
  const loadMore = document.getElementById('tvshows-load-more');

  if (items.length === 0) {
    grid.innerHTML = '';
    empty.style.display = '';
    loadMore.style.display = 'none';
    return;
  }
  empty.style.display = 'none';

  const end = (tvshowsPage + 1) * vodPageSize;
  const visible = items.slice(0, end);
  loadMore.style.display = end < items.length ? '' : 'none';
  loadMore.onclick = () => { tvshowsPage++; renderTvShows(searchQuery); };

  grid.innerHTML = visible.map(s => vodCard(s, 'series')).join('');
  bindVodCards(grid, 'series');
}

// ═══════════════════════════════════════════
// KIDS VIEW
// ═══════════════════════════════════════════
const KIDS_KEYWORDS = ['kids', 'children', 'cartoon', 'animation', 'anime', 'disney', 'nickelodeon', 'nick', 'baby', 'junior', 'jr', 'family', 'toon', 'pbs'];

function isKidsContent(item) {
  const name = (item.name || '').toLowerCase();
  const cat = (item.category || '').toLowerCase();
  return KIDS_KEYWORDS.some(kw => name.includes(kw) || cat.includes(kw));
}

function initKids() {
  const allKids = [...iptv.movies.filter(isKidsContent), ...iptv.series.filter(isKidsContent)];
  const cats = [...new Set(allKids.map(i => i.category))].map(name => ({
    id: name, name, count: allKids.filter(i => i.category === name).length
  }));

  buildCategoryChips('kids-categories', cats, (catId) => {
    kidsCategory = catId;
    renderKids();
  });
  kidsCategory = 'all';
  renderKids();

  document.getElementById('kids-search').value = '';
  document.getElementById('kids-search').oninput = debounce((e) => {
    renderKids(e.target.value.trim());
  }, 300);
}

function renderKids(searchQuery = '') {
  let items = [...iptv.movies.filter(isKidsContent), ...iptv.series.filter(isKidsContent)];
  if (kidsCategory !== 'all') items = items.filter(i => i.categoryId === kidsCategory || i.category === kidsCategory);
  if (searchQuery) {
    const q = searchQuery.toLowerCase();
    items = items.filter(i => i.name.toLowerCase().includes(q));
  }

  const grid = document.getElementById('kids-grid');
  const empty = document.getElementById('kids-empty');
  if (items.length === 0) { grid.innerHTML = ''; empty.style.display = ''; return; }
  empty.style.display = 'none';

  grid.innerHTML = items.slice(0, 80).map(i => {
    const type = iptv.movies.includes(i) ? 'movie' : 'series';
    return vodCard(i, type);
  }).join('');
  bindVodCards(grid, null); // type from data-attribute
}

// ═══════════════════════════════════════════
// DETAIL VIEW
// ═══════════════════════════════════════════
function initDetail(params) {
  const { item, type } = params;
  currentDetailItem = item;
  currentDetailType = type;

  // Hide player
  document.getElementById('detail-player-wrap').style.display = 'none';

  // Backdrop
  const backdrop = document.getElementById('detail-backdrop');
  backdrop.style.backgroundImage = item.backdrop ? `url('${item.backdrop}')` : item.poster ? `url('${item.poster}')` : 'none';

  // Poster
  const poster = document.getElementById('detail-poster');
  poster.src = item.poster || '';
  poster.onerror = () => { poster.style.display = 'none'; };
  poster.style.display = item.poster ? '' : 'none';

  // Title + baseline info from catalog entry
  document.getElementById('detail-title').textContent = item.name;
  document.getElementById('detail-tagline').style.display = 'none';
  document.getElementById('detail-tagline').textContent = '';
  renderDetailBadges({ rating: item.rating, mpaa: '', duration: '', year: item.year });
  renderDetailMeta({ year: item.year, genre: item.category, country: '', director: '' });
  document.getElementById('detail-plot').textContent = item.plot || 'Loading details…';
  document.getElementById('detail-facts').innerHTML = '';
  document.getElementById('detail-cast-section').style.display = 'none';
  document.getElementById('detail-cast-row').innerHTML = '';
  document.getElementById('detail-trailer').style.display = 'none';

  // Favorite
  const favBtn = document.getElementById('detail-fav');
  const isFav = favorites.has(item.id);
  favBtn.querySelector('svg').setAttribute('fill', isFav ? 'var(--gold)' : 'none');
  favBtn.onclick = () => {
    const nowFav = favorites.toggle(item.id);
    favBtn.querySelector('svg').setAttribute('fill', nowFav ? 'var(--gold)' : 'none');
  };

  // Play button
  document.getElementById('detail-play').onclick = () => {
    if (type === 'movie') playMovie(item);
  };

  // Seasons (for series)
  const seasonsEl = document.getElementById('detail-seasons');
  if (type === 'series') {
    seasonsEl.style.display = '';
    document.getElementById('detail-play').style.display = 'none';
    loadSeriesSeasons(item.id);
  } else {
    seasonsEl.style.display = 'none';
    document.getElementById('detail-play').style.display = '';
  }

  // Fetch rich details (async — Xtream + optional TMDB for cast photos)
  if (type === 'movie') {
    iptv.getMovieInfo(item.id)
      .then(info => info && applyDetail(info, 'movie'))
      .catch(() => {});
  } else if (type === 'series') {
    iptv.getSeriesInfo(item.id)
      .then(data => data?.info && applyDetail(data.info, 'tv'))
      .catch(() => {});
  }
}

function applyDetail(info, tmdbType) {
  if (!info) return;

  // Upgrade backdrop/poster with richer TMDB-derived assets if available
  if (info.backdrop) {
    document.getElementById('detail-backdrop').style.backgroundImage = `url('${info.backdrop}')`;
  }
  if (info.poster) {
    const poster = document.getElementById('detail-poster');
    if (!poster.src || poster.src.endsWith('/')) {
      poster.src = info.poster;
      poster.style.display = '';
    }
  }

  // Tagline
  const taglineEl = document.getElementById('detail-tagline');
  if (info.tagline) {
    taglineEl.textContent = `"${info.tagline}"`;
    taglineEl.style.display = '';
  }

  // Plot
  if (info.plot) document.getElementById('detail-plot').textContent = info.plot;
  else if (!currentDetailItem.plot) document.getElementById('detail-plot').textContent = '';

  // Badges + meta line + facts grid
  renderDetailBadges(info);
  renderDetailMeta(info);
  renderDetailFacts(info);

  // Trailer
  const trailerBtn = document.getElementById('detail-trailer');
  const trailerUrl = extractTrailerUrl(info.trailer);
  if (trailerUrl) {
    trailerBtn.style.display = '';
    trailerBtn.onclick = () => window.open(trailerUrl, '_blank', 'noopener');
  }

  // Cast: try TMDB credits first (photos), else parse Xtream's cast string
  renderCastFromString(info.cast);
  resolveTmdbCast(info, tmdbType);
}

function renderDetailBadges(info) {
  const el = document.getElementById('detail-badges');
  const parts = [];
  const rating = parseFloat(info.rating);
  if (!Number.isNaN(rating) && rating > 0) {
    const scaled = rating > 10 ? (rating / 10).toFixed(1) : rating.toFixed(1);
    parts.push(`<span class="badge badge-rating">★ ${scaled}</span>`);
  }
  if (info.mpaa) parts.push(`<span class="badge badge-mpaa">${escHtml(info.mpaa)}</span>`);
  const runtime = formatRuntime(info);
  if (runtime) parts.push(`<span class="badge">${runtime}</span>`);
  const year = (info.year || info.releaseDate || '').toString().slice(0, 4);
  if (year) parts.push(`<span class="badge">${escHtml(year)}</span>`);
  el.innerHTML = parts.join('');
}

function renderDetailMeta(info) {
  const parts = [];
  if (info.genre) parts.push(escHtml(info.genre));
  if (info.country) parts.push(escHtml(info.country));
  if (info.director) parts.push(`Dir. ${escHtml(info.director)}`);
  document.getElementById('detail-meta').innerHTML = parts.join(' <span class="dot">·</span> ');
}

function renderDetailFacts(info) {
  const rows = [];
  if (info.director) rows.push(['Director', info.director]);
  if (info.releaseDate || info.releasedate) rows.push(['Released', info.releaseDate || info.releasedate]);
  if (info.country) rows.push(['Country', info.country]);
  if (info.genre) rows.push(['Genre', info.genre]);
  if (info.episodeRunTime) rows.push(['Episode', `${info.episodeRunTime} min`]);
  const el = document.getElementById('detail-facts');
  el.innerHTML = rows.map(([k, v]) =>
    `<div class="fact"><span class="fact-key">${k}</span><span class="fact-val">${escHtml(String(v))}</span></div>`
  ).join('');
}

function formatRuntime(info) {
  if (info.duration && /^\d+:\d+/.test(info.duration)) {
    const [h, m] = info.duration.split(':').map(Number);
    if (h) return `${h}h ${m || 0}m`;
    return `${m}m`;
  }
  if (info.durationSecs && Number(info.durationSecs) > 0) {
    const total = Number(info.durationSecs);
    const h = Math.floor(total / 3600);
    const m = Math.round((total % 3600) / 60);
    return h ? `${h}h ${m}m` : `${m}m`;
  }
  return '';
}

function extractTrailerUrl(raw) {
  if (!raw) return '';
  if (/^https?:\/\//i.test(raw)) return raw;
  // Bare YouTube IDs are common in Xtream responses
  return `https://www.youtube.com/watch?v=${encodeURIComponent(raw)}`;
}

function renderCastFromString(castStr) {
  if (!castStr) return;
  const section = document.getElementById('detail-cast-section');
  const row = document.getElementById('detail-cast-row');
  const names = castStr.split(/\s*,\s*/).map(s => s.trim()).filter(Boolean).slice(0, 12);
  if (!names.length) return;
  row.innerHTML = names.map(name => castCardHtml(name, '', '')).join('');
  section.style.display = '';
}

async function resolveTmdbCast(info, tmdbType) {
  try {
    let tmdbId = info.tmdbId || info.tmdb || info.tmdb_id;
    if (!tmdbId) {
      tmdbId = await iptv.searchTmdb(currentDetailItem?.name, info.year, tmdbType);
    }
    if (!tmdbId) return;
    const cast = await iptv.getTmdbCredits(tmdbId, tmdbType);
    if (!cast || !cast.length) return;
    const section = document.getElementById('detail-cast-section');
    const row = document.getElementById('detail-cast-row');
    row.innerHTML = cast.map(c => castCardHtml(c.name, c.character, c.photo)).join('');
    section.style.display = '';
  } catch {
    // TMDB not configured or unavailable — fallback cast string already rendered
  }
}

function castCardHtml(name, character, photo) {
  const initials = name.split(/\s+/).map(s => s[0]).filter(Boolean).slice(0, 2).join('').toUpperCase();
  const avatar = photo
    ? `<img class="cast-photo" src="${escAttr(photo)}" alt="" loading="lazy" onerror="this.outerHTML='<div class=&quot;cast-photo cast-initials&quot;>${escHtml(initials)}</div>'">`
    : `<div class="cast-photo cast-initials">${escHtml(initials)}</div>`;
  return `
    <div class="cast-card">
      ${avatar}
      <div class="cast-name">${escHtml(name)}</div>
      ${character ? `<div class="cast-role">${escHtml(character)}</div>` : ''}
    </div>
  `;
}

function escAttr(s) {
  return String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

async function loadSeriesSeasons(seriesId) {
  const tabsEl = document.getElementById('detail-season-tabs');
  const epsEl = document.getElementById('detail-episodes');
  tabsEl.innerHTML = '<div class="vc-player-spinner" style="margin:1rem auto"></div>';
  epsEl.innerHTML = '';

  try {
    const data = await iptv.getSeriesInfo(seriesId);
    if (!data || !data.seasons) {
      tabsEl.innerHTML = '<p style="color:var(--text-hint)">No episodes found</p>';
      return;
    }

    const seasonNums = Object.keys(data.seasons).sort((a, b) => Number(a) - Number(b));
    tabsEl.innerHTML = seasonNums.map((num, i) =>
      `<button class="season-tab ${i === 0 ? 'active' : ''}" data-season="${num}">Season ${num}</button>`
    ).join('');

    const showEpisodes = (seasonNum) => {
      const eps = data.seasons[seasonNum] || [];
      epsEl.innerHTML = eps.map(ep => `
        <div class="episode-item" data-id="${ep.id}" data-ext="${ep.containerExtension}">
          <div class="episode-num">${ep.episodeNum}</div>
          <div class="episode-info">
            <div class="episode-title">${escHtml(ep.title)}</div>
            <div class="episode-meta">${ep.duration || ''}</div>
          </div>
          <button class="episode-play">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg>
          </button>
        </div>
      `).join('');

      epsEl.querySelectorAll('.episode-item').forEach(item => {
        item.addEventListener('click', () => {
          const epId = item.dataset.id;
          const ext = item.dataset.ext || 'm3u8';
          const url = iptv.getSeriesStreamUrl(epId, ext);
          playInDetailPlayer(url, item.querySelector('.episode-title').textContent);
        });
      });
    };

    showEpisodes(seasonNums[0]);
    tabsEl.querySelectorAll('.season-tab').forEach(tab => {
      tab.addEventListener('click', () => {
        tabsEl.querySelectorAll('.season-tab').forEach(t => t.classList.remove('active'));
        tab.classList.add('active');
        showEpisodes(tab.dataset.season);
      });
    });
  } catch (err) {
    tabsEl.innerHTML = '<p style="color:var(--text-hint)">Failed to load episodes</p>';
  }
}

function playMovie(movie) {
  const url = iptv.getMovieStreamUrl(movie.id, movie.containerExtension || 'mp4');
  playInDetailPlayer(url, movie.name);
}

function playInDetailPlayer(url, title) {
  const wrap = document.getElementById('detail-player-wrap');
  wrap.style.display = '';
  if (!detailPlayer) {
    detailPlayer = new VCPlayer('detail-player-container');
    detailPlayer.onBack(() => {
      wrap.style.display = 'none';
      detailPlayer.stop();
    });
  }
  detailPlayer.play(url, title, {
    id: currentDetailItem?.id,
    type: currentDetailType,
    poster: currentDetailItem?.poster,
    name: title
  });
  wrap.scrollIntoView({ behavior: 'smooth' });
}

// ═══════════════════════════════════════════
// GLOBAL SEARCH
// ═══════════════════════════════════════════
let searchTimeout = null;
document.getElementById('search-input').addEventListener('input', (e) => {
  clearTimeout(searchTimeout);
  const q = e.target.value.trim();
  if (q.length < 2) return;
  searchTimeout = setTimeout(() => {
    navigateTo('search', { query: q });
  }, 400);
});
document.getElementById('search-input').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    const q = e.target.value.trim();
    if (q.length >= 2) navigateTo('search', { query: q });
  }
});

function initSearchResults(query) {
  if (!query || !iptv.isLoaded) return;

  document.getElementById('search-results-title').textContent = `Results for "${query}"`;
  const results = iptv.searchAll(query);

  // Channels
  const chSection = document.getElementById('search-channels-section');
  if (results.channels.length > 0) {
    chSection.style.display = '';
    const row = document.getElementById('search-channels-row');
    row.innerHTML = results.channels.slice(0, 20).map(ch => `
      <div class="channel-card" data-channel-id="${ch.id}">
        <div class="channel-card-logo">${ch.logo ? `<img src="${ch.logo}" onerror="this.parentElement.textContent='${ch.name.slice(0,3)}'">` : ch.name.slice(0,3).toUpperCase()}</div>
        <span class="channel-card-name">${escHtml(ch.name)}</span>
        <span class="channel-card-num">CH ${ch.num}</span>
      </div>
    `).join('');
    row.querySelectorAll('.channel-card').forEach(card => {
      card.addEventListener('click', () => {
        navigateTo('livetv');
        setTimeout(() => playChannel(card.dataset.channelId), 300);
      });
    });
  } else { chSection.style.display = 'none'; }

  // Movies
  const mSection = document.getElementById('search-movies-section');
  if (results.movies.length > 0) {
    mSection.style.display = '';
    const grid = document.getElementById('search-movies-grid');
    grid.innerHTML = results.movies.slice(0, 30).map(m => vodCard(m, 'movie')).join('');
    bindVodCards(grid, 'movie');
  } else { mSection.style.display = 'none'; }

  // Series
  const sSection = document.getElementById('search-series-section');
  if (results.series.length > 0) {
    sSection.style.display = '';
    const grid = document.getElementById('search-series-grid');
    grid.innerHTML = results.series.slice(0, 30).map(s => vodCard(s, 'series')).join('');
    bindVodCards(grid, 'series');
  } else { sSection.style.display = 'none'; }

  // Empty
  const empty = document.getElementById('search-empty');
  empty.style.display = (results.channels.length + results.movies.length + results.series.length === 0) ? '' : 'none';
}

// ═══════════════════════════════════════════
// SHARED HELPERS
// ═══════════════════════════════════════════

function vodCard(item, type) {
  const typeAttr = type || (iptv.movies.some(m => m.id === item.id) ? 'movie' : 'series');
  return `
  <div class="vod-card" data-id="${item.id}" data-type="${typeAttr}">
    <div class="vod-card-poster">
      ${item.poster ? `<img src="${item.poster}" loading="lazy" onerror="this.remove()">` : ''}
      <div class="vod-card-overlay">
        <svg width="36" height="36" viewBox="0 0 24 24" fill="white" opacity="0.9"><polygon points="5,3 19,12 5,21"/></svg>
      </div>
    </div>
    <div class="vod-card-title">${escHtml(item.name)}</div>
    ${item.rating ? `<div class="vod-card-rating">★ ${item.rating}</div>` : ''}
  </div>`;
}

function bindVodCards(container, defaultType) {
  container.querySelectorAll('.vod-card').forEach(card => {
    card.addEventListener('click', () => {
      const id = card.dataset.id;
      const type = card.dataset.type || defaultType;
      let item;
      if (type === 'movie') item = iptv.movies.find(m => m.id === id);
      else item = iptv.series.find(s => s.id === id);
      if (item) navigateTo('detail', { item, type, title: item.name });
    });
  });
}

function buildCategoryChips(containerId, categories, onSelect) {
  const el = document.getElementById(containerId);
  if (!categories || categories.length === 0) { el.innerHTML = ''; return; }

  const sorted = [...categories].sort((a, b) => a.name.localeCompare(b.name));
  el.innerHTML = `<button class="cat-chip active" data-cat="all">All</button>` +
    sorted.map(c => `<button class="cat-chip" data-cat="${c.id}">${escHtml(c.name)} <span class="cat-count">${c.count || ''}</span></button>`).join('');

  el.querySelectorAll('.cat-chip').forEach(chip => {
    chip.addEventListener('click', () => {
      el.querySelectorAll('.cat-chip').forEach(c => c.classList.remove('active'));
      chip.classList.add('active');
      onSelect(chip.dataset.cat);
    });
  });
}

function formatTimeRange(start, stop) {
  if (!start || !stop) return '';
  const fmt = (ts) => {
    const d = new Date(ts);
    let h = d.getHours(); const m = String(d.getMinutes()).padStart(2, '0');
    const ampm = h >= 12 ? 'PM' : 'AM'; h = h % 12 || 12;
    return `${h}:${m} ${ampm}`;
  };
  return `${fmt(start)} - ${fmt(stop)}`;
}

function escHtml(str) {
  if (!str) return '';
  return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function debounce(fn, ms) {
  let timer;
  return function(...args) {
    clearTimeout(timer);
    timer = setTimeout(() => fn.apply(this, args), ms);
  };
}

function toast(msg, isError = false) {
  const el = document.getElementById('toast');
  el.textContent = msg;
  el.className = 'toast' + (isError ? ' toast-error' : '');
  el.hidden = false;
  setTimeout(() => { el.hidden = true; }, 4000);
}

// ─── Settings ───
function getSettings() {
  try { return JSON.parse(localStorage.getItem('vc_settings') || '{}'); } catch { return {}; }
}

function loadSettings() {
  const s = getSettings();
  if (s.m3u) document.getElementById('setting-m3u').value = s.m3u;
  if (s.epg) document.getElementById('setting-epg').value = s.epg;
  if (s.xtreamServer) document.getElementById('setting-xtream-server').value = s.xtreamServer;
  if (s.xtreamUser) document.getElementById('setting-xtream-user').value = s.xtreamUser;
  if (s.xtreamPass) document.getElementById('setting-xtream-pass').value = s.xtreamPass;
  if (s.showClock !== undefined) document.getElementById('setting-clock').checked = s.showClock;
  if (s.showGames !== undefined) document.getElementById('setting-games').checked = s.showGames;
}

document.getElementById('btn-settings').addEventListener('click', () => {
  document.getElementById('settings-modal').hidden = false;
});
document.getElementById('settings-close').addEventListener('click', () => {
  document.getElementById('settings-modal').hidden = true;
});
document.getElementById('settings-modal').addEventListener('click', e => {
  if (e.target === e.currentTarget) e.currentTarget.hidden = true;
});

document.getElementById('btn-save-settings').addEventListener('click', () => {
  const settings = {
    m3u: document.getElementById('setting-m3u').value,
    epg: document.getElementById('setting-epg').value,
    xtreamServer: document.getElementById('setting-xtream-server').value,
    xtreamUser: document.getElementById('setting-xtream-user').value,
    xtreamPass: document.getElementById('setting-xtream-pass').value,
    showClock: document.getElementById('setting-clock').checked,
    showGames: document.getElementById('setting-games').checked,
  };
  localStorage.setItem('vc_settings', JSON.stringify(settings));
  document.getElementById('settings-modal').hidden = true;

  // Re-init with new settings
  dashboardInitialized = false;
  liveTvInitialized = false;
  initDashboard();
});

document.getElementById('btn-refresh-catalog')?.addEventListener('click', async () => {
  const btn = document.getElementById('btn-refresh-catalog');
  const prevText = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Refreshing…';
  try {
    await iptv.refreshCatalog();
    toast(`Catalog refreshed: ${iptv.channels.length} channels, ${iptv.movies.length} movies, ${iptv.series.length} series`);
    buildHomeSections();
  } catch (err) {
    toast('Refresh failed: ' + err.message, true);
  } finally {
    btn.disabled = false;
    btn.textContent = prevText;
  }
});

document.getElementById('btn-logout').addEventListener('click', logout);
document.getElementById('user-pill').addEventListener('click', () => {
  document.getElementById('settings-modal').hidden = false;
});

// Setup prompt button
document.getElementById('btn-open-setup')?.addEventListener('click', () => {
  document.getElementById('settings-modal').hidden = false;
});

// ─── Init ───
if (isLoggedIn()) showScreen('dashboard');
else showScreen('login');

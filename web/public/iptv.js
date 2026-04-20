/* ═══════════════════════════════════════════
   VistaCore Web — IPTV Data Service
   Handles Xtream Codes API + M3U parsing
   ═══════════════════════════════════════════ */

// ─── Catalog cache (IndexedDB) ───
// localStorage caps ~5-10 MB; large VOD catalogs blow past that,
// so we use IndexedDB to persist channels/movies/series between sessions.
class CatalogCache {
  static DB_NAME = 'vistacore';
  static STORE   = 'catalog';
  static TTL_MS  = 24 * 60 * 60 * 1000; // 24h freshness

  static _db = null;

  static async _open() {
    if (this._db) return this._db;
    this._db = await new Promise((resolve, reject) => {
      const req = indexedDB.open(this.DB_NAME, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(this.STORE, { keyPath: 'key' });
      req.onsuccess = () => resolve(req.result);
      req.onerror   = () => reject(req.error);
    });
    return this._db;
  }

  static async get(key) {
    try {
      const db = await this._open();
      return await new Promise((resolve, reject) => {
        const req = db.transaction(this.STORE, 'readonly').objectStore(this.STORE).get(key);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror   = () => reject(req.error);
      });
    } catch { return null; }
  }

  static async put(key, data) {
    try {
      const db = await this._open();
      await new Promise((resolve, reject) => {
        const tx = db.transaction(this.STORE, 'readwrite');
        tx.objectStore(this.STORE).put({ key, savedAt: Date.now(), data });
        tx.oncomplete = () => resolve();
        tx.onerror    = () => reject(tx.error);
      });
    } catch {}
  }

  static async delete(key) {
    try {
      const db = await this._open();
      await new Promise((resolve, reject) => {
        const tx = db.transaction(this.STORE, 'readwrite');
        tx.objectStore(this.STORE).delete(key);
        tx.oncomplete = () => resolve();
        tx.onerror    = () => reject(tx.error);
      });
    } catch {}
  }

  static isStale(entry, ttl = this.TTL_MS) {
    return !entry || (Date.now() - entry.savedAt) > ttl;
  }
}

class IPTVService {
  constructor() {
    this.channels = [];
    this.categories = [];
    this.movies = [];
    this.movieCategories = [];
    this.series = [];
    this.seriesCategories = [];
    this.epgData = {};
    this.xtream = null; // { server, username, password }
    this.m3uUrl = null;
    this._loaded = false;
    this._listeners = [];
  }

  // ─── Event System ───
  on(event, fn) {
    this._listeners.push({ event, fn });
  }
  emit(event, data) {
    this._listeners.filter(l => l.event === event).forEach(l => l.fn(data));
  }

  // ─── Configuration ───
  configure(settings) {
    if (settings.xtreamServer && settings.xtreamUser && settings.xtreamPass) {
      this.xtream = {
        server: settings.xtreamServer.replace(/\/+$/, ''),
        username: settings.xtreamUser,
        password: settings.xtreamPass
      };
    }
    if (settings.m3u) this.m3uUrl = settings.m3u;
    if (settings.epg) this.epgUrl = settings.epg;
  }

  get isConfigured() {
    return !!(this.xtream || this.m3uUrl);
  }

  get isLoaded() {
    return this._loaded;
  }

  // ─── Xtream API helpers (proxied through worker) ───
  _xtreamApi(action, params = {}) {
    const url = new URL('/api/xtream', location.origin);
    url.searchParams.set('server', this.xtream.server);
    url.searchParams.set('username', this.xtream.username);
    url.searchParams.set('password', this.xtream.password);
    if (action) url.searchParams.set('action', action);
    for (const [k, v] of Object.entries(params)) {
      url.searchParams.set(k, v);
    }
    return fetch(url.toString())
      .then(r => {
        if (!r.ok) throw new Error(`API error: ${r.status}`);
        return r.json();
      });
  }

  // ─── Stream URLs ───
  // If the origin is HTTPS, return the URL directly so the browser gets
  // native Range support (seeking MP4s) and traffic bypasses our Worker.
  // Only HTTP origins go through the Worker proxy (to upgrade to HTTPS
  // and avoid browser mixed-content blocks).
  _proxyUrl(rawUrl) {
    if (!rawUrl) return rawUrl;
    if (String(rawUrl).startsWith('https://')) return rawUrl;
    return `/api/stream?url=${encodeURIComponent(rawUrl)}`;
  }

  // ─── Image URLs (proxied to bypass mixed-content + HTTP-only providers) ───
  _imageUrl(rawUrl) {
    if (!rawUrl) return '';
    const s = String(rawUrl);
    if (s.startsWith('/') || s.startsWith('data:')) return s;
    // Some providers return garbage like "https, https://host/path".
    // Extract the last valid http(s) URL in the string.
    const matches = s.match(/https?:\/\/[^\s,"']+/g);
    if (!matches || matches.length === 0) return '';
    const clean = matches[matches.length - 1];
    return `/api/image?url=${encodeURIComponent(clean)}`;
  }

  getLiveStreamUrl(streamId, ext = 'm3u8') {
    if (this.xtream) {
      const direct = `${this.xtream.server}/live/${this.xtream.username}/${this.xtream.password}/${streamId}.${ext}`;
      return this._proxyUrl(direct);
    }
    const ch = this.channels.find(c => c.id === streamId);
    return ch ? this._proxyUrl(ch.url) : null;
  }

  getMovieStreamUrl(streamId, ext = 'mp4') {
    if (this.xtream) {
      const direct = `${this.xtream.server}/movie/${this.xtream.username}/${this.xtream.password}/${streamId}.${ext}`;
      return this._proxyUrl(direct);
    }
    return null;
  }

  getSeriesStreamUrl(streamId, ext = 'm3u8') {
    if (this.xtream) {
      const direct = `${this.xtream.server}/series/${this.xtream.username}/${this.xtream.password}/${streamId}.${ext}`;
      return this._proxyUrl(direct);
    }
    return null;
  }

  // ─── Cache plumbing ───
  _cacheKey() {
    if (this.xtream) return `xtream:${this.xtream.server}|${this.xtream.username}`;
    if (this.m3uUrl) return `m3u:${this.m3uUrl}`;
    return null;
  }

  _snapshot() {
    return {
      channels:         this.channels,
      categories:       this.categories,
      movies:           this.movies,
      movieCategories:  this.movieCategories,
      series:           this.series,
      seriesCategories: this.seriesCategories,
    };
  }

  _hydrate(data) {
    this.channels         = data.channels         || [];
    this.categories       = data.categories       || [];
    this.movies           = data.movies           || [];
    this.movieCategories  = data.movieCategories  || [];
    this.series           = data.series           || [];
    this.seriesCategories = data.seriesCategories || [];
  }

  // ─── Load Everything ───
  async loadAll(opts = {}) {
    const { forceRefresh = false } = opts;
    this.emit('loading', { phase: 'Starting...' });

    try {
      const key = this._cacheKey();
      const cached = (key && !forceRefresh) ? await CatalogCache.get(key) : null;

      if (cached) {
        // Instant hydrate from cache — user sees content immediately.
        this._hydrate(cached.data);
        this._loaded = true;
        this.emit('loaded', {
          channels:  this.channels.length,
          movies:    this.movies.length,
          series:    this.series.length,
          fromCache: true,
          ageHours:  (Date.now() - cached.savedAt) / 3600000,
        });

        // Kick off a silent background refresh if the cache is stale.
        if (CatalogCache.isStale(cached)) this._backgroundRefresh(key);
        return;
      }

      // No cache (or forced) — do a full network load.
      if (this.xtream)      await this._loadXtream();
      else if (this.m3uUrl) await this._loadM3U();
      else throw new Error('No IPTV source configured');

      this._loaded = true;
      if (key) await CatalogCache.put(key, this._snapshot());

      this.emit('loaded', {
        channels:  this.channels.length,
        movies:    this.movies.length,
        series:    this.series.length,
        fromCache: false,
      });
    } catch (err) {
      this.emit('error', err);
      throw err;
    }
  }

  async _backgroundRefresh(key) {
    try {
      if (this.xtream)      await this._loadXtream();
      else if (this.m3uUrl) await this._loadM3U();
      else return;
      await CatalogCache.put(key, this._snapshot());
      this.emit('refreshed', {
        channels: this.channels.length,
        movies:   this.movies.length,
        series:   this.series.length,
      });
    } catch (err) {
      console.warn('Background catalog refresh failed:', err);
    }
  }

  async refreshCatalog() {
    const key = this._cacheKey();
    if (key) await CatalogCache.delete(key);
    return this.loadAll({ forceRefresh: true });
  }

  // ─── Xtream: Load All Data ───
  async _loadXtream() {
    // Authenticate first
    this.emit('loading', { phase: 'Authenticating...' });
    const auth = await this._xtreamApi(null);
    if (!auth.user_info) throw new Error('Authentication failed');

    // Load categories + streams in parallel
    this.emit('loading', { phase: 'Loading channels...' });
    const [liveCats, liveStreams, vodCats, vodStreams, seriesCats, seriesData] = await Promise.all([
      this._xtreamApi('get_live_categories'),
      this._xtreamApi('get_live_streams'),
      this._xtreamApi('get_vod_categories'),
      this._xtreamApi('get_vod_streams'),
      this._xtreamApi('get_series_categories'),
      this._xtreamApi('get_series'),
    ]);

    // Parse live channels
    this.categories = (liveCats || []).map(c => ({
      id: String(c.category_id),
      name: c.category_name,
      count: 0
    }));

    this.channels = (liveStreams || []).map((s, i) => {
      const cat = this.categories.find(c => c.id === String(s.category_id));
      if (cat) cat.count++;
      return {
        id: String(s.stream_id),
        num: s.num || i + 1,
        name: s.name || 'Unknown',
        logo: this._imageUrl(s.stream_icon),
        category: cat ? cat.name : 'Uncategorized',
        categoryId: String(s.category_id || ''),
        epgId: s.epg_channel_id || '',
        url: null, // Built on demand
        added: s.added || 0
      };
    });

    // Parse movies
    this.movieCategories = (vodCats || []).map(c => ({
      id: String(c.category_id),
      name: c.category_name,
      count: 0
    }));

    this.movies = (vodStreams || []).map(m => {
      const cat = this.movieCategories.find(c => c.id === String(m.category_id));
      if (cat) cat.count++;
      return {
        id: String(m.stream_id),
        name: m.name || 'Unknown',
        poster: this._imageUrl(m.stream_icon),
        category: cat ? cat.name : 'Uncategorized',
        categoryId: String(m.category_id || ''),
        rating: m.rating || '',
        added: m.added || 0,
        containerExtension: m.container_extension || 'mp4',
        plot: '',
        cast: '',
        year: ''
      };
    });

    // Parse series
    this.seriesCategories = (seriesCats || []).map(c => ({
      id: String(c.category_id),
      name: c.category_name,
      count: 0
    }));

    this.series = (seriesData || []).map(s => {
      const cat = this.seriesCategories.find(c => c.id === String(s.category_id));
      if (cat) cat.count++;
      return {
        id: String(s.series_id),
        name: s.name || 'Unknown',
        poster: this._imageUrl(s.cover),
        category: cat ? cat.name : 'Uncategorized',
        categoryId: String(s.category_id || ''),
        rating: s.rating || '',
        plot: s.plot || '',
        cast: s.cast || '',
        year: s.year || '',
        backdrop: s.backdrop_path ? this._imageUrl(s.backdrop_path[0]) : ''
      };
    });

    this.emit('loading', { phase: `Loaded ${this.channels.length} channels, ${this.movies.length} movies, ${this.series.length} series` });
  }

  // ─── Load Series Info (seasons/episodes) ───
  async getSeriesInfo(seriesId) {
    if (!this.xtream) return null;
    const data = await this._xtreamApi('get_series_info', { series_id: seriesId });
    if (!data) return null;

    const seasons = {};
    const episodes = data.episodes || {};
    for (const [seasonNum, eps] of Object.entries(episodes)) {
      seasons[seasonNum] = (eps || []).map(ep => ({
        id: String(ep.id),
        episodeNum: ep.episode_num || 0,
        title: ep.title || `Episode ${ep.episode_num}`,
        containerExtension: ep.container_extension || 'm3u8',
        duration: ep.info?.duration || '',
        plot: ep.info?.plot || '',
        poster: this._imageUrl(ep.info?.movie_image),
        rating: ep.info?.rating || ''
      }));
    }

    const info = data.info || {};
    const firstBackdrop = Array.isArray(info.backdrop_path) ? info.backdrop_path[0] : info.backdrop_path;
    return {
      info: {
        ...info,
        plot: info.plot || info.description || '',
        cast: info.cast || '',
        director: info.director || '',
        genre: info.genre || '',
        country: info.country || '',
        releaseDate: info.releaseDate || info.release_date || '',
        year: (info.releaseDate || info.release_date || '').slice(0, 4),
        rating: info.rating || '',
        ratingFive: info.rating_5based || '',
        mpaa: info.mpaa_rating || info.age || '',
        episodeRunTime: info.episode_run_time || '',
        trailer: info.youtube_trailer || '',
        tmdbId: info.tmdb || info.tmdb_id || '',
        poster: this._imageUrl(info.cover || info.movie_image),
        backdrop: this._imageUrl(firstBackdrop),
      },
      seasons
    };
  }

  // ─── Load Movie Info ───
  async getMovieInfo(movieId) {
    if (!this.xtream) return null;
    try {
      const data = await this._xtreamApi('get_vod_info', { vod_id: movieId });
      if (!data || !data.info) return null;
      const info = data.info;
      const firstBackdrop = Array.isArray(info.backdrop_path) ? info.backdrop_path[0] : info.backdrop_path;
      return {
        ...info,
        plot: info.plot || info.description || '',
        cast: info.cast || '',
        director: info.director || '',
        genre: info.genre || '',
        country: info.country || '',
        duration: info.duration || '',
        durationSecs: info.duration_secs || 0,
        year: info.releasedate || info.year || '',
        rating: info.rating || '',
        ratingFive: info.rating_5based || '',
        mpaa: info.mpaa_rating || info.age || '',
        trailer: info.youtube_trailer || '',
        tagline: info.tagline || '',
        tmdbId: info.tmdb_id || info.tmdb || '',
        poster: this._imageUrl(info.movie_image || info.stream_icon),
        backdrop: this._imageUrl(firstBackdrop),
      };
    } catch {
      return null;
    }
  }

  // ─── TMDB: fetch rich credits (cast with profile photos) ───
  async getTmdbCredits(tmdbId, type = 'movie') {
    if (!tmdbId) return null;
    try {
      const resp = await fetch(`/api/tmdb?path=${encodeURIComponent(type + '/' + tmdbId + '/credits')}`);
      if (!resp.ok) return null;
      const data = await resp.json();
      const cast = (data.cast || []).slice(0, 12).map(c => ({
        name: c.name || '',
        character: c.character || '',
        photo: c.profile_path ? `https://image.tmdb.org/t/p/w300${c.profile_path}` : ''
      }));
      return cast.length ? cast : null;
    } catch {
      return null;
    }
  }

  // ─── TMDB: search by title when tmdb_id is unknown ───
  async searchTmdb(title, year, type = 'movie') {
    if (!title) return null;
    try {
      const q = new URLSearchParams({ query: title });
      if (year) q.set('year', String(year).slice(0, 4));
      const resp = await fetch(`/api/tmdb?path=${encodeURIComponent('search/' + type)}&${q.toString()}`);
      if (!resp.ok) return null;
      const data = await resp.json();
      return (data.results && data.results[0]) ? data.results[0].id : null;
    } catch {
      return null;
    }
  }

  // ─── M3U Parser ───
  async _loadM3U() {
    this.emit('loading', { phase: 'Downloading playlist...' });
    const resp = await fetch(`/api/m3u?url=${encodeURIComponent(this.m3uUrl)}`);
    if (!resp.ok) throw new Error(`Failed to fetch M3U: ${resp.status}`);
    const text = await resp.text();

    // Reset — this loader appends, so a refresh over existing data
    // would otherwise duplicate every entry.
    this.channels = [];
    this.movies   = [];
    this.series   = [];

    this.emit('loading', { phase: 'Parsing playlist...' });
    const lines = text.split('\n');
    const categorySet = new Map();
    let currentInfo = null;

    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();

      if (line.startsWith('#EXTINF:')) {
        // Parse metadata
        const tvgName = this._extractAttr(line, 'tvg-name');
        const tvgLogo = this._extractAttr(line, 'tvg-logo');
        const tvgId = this._extractAttr(line, 'tvg-id');
        const group = this._extractAttr(line, 'group-title') || 'Uncategorized';
        // Display name is after the last comma
        const commaIdx = line.lastIndexOf(',');
        const displayName = commaIdx >= 0 ? line.substring(commaIdx + 1).trim() : tvgName || 'Unknown';

        currentInfo = {
          name: displayName || tvgName || 'Unknown',
          logo: tvgLogo || '',
          epgId: tvgId || '',
          category: group
        };
      } else if (line && !line.startsWith('#') && currentInfo) {
        // This is the URL line
        const category = currentInfo.category;
        if (!categorySet.has(category)) {
          categorySet.set(category, { id: category, name: category, count: 0 });
        }
        categorySet.get(category).count++;

        // Determine content type from URL and group
        const lowerUrl = line.toLowerCase();
        const lowerGroup = category.toLowerCase();
        let contentType = 'live';
        if (lowerUrl.includes('/movie/') || lowerGroup.includes('movie') || lowerGroup.includes('film')) {
          contentType = 'movie';
        } else if (lowerUrl.includes('/series/') || lowerGroup.includes('series') || lowerGroup.includes('episode')) {
          contentType = 'series';
        }

        const entry = {
          id: `m3u_${this.channels.length + this.movies.length}`,
          num: this.channels.length + 1,
          name: currentInfo.name,
          logo: this._imageUrl(currentInfo.logo),
          category: category,
          categoryId: category,
          epgId: currentInfo.epgId,
          url: line,
          added: 0
        };

        if (contentType === 'movie') {
          this.movies.push({
            ...entry,
            poster: entry.logo,
            containerExtension: this._getExtension(line),
            rating: '', plot: '', cast: '', year: ''
          });
        } else if (contentType === 'series') {
          // For M3U, series episodes are individual entries
          this.channels.push(entry); // Treat as channels for simplicity
        } else {
          this.channels.push(entry);
        }

        currentInfo = null;
      }
    }

    this.categories = Array.from(categorySet.values());
    this.movieCategories = [...new Set(this.movies.map(m => m.category))].map(name => ({
      id: name, name, count: this.movies.filter(m => m.category === name).length
    }));
  }

  _extractAttr(line, attr) {
    const regex = new RegExp(`${attr}="([^"]*)"`, 'i');
    const match = line.match(regex);
    return match ? match[1] : '';
  }

  _getExtension(url) {
    try {
      const path = new URL(url).pathname;
      const ext = path.split('.').pop().toLowerCase();
      return ['mp4', 'mkv', 'avi', 'ts', 'm3u8'].includes(ext) ? ext : 'mp4';
    } catch {
      return 'mp4';
    }
  }

  // ─── Search ───
  searchChannels(query) {
    if (!query || query.length < 2) return this.channels;
    const q = query.toLowerCase();
    return this.channels.filter(c =>
      c.name.toLowerCase().includes(q) ||
      c.category.toLowerCase().includes(q)
    );
  }

  searchMovies(query) {
    if (!query || query.length < 2) return this.movies;
    const q = query.toLowerCase();
    return this.movies.filter(m =>
      m.name.toLowerCase().includes(q) ||
      m.category.toLowerCase().includes(q)
    );
  }

  searchSeries(query) {
    if (!query || query.length < 2) return this.series;
    const q = query.toLowerCase();
    return this.series.filter(s =>
      s.name.toLowerCase().includes(q) ||
      s.category.toLowerCase().includes(q)
    );
  }

  searchAll(query) {
    return {
      channels: this.searchChannels(query),
      movies: this.searchMovies(query),
      series: this.searchSeries(query)
    };
  }

  // ─── Filters ───
  getChannelsByCategory(categoryId) {
    if (!categoryId || categoryId === 'all') return this.channels;
    return this.channels.filter(c => c.categoryId === categoryId);
  }

  getMoviesByCategory(categoryId) {
    if (!categoryId || categoryId === 'all') return this.movies;
    return this.movies.filter(m => m.categoryId === categoryId);
  }

  getSeriesByCategory(categoryId) {
    if (!categoryId || categoryId === 'all') return this.series;
    return this.series.filter(s => s.categoryId === categoryId);
  }

  // ─── Discovery: mood / decade / rating shelves ───
  // Mood keywords match against category name first (Xtream catalogs encode
  // genre there, e.g. "EN | MOVIES | ACTION"), then fall back to title.
  static MOODS = {
    'feel-good':  { label: 'Feel-Good',   icon: '☀',  cat: /comed|family|musical|romance|feel.?good/i, title: /comedy|musical/i },
    'funny':      { label: 'Funny',       icon: '😄', cat: /comed|sitcom|stand.?up/i },
    'action':     { label: 'Action',      icon: '💥', cat: /action|advent/i },
    'true-story': { label: 'True Story',  icon: '📖', cat: /document|biograph|true.?story|history/i },
    'romance':    { label: 'Romance',     icon: '❤', cat: /roman/i },
    'western':    { label: 'Western',     icon: '🤠', cat: /western|cowboy/i },
    'thriller':   { label: 'Edge of Seat',icon: '🔥', cat: /thrill|mystery|crime/i },
    'classic':    { label: 'Classics',    icon: '🎬', cat: /classic|old|vintage|golden.?age/i },
  };

  getMoodList() {
    return Object.entries(IPTVService.MOODS).map(([key, m]) => ({ key, label: m.label, icon: m.icon }));
  }

  getMoviesByMood(moodKey, limit = 30) {
    const mood = IPTVService.MOODS[moodKey];
    if (!mood) return [];
    const out = this.movies.filter(m => {
      const cat = m.category || '';
      if (mood.cat && mood.cat.test(cat)) return true;
      if (mood.title && mood.title.test(m.name || '')) return true;
      return false;
    });
    return this._sortByQuality(out).slice(0, limit);
  }

  getTopRatedMovies(limit = 20) {
    return this.movies
      .map(m => ({ m, r: parseFloat(m.rating) || 0 }))
      .filter(x => x.r >= 7)
      .sort((a, b) => b.r - a.r)
      .slice(0, limit)
      .map(x => x.m);
  }

  getRecentlyAddedMovies(limit = 20) {
    return [...this.movies]
      .filter(m => m.added)
      .sort((a, b) => Number(b.added) - Number(a.added))
      .slice(0, limit);
  }

  getMoviesByDecade(decade, limit = 30) {
    // decade = 1960, 1970, 1980, ... — matches "(YYYY)" suffix in title
    const lo = decade, hi = decade + 9;
    const out = this.movies.filter(m => {
      const y = this._extractYear(m.name);
      return y && y >= lo && y <= hi;
    });
    return this._sortByQuality(out).slice(0, limit);
  }

  // Pick a single random highly-rated movie, preferring genres the user
  // has watched recently. Optional `excludeIds` skips items already seen.
  getRandomMovie(opts = {}) {
    const { excludeIds = [], preferCategoryIds = [] } = opts;
    const skip = new Set(excludeIds);
    let pool = this.movies.filter(m => !skip.has(m.id) && (parseFloat(m.rating) || 0) >= 6.5);
    if (pool.length === 0) pool = this.movies.filter(m => !skip.has(m.id));
    if (preferCategoryIds.length) {
      const preferred = pool.filter(m => preferCategoryIds.includes(m.categoryId));
      if (preferred.length >= 5) pool = preferred;
    }
    if (pool.length === 0) return null;
    return pool[Math.floor(Math.random() * pool.length)];
  }

  _extractYear(name) {
    const m = (name || '').match(/\((19|20)\d{2}\)/);
    return m ? parseInt(m[0].slice(1, 5), 10) : null;
  }

  _sortByQuality(arr) {
    // Highly-rated items first; ties broken by recency.
    return [...arr].sort((a, b) => {
      const ra = parseFloat(a.rating) || 0;
      const rb = parseFloat(b.rating) || 0;
      if (rb !== ra) return rb - ra;
      return Number(b.added || 0) - Number(a.added || 0);
    });
  }

  // ─── EPG ───
  async loadEPG(url) {
    if (!url) return;
    this.emit('loading', { phase: 'Loading EPG...' });
    try {
      const resp = await fetch(`/api/epg?url=${encodeURIComponent(url)}`);
      if (!resp.ok) return;
      const text = await resp.text();
      const parser = new DOMParser();
      const xml = parser.parseFromString(text, 'text/xml');
      const programmes = xml.querySelectorAll('programme');

      programmes.forEach(prog => {
        const channelId = prog.getAttribute('channel') || '';
        const start = this._parseXmltvDate(prog.getAttribute('start'));
        const stop = this._parseXmltvDate(prog.getAttribute('stop'));
        const titleEl = prog.querySelector('title');
        const descEl = prog.querySelector('desc');

        if (!this.epgData[channelId]) this.epgData[channelId] = [];
        this.epgData[channelId].push({
          title: titleEl ? titleEl.textContent : '',
          description: descEl ? descEl.textContent : '',
          start, stop
        });
      });

      this.emit('loading', { phase: 'EPG loaded' });
    } catch (err) {
      console.warn('EPG load failed:', err);
    }
  }

  getNowPlaying(channelId, channelName = '') {
    const now = Date.now();
    // Try by epgId, then channel name
    const ids = [channelId, channelName, channelName.replace(/\s*(HD|FHD|4K|UHD|SD)\s*/gi, '').trim()];
    for (const id of ids) {
      const progs = this.epgData[id];
      if (progs) {
        const current = progs.find(p => p.start <= now && p.stop > now);
        if (current) {
          return {
            ...current,
            progress: ((now - current.start) / (current.stop - current.start)) * 100
          };
        }
      }
    }
    return null;
  }

  _parseXmltvDate(str) {
    if (!str) return 0;
    // Format: 20210101120000 +0000
    const clean = str.replace(/\s.*/, '');
    const y = clean.slice(0, 4);
    const m = clean.slice(4, 6);
    const d = clean.slice(6, 8);
    const h = clean.slice(8, 10);
    const min = clean.slice(10, 12);
    const s = clean.slice(12, 14);
    return new Date(`${y}-${m}-${d}T${h}:${min}:${s}`).getTime();
  }
}

// ─── Favorites Manager ───
class FavoritesManager {
  constructor(key = 'vc_favorites') {
    this.key = key;
    this._load();
  }
  _load() {
    try {
      this.items = JSON.parse(localStorage.getItem(this.key) || '[]');
    } catch { this.items = []; }
  }
  _save() {
    localStorage.setItem(this.key, JSON.stringify(this.items));
  }
  has(id) { return this.items.includes(String(id)); }
  toggle(id) {
    id = String(id);
    if (this.has(id)) {
      this.items = this.items.filter(i => i !== id);
    } else {
      this.items.push(id);
    }
    this._save();
    return this.has(id);
  }
  getAll() { return [...this.items]; }
}

// ─── Watch History Manager ───
class WatchHistoryManager {
  constructor(key = 'vc_history') {
    this.key = key;
    this._load();
  }
  _load() {
    try {
      this.items = JSON.parse(localStorage.getItem(this.key) || '[]');
    } catch { this.items = []; }
  }
  _save() {
    localStorage.setItem(this.key, JSON.stringify(this.items));
  }
  add(entry) {
    // entry: { id, type, name, poster, position, duration, timestamp }
    this.items = this.items.filter(i => !(i.id === entry.id && i.type === entry.type));
    this.items.unshift({ ...entry, timestamp: Date.now() });
    if (this.items.length > 50) this.items = this.items.slice(0, 50);
    this._save();
  }
  getRecent(count = 20) {
    return this.items.slice(0, count);
  }
  getContinueWatching() {
    return this.items.filter(i => i.position > 0 && i.duration > 0 && (i.position / i.duration) < 0.95);
  }
  getPosition(id, type) {
    const item = this.items.find(i => i.id === id && i.type === type);
    return item ? item.position : 0;
  }
  clear() {
    this.items = [];
    this._save();
  }
}

// ─── Recent Channels ───
class RecentChannelsManager {
  constructor(key = 'vc_recent_channels') {
    this.key = key;
    this._load();
  }
  _load() {
    try {
      this.items = JSON.parse(localStorage.getItem(this.key) || '[]');
    } catch { this.items = []; }
  }
  _save() {
    localStorage.setItem(this.key, JSON.stringify(this.items));
  }
  add(channelId) {
    this.items = this.items.filter(i => i !== channelId);
    this.items.unshift(channelId);
    if (this.items.length > 20) this.items = this.items.slice(0, 20);
    this._save();
  }
  getAll() { return [...this.items]; }
}

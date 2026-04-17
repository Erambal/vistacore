/* ═══════════════════════════════════════════
   VistaCore Web — Video Player Module
   HLS.js wrapper with controls overlay
   ═══════════════════════════════════════════ */

class VCPlayer {
  constructor(containerId) {
    this.container = document.getElementById(containerId);
    this.video = null;
    this.hls = null;
    this.overlay = null;
    this.isPlaying = false;
    this.isFullscreen = false;
    this.hideTimer = null;
    this.onChannelChange = null;
    this.currentItem = null;
    this._positionInterval = null;
    this._build();
  }

  _build() {
    this.container.innerHTML = `
      <div class="vc-player">
        <video class="vc-player-video" playsinline></video>
        <div class="vc-player-overlay">
          <div class="vc-player-top">
            <button class="vc-player-btn vc-player-back" title="Back">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
            </button>
            <div class="vc-player-title"></div>
            <div class="vc-player-top-right">
              <button class="vc-player-btn vc-player-cast" title="Cast" style="display:none">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M2 16v3h3M2 12v1a7 7 0 017 7h1M2 8V7a9 9 0 019 9v1"/>
                  <rect x="2" y="4" width="20" height="15" rx="2"/>
                </svg>
              </button>
              <button class="vc-player-btn vc-player-pip" title="Picture in Picture">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2"/><rect x="11" y="9" width="9" height="7" rx="1" fill="currentColor" opacity="0.3" stroke="currentColor"/></svg>
              </button>
              <button class="vc-player-btn vc-player-fullscreen" title="Fullscreen">
                <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M8 3H5a2 2 0 00-2 2v3M21 8V5a2 2 0 00-2-2h-3M3 16v3a2 2 0 002 2h3M16 21h3a2 2 0 002-2v-3"/></svg>
              </button>
            </div>
          </div>
          <div class="vc-player-center">
            <button class="vc-player-btn vc-player-prev" title="Previous">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg>
            </button>
            <button class="vc-player-btn vc-player-play" title="Play/Pause">
              <svg class="play-icon" width="48" height="48" viewBox="0 0 24 24" fill="currentColor"><polygon points="5,3 19,12 5,21"/></svg>
              <svg class="pause-icon" width="48" height="48" viewBox="0 0 24 24" fill="currentColor" style="display:none"><rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/></svg>
            </button>
            <button class="vc-player-btn vc-player-next" title="Next">
              <svg width="32" height="32" viewBox="0 0 24 24" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg>
            </button>
          </div>
          <div class="vc-player-bottom">
            <div class="vc-player-progress">
              <div class="vc-player-progress-bar">
                <div class="vc-player-progress-fill"></div>
                <div class="vc-player-progress-handle"></div>
              </div>
              <div class="vc-player-time">
                <span class="vc-player-current">0:00</span>
                <span class="vc-player-duration">LIVE</span>
              </div>
            </div>
            <div class="vc-player-controls-row">
              <div class="vc-player-volume-wrap">
                <button class="vc-player-btn vc-player-mute" title="Mute">
                  <svg class="vol-on" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19" fill="currentColor" opacity="0.3"/><path d="M19.07 4.93a10 10 0 010 14.14M15.54 8.46a5 5 0 010 7.07"/></svg>
                  <svg class="vol-off" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="display:none"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19" fill="currentColor" opacity="0.3"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
                </button>
                <input type="range" class="vc-player-volume" min="0" max="1" step="0.05" value="1">
              </div>
              <div class="vc-player-info-strip">
                <span class="vc-player-now-text"></span>
              </div>
            </div>
          </div>
        </div>
        <div class="vc-player-loading" style="display:none">
          <div class="vc-player-spinner"></div>
        </div>
        <div class="vc-player-error" style="display:none">
          <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>
          <p class="vc-player-error-msg">Unable to play this stream</p>
          <button class="vc-player-btn vc-player-retry">Retry</button>
        </div>
      </div>
    `;

    this.video = this.container.querySelector('.vc-player-video');
    this.overlay = this.container.querySelector('.vc-player-overlay');
    this._bindEvents();
    this._initCast();
  }

  // ─── Chromecast ───
  _initCast() {
    this._castBtn = this.container.querySelector('.vc-player-cast');
    this._castSession = null;
    this._isCasting = false;

    const setup = () => {
      if (!window.cast || !window.chrome || !chrome.cast) return;

      cast.framework.CastContext.getInstance().setOptions({
        receiverApplicationId: chrome.cast.media.DEFAULT_MEDIA_RECEIVER_APP_ID,
        autoJoinPolicy: chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED,
      });

      this._castBtn.style.display = '';
      this._castBtn.addEventListener('click', () => this._toggleCast());

      cast.framework.CastContext.getInstance().addEventListener(
        cast.framework.CastContextEventType.CAST_STATE_CHANGED,
        (e) => this._onCastStateChanged(e.castState)
      );
      this._onCastStateChanged(cast.framework.CastContext.getInstance().getCastState());
    };

    if (window.cast && window.cast.framework) {
      setup();
    } else {
      window.__onGCastApiAvailable = (available) => { if (available) setup(); };
    }
  }

  _onCastStateChanged(state) {
    // NO_DEVICES_AVAILABLE, NOT_CONNECTED, CONNECTING, CONNECTED
    const connected = state === 'CONNECTED';
    this._isCasting = connected;
    if (this._castBtn) {
      this._castBtn.style.color = connected ? 'var(--gold)' : '#fff';
    }
    if (connected && this._lastUrl) {
      this._loadOnCastReceiver(this._lastUrl, this._lastTitle, this._lastItem);
      this.video.pause();
    }
  }

  async _toggleCast() {
    try {
      const ctx = cast.framework.CastContext.getInstance();
      if (this._isCasting) {
        ctx.endCurrentSession(true);
      } else {
        await ctx.requestSession();
      }
    } catch (err) {
      console.warn('Cast failed:', err);
    }
  }

  _loadOnCastReceiver(url, title, item) {
    const ctx = cast.framework.CastContext.getInstance();
    const session = ctx.getCurrentSession();
    if (!session) return;

    // Use the absolute URL (Chromecast needs a URL it can reach directly,
    // not the Worker's /api/stream proxy unless the receiver app allows CORS).
    // Send the proxied URL as-is — if hosted on a public domain, the receiver
    // will fetch it through the Worker, which sets permissive CORS.
    const absoluteUrl = url.startsWith('http') ? url : new URL(url, location.origin).toString();

    const isHLS = absoluteUrl.includes('.m3u8') || absoluteUrl.includes('/live/');
    const mediaInfo = new chrome.cast.media.MediaInfo(
      absoluteUrl,
      isHLS ? 'application/x-mpegURL' : 'video/mp4'
    );
    mediaInfo.streamType = isHLS ? chrome.cast.media.StreamType.LIVE : chrome.cast.media.StreamType.BUFFERED;

    const metadata = new chrome.cast.media.GenericMediaMetadata();
    metadata.title = title || '';
    if (item && item.poster) metadata.images = [new chrome.cast.Image(item.poster)];
    mediaInfo.metadata = metadata;

    const request = new chrome.cast.media.LoadRequest(mediaInfo);
    session.loadMedia(request).catch(err => {
      console.warn('Cast load failed:', err);
    });
  }

  _bindEvents() {
    const el = (sel) => this.container.querySelector(sel);

    // Play/Pause
    el('.vc-player-play').addEventListener('click', () => this.togglePlay());
    this.video.addEventListener('click', () => this.togglePlay());

    // Fullscreen
    el('.vc-player-fullscreen').addEventListener('click', () => this.toggleFullscreen());

    // PiP
    el('.vc-player-pip').addEventListener('click', () => this.togglePiP());

    // Back
    el('.vc-player-back').addEventListener('click', () => {
      this.stop();
      if (this._onBack) this._onBack();
    });

    // Prev/Next
    el('.vc-player-prev').addEventListener('click', () => {
      if (this.onChannelChange) this.onChannelChange(-1);
    });
    el('.vc-player-next').addEventListener('click', () => {
      if (this.onChannelChange) this.onChannelChange(1);
    });

    // Volume
    const volSlider = el('.vc-player-volume');
    volSlider.addEventListener('input', (e) => {
      this.video.volume = parseFloat(e.target.value);
      this._updateVolumeIcon();
    });
    el('.vc-player-mute').addEventListener('click', () => {
      this.video.muted = !this.video.muted;
      this._updateVolumeIcon();
    });

    // Progress bar scrubbing
    const progressBar = el('.vc-player-progress-bar');
    progressBar.addEventListener('click', (e) => {
      if (!this.video.duration || !isFinite(this.video.duration)) return;
      const rect = progressBar.getBoundingClientRect();
      const pct = (e.clientX - rect.left) / rect.width;
      this.video.currentTime = pct * this.video.duration;
    });

    // Video events
    this.video.addEventListener('playing', () => {
      this.isPlaying = true;
      this._updatePlayIcon();
      this._hideLoading();
    });
    this.video.addEventListener('pause', () => {
      this.isPlaying = false;
      this._updatePlayIcon();
    });
    this.video.addEventListener('waiting', () => this._showLoading());
    this.video.addEventListener('canplay', () => this._hideLoading());
    this.video.addEventListener('timeupdate', () => this._updateProgress());
    this.video.addEventListener('error', () => this._showError());
    this.video.addEventListener('ended', () => {
      if (this.onChannelChange) this.onChannelChange(1);
    });

    // Overlay show/hide on mouse
    const player = this.container.querySelector('.vc-player');
    player.addEventListener('mousemove', () => this._showOverlay());
    player.addEventListener('mouseleave', () => this._startHideTimer());

    // Keyboard
    document.addEventListener('keydown', (e) => {
      if (!this.container.querySelector('.vc-player').offsetParent) return;
      switch (e.key) {
        case ' ': e.preventDefault(); this.togglePlay(); break;
        case 'f': case 'F': this.toggleFullscreen(); break;
        case 'Escape': if (this.isFullscreen) this.toggleFullscreen(); break;
        case 'ArrowLeft':
          if (this.video.duration && isFinite(this.video.duration))
            this.video.currentTime = Math.max(0, this.video.currentTime - 10);
          break;
        case 'ArrowRight':
          if (this.video.duration && isFinite(this.video.duration))
            this.video.currentTime = Math.min(this.video.duration, this.video.currentTime + 10);
          break;
        case 'ArrowUp': this.video.volume = Math.min(1, this.video.volume + 0.1); break;
        case 'ArrowDown': this.video.volume = Math.max(0, this.video.volume - 0.1); break;
        case 'm': case 'M': this.video.muted = !this.video.muted; this._updateVolumeIcon(); break;
      }
    });

    // Fullscreen change
    document.addEventListener('fullscreenchange', () => {
      this.isFullscreen = !!document.fullscreenElement;
    });

    // Retry
    el('.vc-player-retry').addEventListener('click', () => {
      if (this._lastUrl) this.play(this._lastUrl, this._lastTitle, this._lastItem);
    });
  }

  // ─── Playback ───
  play(url, title = '', item = null) {
    this._lastUrl = url;
    this._lastTitle = title;
    this._lastItem = item;
    this.currentItem = item;

    this._hideError();
    this._showLoading();

    // Set title
    this.container.querySelector('.vc-player-title').textContent = title;

    // If a Cast session is active, send to receiver and skip local playback
    if (this._isCasting) {
      this._loadOnCastReceiver(url, title, item);
      this._hideLoading();
      return;
    }

    // Destroy old HLS instance
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }

    // Clear position tracker
    if (this._positionInterval) clearInterval(this._positionInterval);

    const isHLS = url.includes('.m3u8') || url.includes('/live/');

    if (isHLS && Hls.isSupported()) {
      this.hls = new Hls({
        maxBufferLength: 30,
        maxMaxBufferLength: 60,
        startLevel: -1,
        capLevelOnFPSDrop: true,
        lowLatencyMode: false,
      });
      this.hls.loadSource(url);
      this.hls.attachMedia(this.video);
      this.hls.on(Hls.Events.MANIFEST_PARSED, () => {
        this.video.play().catch(() => {});
      });
      this.hls.on(Hls.Events.ERROR, (event, data) => {
        if (data.fatal) {
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
            this.hls.startLoad();
          } else if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
            this.hls.recoverMediaError();
          } else {
            this._showError('Stream unavailable');
          }
        }
      });
    } else if (this.video.canPlayType('application/vnd.apple.mpegurl') && isHLS) {
      // Safari native HLS
      this.video.src = url;
      this.video.play().catch(() => {});
    } else {
      // Direct (MP4, etc.)
      this.video.src = url;
      this.video.play().catch(() => {});
    }

    // Restore position if VOD
    if (item && window.watchHistory) {
      const pos = window.watchHistory.getPosition(item.id, item.type || 'channel');
      if (pos > 0) this.video.currentTime = pos;
    }

    // Track position
    this._positionInterval = setInterval(() => {
      if (item && this.video.currentTime > 0 && window.watchHistory) {
        window.watchHistory.add({
          id: item.id,
          type: item.type || 'channel',
          name: title,
          poster: item.poster || item.logo || '',
          position: this.video.currentTime,
          duration: this.video.duration || 0
        });
      }
    }, 5000);

    // Live indicator
    const durEl = this.container.querySelector('.vc-player-duration');
    if (isHLS && url.includes('/live/')) {
      durEl.textContent = 'LIVE';
      durEl.style.color = '#4CAF50';
    } else {
      durEl.textContent = '0:00';
      durEl.style.color = '';
    }
  }

  stop() {
    if (this.hls) {
      this.hls.destroy();
      this.hls = null;
    }
    if (this._positionInterval) clearInterval(this._positionInterval);
    this.video.pause();
    this.video.removeAttribute('src');
    this.video.load();
    this.isPlaying = false;
    this._updatePlayIcon();
    if (this.isFullscreen) this.toggleFullscreen();
  }

  togglePlay() {
    if (this.video.paused) {
      this.video.play().catch(() => {});
    } else {
      this.video.pause();
    }
  }

  toggleFullscreen() {
    const player = this.container.querySelector('.vc-player');
    if (!document.fullscreenElement) {
      player.requestFullscreen().catch(() => {});
    } else {
      document.exitFullscreen().catch(() => {});
    }
  }

  togglePiP() {
    if (document.pictureInPictureElement) {
      document.exitPictureInPicture().catch(() => {});
    } else if (this.video.requestPictureInPicture) {
      this.video.requestPictureInPicture().catch(() => {});
    }
  }

  setTitle(title) {
    this.container.querySelector('.vc-player-title').textContent = title;
  }

  setNowPlaying(text) {
    this.container.querySelector('.vc-player-now-text').textContent = text;
  }

  onBack(fn) { this._onBack = fn; }

  // ─── UI Updates ───
  _updatePlayIcon() {
    const play = this.container.querySelector('.play-icon');
    const pause = this.container.querySelector('.pause-icon');
    play.style.display = this.isPlaying ? 'none' : '';
    pause.style.display = this.isPlaying ? '' : 'none';
  }

  _updateVolumeIcon() {
    const on = this.container.querySelector('.vol-on');
    const off = this.container.querySelector('.vol-off');
    const muted = this.video.muted || this.video.volume === 0;
    on.style.display = muted ? 'none' : '';
    off.style.display = muted ? '' : 'none';
  }

  _updateProgress() {
    const fill = this.container.querySelector('.vc-player-progress-fill');
    const handle = this.container.querySelector('.vc-player-progress-handle');
    const current = this.container.querySelector('.vc-player-current');
    const duration = this.container.querySelector('.vc-player-duration');

    if (this.video.duration && isFinite(this.video.duration)) {
      const pct = (this.video.currentTime / this.video.duration) * 100;
      fill.style.width = `${pct}%`;
      handle.style.left = `${pct}%`;
      current.textContent = this._formatTime(this.video.currentTime);
      duration.textContent = this._formatTime(this.video.duration);
    } else {
      // Live stream
      fill.style.width = '100%';
      handle.style.display = 'none';
    }
  }

  _formatTime(s) {
    if (!s || !isFinite(s)) return '0:00';
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = Math.floor(s % 60);
    if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
    return `${m}:${String(sec).padStart(2, '0')}`;
  }

  _showLoading() {
    this.container.querySelector('.vc-player-loading').style.display = 'flex';
    this._hideError();
  }
  _hideLoading() {
    this.container.querySelector('.vc-player-loading').style.display = 'none';
  }
  _showError(msg) {
    this._hideLoading();
    const errEl = this.container.querySelector('.vc-player-error');
    errEl.style.display = 'flex';
    if (msg) errEl.querySelector('.vc-player-error-msg').textContent = msg;
  }
  _hideError() {
    this.container.querySelector('.vc-player-error').style.display = 'none';
  }

  _showOverlay() {
    this.overlay.classList.add('visible');
    this._startHideTimer();
  }
  _startHideTimer() {
    clearTimeout(this.hideTimer);
    this.hideTimer = setTimeout(() => {
      if (this.isPlaying) this.overlay.classList.remove('visible');
    }, 3000);
  }
}

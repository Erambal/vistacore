# VistaCore VOD Transcoder

A small service that makes "unplayable-in-browser" IPTV VOD (MKV containers,
DivX/MPEG-2 video, Dolby AC-3/E-AC-3 audio) play in the VistaCore **web** app,
by re-encoding on the fly to browser-friendly **HLS (H.264 + AAC)**.

It re-encodes only what's needed:

| Source | What happens | Cost |
| --- | --- | --- |
| H.264 video | copied untouched | free |
| MPEG-4 / DivX / MPEG-2 video | → H.264 via **NVENC** (GTX 1070), CPU fallback | GPU |
| AAC / MP3 audio | copied | free |
| AC-3 / E-AC-3 / DTS audio | → stereo AAC | tiny (CPU) |

So the common case (modern H.264 + Dolby audio, e.g. *Wild Cards*) is a cheap
audio-only re-encode; only legacy DivX (e.g. *I Dream of Jeannie*) pays for a
GPU video transcode.

Runs natively on the Windows gaming PC (NVENC through the NVIDIA driver — no
Docker needed) behind the existing `render-server` cloudflared tunnel. Your
WordPress/Dispatcharr VPS is never touched.

---

## 1. Prerequisites (on the Windows PC)

1. **Python 3.10+** — https://www.python.org/downloads/ (tick "Add to PATH").
2. **ffmpeg with NVENC** — download a full build (NVENC is included):
   - https://www.gyan.dev/ffmpeg/builds/ (`ffmpeg-release-full`) — unzip to e.g. `C:\ffmpeg`.
   - Confirm: `ffmpeg -hide_banner -encoders | findstr nvenc` should list `h264_nvenc`.
3. Make sure `ffmpeg.exe` / `ffprobe.exe` are on PATH (or set `VC_FFMPEG` /
   `VC_FFPROBE` in `run.bat`).

## 2. Install & run

```bat
cd transcoder
pip install -r requirements.txt
run.bat
```

It listens on `http://localhost:8088`. Quick check (in another terminal):

```bat
curl http://localhost:8088/healthz
```
→ `{"ok":true,"jobs":0,"nvenc_active":0}`

## 3. Expose it through your existing tunnel

Your tunnel currently runs single-service (`--url http://localhost:3100`).
Switch it to a **config file** so it can serve both the render server and the
transcoder. Use `cloudflared-config.example.yml` as a template:

1. Add a DNS route for the new hostname (one time):
   ```bat
   cloudflared tunnel route dns render-server transcode.alturaview.com
   ```
2. Save the example as `C:\Users\Elk Springs Villa\.cloudflared\config.yml`
   (fix the `credentials-file` path/UUID to match yours — see
   `.cloudflared\*.json`).
3. Restart the tunnel **without** the `--url` flag:
   ```bat
   cloudflared tunnel run render-server
   ```
4. Verify from anywhere:
   ```
   https://transcode.alturaview.com/healthz
   ```

## 4. Point VistaCore at it

In the VistaCore web app → **Settings → VOD Transcoder**, set
**Transcoder URL** to:

```
https://transcode.alturaview.com
```

Save. Now any movie/show the browser can't play natively is routed here instead
of showing the "use the TV app" message.

## 5. Run it 24/7 (optional but recommended)

Use **NSSM** (https://nssm.cc) to run it as a Windows service so it survives
reboots and starts on boot:

```bat
nssm install VistaCoreTranscoder "C:\path\to\transcoder\run.bat"
nssm start VistaCoreTranscoder
```

(Do the same for `cloudflared tunnel run render-server` if it isn't already a
service.)

---

## Tuning (environment variables)

| Var | Default | Meaning |
| --- | --- | --- |
| `VC_PORT` | `8088` | Listen port |
| `VC_NVENC_MAX` | `2` | Max concurrent GPU sessions (GTX 1070 ≈ 2–3; extra video jobs fall back to CPU) |
| `VC_MAX_JOBS` | `8` | Max concurrent transcodes total |
| `VC_IDLE_TIMEOUT` | `120` | Seconds of no requests before a job is killed & cleaned up |
| `VC_SEG_TIME` | `6` | HLS segment length (seconds) |
| `VC_FFMPEG` / `VC_FFPROBE` | `ffmpeg` / `ffprobe` | Binary paths if not on PATH |

## How it fits together

```
Browser ── plays HLS ──▶ transcode.alturaview.com (cloudflared → localhost:8088)
                                   │  ffmpeg: copy/NVENC video + AAC audio → HLS
                                   ▼
                         pulls source from your Dispatcharr Xtream VOD URL
```

## Notes & limits

- **Seeking**: playback starts fast and you can scrub within the
  already-transcoded portion (live-style HLS). Scrubbing far ahead waits for
  the transcode to reach there.
- **NVENC sessions**: shared with Jellyfin. If both are busy transcoding video
  at once you may hit the 1070's session cap; extra jobs automatically use the
  CPU (libx264). The community "nvidia-patch" lifts the cap if you ever need to.
- **ffmpeg runs below-normal priority** so it never starves Jellyfin/gaming.
- Segments live in a temp dir and are cleaned up automatically when a stream is
  idle or the service restarts.

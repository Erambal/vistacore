"""
VistaCore VOD Transcoder
========================
A tiny HTTP service that takes an IPTV VOD URL the browser can't play
(MKV/AVI container, DivX/MPEG-2 video, or Dolby AC-3/E-AC-3 audio) and streams
it back as browser-friendly HLS (H.264 + AAC), re-encoding ONLY what's
necessary:

  * video already H.264  -> copied untouched (cheap)
  * video MPEG-4/DivX/…  -> re-encoded to H.264 on the GTX 1070 via NVENC,
                            falling back to CPU (libx264) when all NVENC
                            sessions are busy
  * audio AAC/MP3        -> copied
  * audio E-AC-3/AC-3/…  -> re-encoded to stereo AAC (cheap, CPU)

Designed to run natively on the Windows gaming PC (uses NVENC through the
NVIDIA driver — no Docker GPU passthrough needed) and sit behind the existing
cloudflared tunnel.

Run:    python app.py        (or run.bat)
Needs:  ffmpeg.exe + ffprobe.exe on PATH, or set VC_FFMPEG / VC_FFPROBE.
        pip install -r requirements.txt
"""

import asyncio
import hashlib
import os
import shutil
import subprocess
import tempfile
import time

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, Response

# ─── Config (override via environment variables) ───
FFMPEG       = os.environ.get("VC_FFMPEG", "ffmpeg")
FFPROBE      = os.environ.get("VC_FFPROBE", "ffprobe")
WORK_DIR     = os.environ.get("VC_WORK_DIR", os.path.join(tempfile.gettempdir(), "vc-transcode"))
PORT         = int(os.environ.get("VC_PORT", "8088"))
SEG_TIME     = int(os.environ.get("VC_SEG_TIME", "6"))      # HLS segment seconds
NVENC_MAX    = int(os.environ.get("VC_NVENC_MAX", "2"))     # concurrent GPU sessions (GTX 1070 ≈ 2-3)
MAX_JOBS     = int(os.environ.get("VC_MAX_JOBS", "8"))      # total concurrent transcodes
IDLE_TIMEOUT = int(os.environ.get("VC_IDLE_TIMEOUT", "120"))  # kill a job N s after last request

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")

# Codecs a browser plays directly — copy these, re-encode everything else.
COPY_VIDEO = {"h264"}
COPY_AUDIO = {"aac", "mp3"}

app = FastAPI()
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)

_jobs = {}          # job_id -> {"proc", "dir", "last", "nvenc"}
_lock = asyncio.Lock()
_nvenc_active = 0


# ─── Process helpers ───
def _popen(cmd):
    """Start ffmpeg below-normal priority so it never starves Jellyfin/gaming."""
    kw = {"stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL}
    if os.name == "nt":
        kw["creationflags"] = subprocess.BELOW_NORMAL_PRIORITY_CLASS
    return subprocess.Popen(cmd, **kw)


def _probe(src, stream):
    """Return the codec_name of the first matching stream, or '' on failure."""
    try:
        out = subprocess.run(
            [FFPROBE, "-v", "error", "-user_agent", UA,
             "-select_streams", stream, "-show_entries", "stream=codec_name",
             "-of", "default=nw=1:nk=1", src],
            capture_output=True, text=True, timeout=30)
        for line in out.stdout.splitlines():
            if line.strip():
                return line.strip().lower()
    except Exception:
        pass
    return ""


def _job_id(src):
    return hashlib.sha1(src.encode("utf-8")).hexdigest()[:16]


def _build_cmd(src, out_dir, job_id, need_video, use_nvenc, acodec):
    if not need_video:
        v = ["-c:v", "copy"]
    elif use_nvenc:
        v = ["-c:v", "h264_nvenc", "-preset", "p5", "-rc", "vbr", "-cq", "23",
             "-b:v", "0", "-profile:v", "high",
             "-force_key_frames", f"expr:gte(t,n_forced*{SEG_TIME})"]
    else:
        v = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
             "-force_key_frames", f"expr:gte(t,n_forced*{SEG_TIME})"]
    a = ["-c:a", "copy"] if acodec in COPY_AUDIO else ["-c:a", "aac", "-b:a", "192k", "-ac", "2"]
    return [
        FFMPEG, "-hide_banner", "-loglevel", "error", "-y",
        "-user_agent", UA,
        "-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5",
        "-i", src,
        *v, *a,
        "-f", "hls", "-hls_time", str(SEG_TIME),
        "-hls_playlist_type", "event",
        "-hls_flags", "independent_segments",
        "-hls_base_url", f"/seg/{job_id}/",
        "-hls_segment_filename", os.path.join(out_dir, "seg%05d.ts"),
        os.path.join(out_dir, "index.m3u8"),
    ]


def _cleanup_locked(job_id):
    """Kill a job's ffmpeg, free its NVENC slot, and delete its segments.
    Caller must hold _lock."""
    global _nvenc_active
    job = _jobs.pop(job_id, None)
    if not job:
        return
    if job.get("nvenc"):
        _nvenc_active = max(0, _nvenc_active - 1)
    try:
        if job["proc"].poll() is None:
            job["proc"].terminate()
            try:
                job["proc"].wait(timeout=5)
            except Exception:
                job["proc"].kill()
    except Exception:
        pass
    shutil.rmtree(job["dir"], ignore_errors=True)


async def _ensure_job(src):
    """Start (or reuse) a transcode for `src` and return its job id."""
    global _nvenc_active
    job_id = _job_id(src)

    # Fast path: a job for this source is already running.
    async with _lock:
        job = _jobs.get(job_id)
        if job and job["proc"].poll() is None:
            job["last"] = time.time()
            return job_id

    # Probe off the event loop (ffprobe blocks).
    vcodec = await asyncio.to_thread(_probe, src, "v:0")
    acodec = await asyncio.to_thread(_probe, src, "a:0")
    need_video = vcodec not in COPY_VIDEO

    async with _lock:
        # Re-check: another request may have started it while we probed.
        job = _jobs.get(job_id)
        if job and job["proc"].poll() is None:
            job["last"] = time.time()
            return job_id
        if job:
            _cleanup_locked(job_id)
        if len(_jobs) >= MAX_JOBS:
            _cleanup_locked(min(_jobs, key=lambda k: _jobs[k]["last"]))

        use_nvenc = False
        if need_video and _nvenc_active < NVENC_MAX:
            use_nvenc = True
            _nvenc_active += 1

        out_dir = os.path.join(WORK_DIR, job_id)
        os.makedirs(out_dir, exist_ok=True)
        cmd = _build_cmd(src, out_dir, job_id, need_video, use_nvenc, acodec)
        _jobs[job_id] = {"proc": _popen(cmd), "dir": out_dir,
                         "last": time.time(), "nvenc": use_nvenc}
    return job_id


# ─── Routes ───
@app.get("/healthz")
async def healthz():
    return {"ok": True, "jobs": len(_jobs), "nvenc_active": _nvenc_active}


@app.get("/hls.m3u8")
async def hls(src: str):
    if not src.lower().startswith("http"):
        raise HTTPException(400, "bad src")
    job_id = await _ensure_job(src)
    playlist = os.path.join(WORK_DIR, job_id, "index.m3u8")

    # Wait for ffmpeg to emit the first segment(s).
    deadline = time.time() + 30
    while time.time() < deadline:
        async with _lock:
            job = _jobs.get(job_id)
            dead = job["proc"].poll() if job else 1
        if os.path.exists(playlist) and os.path.getsize(playlist) > 0:
            txt = open(playlist, "r", encoding="utf-8", errors="ignore").read()
            if ".ts" in txt:
                async with _lock:
                    if job_id in _jobs:
                        _jobs[job_id]["last"] = time.time()
                return Response(content=txt,
                                media_type="application/vnd.apple.mpegurl")
        if dead not in (None, 0):
            raise HTTPException(502, "transcode failed to start")
        await asyncio.sleep(0.5)
    raise HTTPException(504, "transcode start timeout")


@app.get("/seg/{job_id}/{name}")
async def seg(job_id: str, name: str):
    if "/" in name or "\\" in name or ".." in name:
        raise HTTPException(400)
    async with _lock:
        job = _jobs.get(job_id)
        if not job:
            raise HTTPException(404)
        job["last"] = time.time()
        out_dir, proc = job["dir"], job["proc"]

    path = os.path.join(out_dir, name)
    deadline = time.time() + 25
    while not os.path.exists(path) and time.time() < deadline:
        if proc.poll() not in (None, 0):
            break
        await asyncio.sleep(0.3)
    if not os.path.exists(path):
        raise HTTPException(404)
    return FileResponse(path, media_type="video/mp2t")


@app.on_event("startup")
async def _startup():
    os.makedirs(WORK_DIR, exist_ok=True)
    for d in os.listdir(WORK_DIR):
        shutil.rmtree(os.path.join(WORK_DIR, d), ignore_errors=True)

    async def reaper():
        while True:
            await asyncio.sleep(20)
            now = time.time()
            async with _lock:
                for jid in list(_jobs):
                    if now - _jobs[jid]["last"] > IDLE_TIMEOUT:
                        _cleanup_locked(jid)

    asyncio.create_task(reaper())


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=PORT)

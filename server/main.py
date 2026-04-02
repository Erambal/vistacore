"""
VistaFilter — companion server for VistaCore content filtering.
Extracts audio from VOD streams, runs Whisper transcription, and generates
filter files that the TV app uses to mute/skip objectionable content.
"""

import asyncio
import json
import os
import re
import subprocess
import time
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from enum import Enum
from typing import Optional

import aiosqlite
import httpx
from fastapi import FastAPI, HTTPException, Header, BackgroundTasks
from pydantic import BaseModel

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

API_KEY = os.getenv("API_KEY", "changeme")
WHISPER_MODEL = os.getenv("WHISPER_MODEL", "base")
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
WHISPER_COMPUTE = os.getenv("WHISPER_COMPUTE", "int8")
DATA_DIR = Path("/app/data")
FILTERS_DIR = DATA_DIR / "filters"
AUDIO_TMP = DATA_DIR / "audio_tmp"
DB_PATH = DATA_DIR / "vistafilter.db"

# Max concurrent processing jobs
MAX_WORKERS = 2

# ---------------------------------------------------------------------------
# Profanity word list (loaded at startup, editable in profanity.txt)
# ---------------------------------------------------------------------------

DEFAULT_PROFANITY = {
    "ass", "asshole", "bastard", "bitch", "bullshit", "crap", "damn",
    "dammit", "dick", "douchebag", "fuck", "fucking", "fucker",
    "horseshit", "jackass", "motherfucker", "piss", "prick", "shit",
    "shitty", "slut", "whore", "goddamn", "goddammit", "goddam",
}

BLASPHEMY_WORDS = {"goddamn", "goddammit", "goddam"}

def load_profanity_list() -> set[str]:
    """Load custom profanity list or fall back to defaults."""
    custom_path = DATA_DIR / "profanity.txt"
    if custom_path.exists():
        words = set()
        for line in custom_path.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                words.add(line.lower())
        return words
    return DEFAULT_PROFANITY.copy()

PROFANITY_SET = load_profanity_list()

# ---------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------

async def init_db():
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute("""
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                year TEXT DEFAULT '',
                stream_url TEXT NOT NULL,
                status TEXT DEFAULT 'queued',
                progress TEXT DEFAULT '',
                created_at REAL DEFAULT (unixepoch()),
                completed_at REAL,
                error TEXT
            )
        """)
        await db.commit()

# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------

class JobStatus(str, Enum):
    QUEUED = "queued"
    EXTRACTING = "extracting"
    TRANSCRIBING = "transcribing"
    FILTERING = "filtering"
    DONE = "done"
    FAILED = "failed"

class FilterRequest(BaseModel):
    title: str
    year: str = ""
    stream_url: str

class FilterSegment(BaseModel):
    startMs: int
    endMs: int
    action: str  # MUTE or SKIP
    category: str
    word: Optional[str] = None

class FilterFile(BaseModel):
    title: str
    year: str = ""
    source: str = "whisper"
    createdAt: int = 0
    segments: list[FilterSegment] = []

class JobResponse(BaseModel):
    id: str
    title: str
    year: str
    status: str
    progress: str
    created_at: float
    completed_at: Optional[float] = None
    error: Optional[str] = None

# ---------------------------------------------------------------------------
# Processing pipeline
# ---------------------------------------------------------------------------

processing_semaphore: asyncio.Semaphore

async def process_job(job_id: str, title: str, year: str, stream_url: str):
    """Full pipeline: extract audio → transcribe → match profanity → save filter."""
    async with processing_semaphore:
        audio_path = AUDIO_TMP / f"{job_id}.wav"
        try:
            # --- Step 1: Extract audio via FFmpeg ---
            await update_job(job_id, "extracting", "Downloading and extracting audio...")

            proc = await asyncio.create_subprocess_exec(
                "ffmpeg", "-y",
                "-i", stream_url,
                "-vn",                    # no video
                "-ac", "1",               # mono
                "-ar", "16000",           # 16kHz (Whisper optimal)
                "-t", "10800",            # max 3 hours
                "-f", "wav",
                str(audio_path),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
            _, stderr = await proc.communicate()

            if proc.returncode != 0 or not audio_path.exists():
                err = stderr.decode()[-500:] if stderr else "FFmpeg failed"
                await fail_job(job_id, f"Audio extraction failed: {err}")
                return

            # --- Step 2: Transcribe with Whisper ---
            await update_job(job_id, "transcribing", "Running speech recognition...")

            # Run Whisper in a thread to avoid blocking the event loop
            segments = await asyncio.to_thread(run_whisper, audio_path)

            # --- Step 3: Match profanity and generate filter ---
            await update_job(job_id, "filtering", "Generating filter file...")

            filter_segments = match_profanity(segments)
            filter_file = {
                "title": title,
                "year": year,
                "source": "whisper",
                "createdAt": int(time.time() * 1000),
                "segments": filter_segments,
            }

            # Save filter JSON
            out_path = filter_path(title, year)
            out_path.write_text(json.dumps(filter_file, indent=2))

            await update_job(job_id, "done", f"Complete — {len(filter_segments)} segments filtered")
            async with aiosqlite.connect(DB_PATH) as db:
                await db.execute(
                    "UPDATE jobs SET completed_at = ? WHERE id = ?",
                    (time.time(), job_id)
                )
                await db.commit()

        except Exception as e:
            await fail_job(job_id, str(e))
        finally:
            # Clean up temp audio
            if audio_path.exists():
                audio_path.unlink()


def run_whisper(audio_path: Path) -> list[dict]:
    """Run faster-whisper and return word-level timestamps."""
    from faster_whisper import WhisperModel

    model = WhisperModel(WHISPER_MODEL, device=WHISPER_DEVICE, compute_type=WHISPER_COMPUTE)
    segs, _ = model.transcribe(
        str(audio_path),
        word_timestamps=True,
        language=None,  # auto-detect
    )

    words = []
    for segment in segs:
        if segment.words:
            for w in segment.words:
                words.append({
                    "word": w.word.strip(),
                    "start": w.start,
                    "end": w.end,
                })
    return words


def match_profanity(words: list[dict]) -> list[dict]:
    """Match words against profanity list, return filter segments."""
    buffer_ms = 200
    raw_segments = []

    for w in words:
        clean = re.sub(r"[^a-z']", "", w["word"].lower())
        if clean in PROFANITY_SET:
            category = "BLASPHEMY" if clean in BLASPHEMY_WORDS else "PROFANITY"
            raw_segments.append({
                "startMs": max(0, int(w["start"] * 1000) - buffer_ms),
                "endMs": int(w["end"] * 1000) + buffer_ms,
                "action": "MUTE",
                "category": category,
                "word": clean,
            })

    # Merge overlapping/adjacent segments
    if not raw_segments:
        return []

    raw_segments.sort(key=lambda s: s["startMs"])
    merged = [raw_segments[0]]
    for seg in raw_segments[1:]:
        last = merged[-1]
        if seg["action"] == last["action"] and seg["startMs"] <= last["endMs"] + 100:
            last["endMs"] = max(last["endMs"], seg["endMs"])
        else:
            merged.append(seg)

    return merged


async def update_job(job_id: str, status: str, progress: str):
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "UPDATE jobs SET status = ?, progress = ? WHERE id = ?",
            (status, progress, job_id)
        )
        await db.commit()


async def fail_job(job_id: str, error: str):
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "UPDATE jobs SET status = 'failed', error = ?, completed_at = ? WHERE id = ?",
            (error, time.time(), job_id)
        )
        await db.commit()

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def sanitize_key(title: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", title.lower()).strip("_")[:80]

def filter_path(title: str, year: str = "") -> Path:
    key = sanitize_key(title) + (f"_{year}" if year else "")
    return FILTERS_DIR / f"{key}.json"

def verify_api_key(authorization: str = Header(None)):
    if API_KEY == "changeme":
        return  # No auth if key not configured
    if not authorization or authorization != f"Bearer {API_KEY}":
        raise HTTPException(401, "Invalid API key")

# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------

@asynccontextmanager
async def lifespan(app: FastAPI):
    global processing_semaphore
    processing_semaphore = asyncio.Semaphore(MAX_WORKERS)
    FILTERS_DIR.mkdir(parents=True, exist_ok=True)
    AUDIO_TMP.mkdir(parents=True, exist_ok=True)
    await init_db()
    yield

app = FastAPI(title="VistaFilter", version="1.0.0", lifespan=lifespan)

# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/api/health")
async def health():
    return {"status": "ok", "model": WHISPER_MODEL}


@app.post("/api/filter/request", response_model=JobResponse)
async def request_filter(
    req: FilterRequest,
    background_tasks: BackgroundTasks,
    authorization: str = Header(None),
):
    """Request a new content filter. Queues audio extraction + transcription."""
    verify_api_key(authorization)

    # Check if filter already exists
    fp = filter_path(req.title, req.year)
    if fp.exists():
        return JobResponse(
            id="existing",
            title=req.title,
            year=req.year,
            status="done",
            progress="Filter already available",
            created_at=fp.stat().st_mtime,
            completed_at=fp.stat().st_mtime,
        )

    # Check if there's already a pending/active job for this title
    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute(
            "SELECT * FROM jobs WHERE title = ? AND year = ? AND status IN ('queued','extracting','transcribing','filtering') LIMIT 1",
            (req.title, req.year)
        )
        existing = await cursor.fetchone()
        if existing:
            return JobResponse(**dict(existing))

    # Create new job
    job_id = str(uuid.uuid4())[:8]
    async with aiosqlite.connect(DB_PATH) as db:
        await db.execute(
            "INSERT INTO jobs (id, title, year, stream_url) VALUES (?, ?, ?, ?)",
            (job_id, req.title, req.year, req.stream_url)
        )
        await db.commit()

    # Queue processing
    background_tasks.add_task(process_job, job_id, req.title, req.year, req.stream_url)

    return JobResponse(
        id=job_id,
        title=req.title,
        year=req.year,
        status="queued",
        progress="Waiting to start...",
        created_at=time.time(),
    )


@app.get("/api/filter/status/{job_id}", response_model=JobResponse)
async def get_job_status(job_id: str, authorization: str = Header(None)):
    """Check the status of a filter generation job."""
    verify_api_key(authorization)

    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute("SELECT * FROM jobs WHERE id = ?", (job_id,))
        row = await cursor.fetchone()
        if not row:
            raise HTTPException(404, "Job not found")
        return JobResponse(**dict(row))


@app.get("/api/filter/{title}")
async def get_filter(title: str, year: str = "", authorization: str = Header(None)):
    """Download a completed filter file."""
    verify_api_key(authorization)

    fp = filter_path(title, year)
    if not fp.exists():
        raise HTTPException(404, "No filter available for this title")

    return json.loads(fp.read_text())


@app.get("/api/filters")
async def list_filters(authorization: str = Header(None)):
    """List all available filter files."""
    verify_api_key(authorization)

    filters = []
    for fp in FILTERS_DIR.glob("*.json"):
        try:
            data = json.loads(fp.read_text())
            filters.append({
                "title": data.get("title", fp.stem),
                "year": data.get("year", ""),
                "source": data.get("source", "unknown"),
                "segments": len(data.get("segments", [])),
                "createdAt": data.get("createdAt", 0),
            })
        except Exception:
            continue
    return filters


@app.get("/api/jobs")
async def list_jobs(
    limit: int = 20,
    authorization: str = Header(None),
):
    """List recent processing jobs."""
    verify_api_key(authorization)

    async with aiosqlite.connect(DB_PATH) as db:
        db.row_factory = aiosqlite.Row
        cursor = await db.execute(
            "SELECT * FROM jobs ORDER BY created_at DESC LIMIT ?", (limit,)
        )
        rows = await cursor.fetchall()
        return [JobResponse(**dict(r)) for r in rows]


@app.delete("/api/filter/{title}")
async def delete_filter(title: str, year: str = "", authorization: str = Header(None)):
    """Delete a filter file."""
    verify_api_key(authorization)

    fp = filter_path(title, year)
    if fp.exists():
        fp.unlink()
        return {"deleted": True}
    raise HTTPException(404, "Filter not found")


@app.post("/api/filter/import/edl")
async def import_edl(
    title: str,
    year: str = "",
    edl_content: str = "",
    authorization: str = Header(None),
):
    """Import an EDL (Edit Decision List) file."""
    verify_api_key(authorization)

    if not edl_content.strip():
        raise HTTPException(400, "EDL content is empty")

    segments = []
    for line in edl_content.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 3:
            continue
        try:
            start = float(parts[0]) * 1000
            end = float(parts[1]) * 1000
            code = int(parts[2])
        except ValueError:
            continue
        action = "SKIP" if code in (0, 3) else "MUTE" if code == 1 else None
        if action:
            segments.append({
                "startMs": int(start),
                "endMs": int(end),
                "action": action,
                "category": "PROFANITY",
                "word": None,
            })

    filter_file = {
        "title": title,
        "year": year,
        "source": "edl",
        "createdAt": int(time.time() * 1000),
        "segments": segments,
    }

    fp = filter_path(title, year)
    fp.write_text(json.dumps(filter_file, indent=2))

    return {"title": title, "segments": len(segments)}

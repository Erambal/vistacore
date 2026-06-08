@echo off
REM ─── VistaCore VOD Transcoder launcher (Windows) ───
REM Edit the paths below if ffmpeg/ffprobe aren't on your PATH.

REM set VC_FFMPEG=C:\ffmpeg\bin\ffmpeg.exe
REM set VC_FFPROBE=C:\ffmpeg\bin\ffprobe.exe
set VC_PORT=8088
set VC_NVENC_MAX=2

cd /d "%~dp0"
python -m uvicorn app:app --host 0.0.0.0 --port %VC_PORT%

@echo off
REM ─── VistaCore VOD Transcoder launcher (Windows) ───
REM If you unzipped ffmpeg somewhere other than C:\ffmpeg, fix these two paths.
set VC_FFMPEG=C:\ffmpeg\bin\ffmpeg.exe
set VC_FFPROBE=C:\ffmpeg\bin\ffprobe.exe
set VC_PORT=8088
set VC_NVENC_MAX=2

cd /d "%~dp0"

REM Sanity check: make sure ffmpeg actually exists before starting.
if not exist "%VC_FFMPEG%" (
  echo.
  echo [ERROR] ffmpeg not found at "%VC_FFMPEG%"
  echo Download ffmpeg-release-full from https://www.gyan.dev/ffmpeg/builds/
  echo unzip it so ffmpeg.exe is at that path, or edit VC_FFMPEG above.
  echo.
  pause
  exit /b 1
)

python -m uvicorn app:app --host 0.0.0.0 --port %VC_PORT%

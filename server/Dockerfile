FROM python:3.11-slim

# Install FFmpeg
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download the Whisper model so first run isn't slow
RUN python -c "from faster_whisper import WhisperModel; WhisperModel('base', device='cpu', compute_type='int8')"

COPY . .

# Create data directories
RUN mkdir -p /app/data/filters /app/data/audio_tmp

EXPOSE 8642

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8642"]

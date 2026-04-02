# VistaFilter Server Deployment

## Prerequisites
- Docker & Docker Compose on your VPS
- A subdomain pointed to the VPS (e.g. `filter.yourdomain.com`)
- Port 8642 available (or proxy through Enhance/Nginx)

## Quick Start

```bash
# 1. Clone or copy the server/ directory to your VPS
scp -r server/ user@your-vps:/opt/vistafilter/

# 2. SSH into the VPS
ssh user@your-vps
cd /opt/vistafilter

# 3. Create .env file
cp .env.example .env
nano .env
# Set VISTAFILTER_API_KEY to a secure random string

# 4. Build and run
docker compose up -d --build

# 5. Verify it's running
curl http://localhost:8642/api/health
# Should return: {"status":"ok","model":"base"}
```

## Reverse Proxy (Enhance / Nginx)

If Enhance manages your web server, add a reverse proxy for your subdomain.

**Nginx config** (add to your domain's server block or via Enhance):
```nginx
server {
    listen 443 ssl;
    server_name filter.yourdomain.com;

    # SSL handled by Enhance/Let's Encrypt

    location / {
        proxy_pass http://127.0.0.1:8642;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;  # long timeout for processing requests
    }
}
```

## Android App Setup

1. Go to **Settings > Content Filtering**
2. Enter server URL: `https://filter.yourdomain.com`
3. Enter API key (matches `VISTAFILTER_API_KEY` in .env)
4. Tap **Test Connection** — should say "Connected!"
5. Enable **Content filtering by default**

## Whisper Model Options

Set `WHISPER_MODEL` in `.env`:

| Model  | RAM   | Speed (2hr movie) | Accuracy |
|--------|-------|--------------------|----------|
| tiny   | ~1 GB | ~10 min            | Basic    |
| base   | ~1 GB | ~15 min            | Good     |
| small  | ~2 GB | ~30 min            | Better   |
| medium | ~5 GB | ~60 min            | Best     |

**Recommendation**: `base` for the Hostinger KVM 4 (good accuracy, reasonable speed).

## Managing Filters

```bash
# List all filters
curl -H "Authorization: Bearer YOUR_KEY" https://filter.yourdomain.com/api/filters

# Check job status
curl -H "Authorization: Bearer YOUR_KEY" https://filter.yourdomain.com/api/jobs

# Manually request a filter
curl -X POST -H "Authorization: Bearer YOUR_KEY" \
  -H "Content-Type: application/json" \
  -d '{"title":"Movie Name","year":"2024","stream_url":"http://..."}' \
  https://filter.yourdomain.com/api/filter/request
```

## Custom Profanity List

Create `/app/data/profanity.txt` (one word per line) to override the default list:

```bash
docker exec -it vistafilter bash
cat > /app/data/profanity.txt << 'EOF'
# Your custom word list
fuck
shit
# ... etc
EOF
```

The server reads this file at startup. Restart to pick up changes:
```bash
docker compose restart
```

## Logs

```bash
docker compose logs -f vistafilter
```

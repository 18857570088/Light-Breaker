# LightBreaker Backend

FastAPI service for LightBreaker multiplayer rooms.

## Local Run

```powershell
python -m venv .venv
.venv\Scripts\pip install -r backend\requirements.txt
$env:LIGHTBREAKER_DB_HOST="127.0.0.1"
$env:LIGHTBREAKER_DB_USER="wm"
$env:LIGHTBREAKER_DB_PASSWORD="<secret>"
$env:LIGHTBREAKER_DB_NAME="LightBreaker"
.venv\Scripts\uvicorn backend.lightbreaker_server:app --host 0.0.0.0 --port 8022
```

Public routes are mounted under `/lightbreaker/api/` and `/lightbreaker/ws/`.

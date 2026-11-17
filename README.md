# Globlingo

**Explore the world. Speak its languages.**

Globlingo is a language-learning platform that helps you learn a language by
watching real videos with interactive, clickable subtitles, word meanings,
translations, and an AI Tutor for grammar correction and speaking practice.

## Features

- **Multilingual video discovery** — Home, Explore, Trending and Shorts feed
  real YouTube videos via the YouTube Data API v3.
- **Interactive subtitles** — timed captions with clickable words, English
  meanings, translations, and click-to-seek.
- **Transcript pipeline** — Supadata captions (primary) with ScrapeCreators
  as a secondary API fallback, then yt-dlp captions and optional
  faster-whisper speech-to-text as last resorts.
- **AI Tutor** — grammar correction and speaking practice via browser speech
  recognition, backed by Gemini/Groq with LibreTranslate fallback.
- **User accounts** — email + OTP verification, guest mode, saved videos and
  channel subscriptions.
- **Connect with the World** — curated directory of language-exchange platforms.

## Tech stack

- **Frontend:** vanilla HTML/CSS/JavaScript (no framework), hash routing.
- **Backend:** Java 17+, Spring Boot 3, Spring Security, Spring Data JPA.
- **Database:** H2 (file mode) — swap the JDBC URL for any JPA-compatible DB.
- **Integrations:** YouTube Data API v3 + IFrame Player API, Supadata, ScrapeCreators,
  yt-dlp, faster-whisper, dictionaryapi.dev, LibreTranslate, Gemini, Groq,
  SMTP email.

## Project structure

```
Globlingo/                  # Frontend (static files)
  index.html  style.css  script.js  data.js  assets/
GloblingoDemoSpring/       # Backend (Spring Boot)
  src/main/java/...        # controllers, services, models, repositories
  src/main/resources/      # application.properties
  scripts/whisper_transcribe.py
  mvnw / pom.xml
```

## Setup

### Prerequisites

- Java 17+ and Maven (or use `./mvnw`)
- Python 3 + `yt-dlp` (`pip install yt-dlp`) for caption fallback
- Optional: `faster-whisper` for speech-to-text fallback

### Backend

```bash
cd GloblingoDemoSpring
export YOUTUBE_API_KEY=...        # required for video search
export SUPADATA_API_KEY=...       # primary transcript provider
export SCRAPECREATORS_API_KEY=... # secondary transcript fallback
export GEMINI_API_KEY=...         # AI Tutor / word meanings
export MAIL_HOST=... MAIL_USERNAME=... MAIL_PASSWORD=...  # OTP email
./mvnw spring-boot:run            # serves on http://localhost:8081
```

### Frontend

Serve the project root with any static server, e.g.:

```bash
python -m http.server 5500        # then open http://localhost:5500
```

The frontend talks to the backend at `http://localhost:8081/api` (or the same
origin in production).

### Environment variables

See `GloblingoDemoSpring/src/main/resources/application.properties` — every
secret is an env placeholder with a safe empty default. Key ones:

| Variable | Purpose |
|---|---|
| `YOUTUBE_API_KEY` | YouTube Data API search/trending |
| `SUPADATA_API_KEY` | Primary transcript provider |
| `SCRAPECREATORS_API_KEY` | Secondary transcript fallback |
| `GEMINI_API_KEY` / `GROQ_API_KEY` | AI Tutor, word meanings |
| `MAIL_*` | SMTP for OTP verification emails |
| `YTDLP_COMMAND` / `WHISPER_*` | Local transcript fallbacks |

## API endpoints (selection)

- `GET /api/videos/search`, `/api/videos/trending`, `/api/videos/shorts/search`
- `GET /api/videos/{id}/transcript?lang=...`
- `POST /api/word-meaning`, `POST /api/translate`
- `POST /api/auth/*` (login, register, verify, guest)
- `GET/POST/DELETE /api/saved`, `/api/subscriptions`
- `POST /api/tutor/check`

## Testing

```bash
cd GloblingoDemoSpring
./mvnw test
```

Frontend behaviour can be verified in any browser at the served URL; Shorts,
Watch subtitles and the AI Tutor are the main interactive surfaces.

## Status notes

- Implemented & verified: YouTube search/trending/shorts, timed subtitles with
  word popups, transcript fallbacks, AI Tutor, auth + OTP, saved videos,
  responsive layouts.
- Demo/sample catalogue entries exist alongside real YouTube content.
- faster-whisper fallback requires local install + `WHISPER_*` configuration.

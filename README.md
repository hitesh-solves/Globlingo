# Globlingo

Globlingo is a vanilla HTML/CSS/JavaScript frontend with a Spring Boot API. It
combines a clearly labelled sample learning experience with optional YouTube
search/trending, real YouTube playback, user accounts, private saved videos and
private in-app channel lists.

## What works

- Home, Explore, Shorts, the dictionary, and bundled captions/translations use
  hand-authored sample data. The sample video/short player advances a simulated
  clock; these entries do not represent playable source media.
- Trending and search call YouTube Data API v3 when `YOUTUBE_API_KEY` is set.
  Returned YouTube videos play in YouTube's privacy-enhanced embed. YouTube
  controls whether a video is playable and whether captions are available.
- Explore search supports YouTube's `relevance`, `date`, and `viewCount` sort
  orders and uses the official `nextPageToken` for Load More. Pages are
  de-duplicated by video ID. Topic hints and typed search text are combined;
  the API language is passed as a ranking hint and does not verify each video's
  language. `viewCount` is not an official trending feed;
  Trending uses YouTube's `mostPopular` chart endpoint and supports its
  continuation token when YouTube returns one. The chart is not an all-category
  Trending feed.
- The header picker contains a searchable set of commonly used ISO 639-1
  languages. Listing a language does not guarantee YouTube search support or
  available videos/captions. The API's `relevanceLanguage` is a ranking hint,
  not proof that each returned video is in that language; unknown videos remain
  labelled as language unavailable.
- Globlingo does not download arbitrary YouTube caption tracks. Caption-track
  listing and download require YouTube OAuth authorization; a public API key
  alone is not enough. For embedded YouTube playback, use the player's CC menu.
- Translation is currently unavailable from a provider. Sample dictionary
  material is labelled as a demo; no generated or fabricated live translation
  is returned.
- Signup/login use server-side sessions and BCrypt hashes. Saved videos and
  in-app channel subscriptions belong to the signed-in account. The subscription
  API records a Globlingo preference only; it does not change a YouTube account.
- Existing legacy `saved_videos` and `subscribed_channels` rows stay in the
  existing H2 schema. They are unassigned and are not exposed through the new
  authenticated APIs; no data claim/transfer has been performed.

## Project layout

- `index.html`, `style.css`, `script.js`, `data.js`, `assets/`: active frontend.
- `GloblingoDemoSpring/`: Spring Boot API and H2 persistence.
- `Globlingo/`: older frontend copy; it is not loaded by the root page.

## Local setup

1. Use Java 17. From `GloblingoDemoSpring`, start the backend with
   `.\mvnw.cmd spring-boot:run`
   It listens on port 8081. The existing H2 file remains at
   `GloblingoDemoSpring/data/globlingo`; do not delete it. Run only one backend
   process against this file at a time.
2. Serve the root frontend over HTTP, for example with VS Code Live Server on
   port 5500 or 5501. Do not use `file://`. Both `localhost` and `127.0.0.1`
   origins are allowed by default; the frontend calls the backend on the same
   hostname at port 8081. Same-origin hosting uses `/api`.
3. New visitors see the welcome screen and can create an account, log in, or
   continue as a guest. Guest bookmarks stay in that browser; account bookmarks
   are stored privately by the backend. Passwords must be at least 12
   characters and no more than 72 UTF-8 bytes.

## Configuration

- `PORT`: backend port (default `8081`).
- `YOUTUBE_API_KEY`: YouTube Data API v3 key, held on the backend. Configure API
  restrictions and quota monitoring in Google Cloud. No key is present in this
  checkout, so live API calls have not been verified.
- `APP_CORS_ALLOWED_ORIGINS`: comma-separated exact frontend origins, e.g.
  `https://learn.example.com`. Avoid wildcard origins with credentialed sessions.
- `SESSION_COOKIE_SECURE`: set `true` behind HTTPS in deployed environments;
  defaults to `false` for local HTTP.
- `SESSION_COOKIE_SAME_SITE`: defaults to `lax`. The current setup expects the
  frontend and API to be same-site. Cross-site deployments need a deliberate
  cookie/CORS/CSRF configuration review and HTTPS.
- `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`,
  `MAIL_SMTP_AUTH`, and `MAIL_SMTP_STARTTLS_ENABLE` (legacy alias
  `MAIL_SMTP_STARTTLS` is also accepted): SMTP settings for email OTP
  verification. `MAIL_HOST` and `MAIL_FROM` are required before new signup can
  proceed. Use provider-issued credentials stored in the process environment;
  never put them in frontend files or tracked properties.

For Gmail, enable 2-Step Verification and create a Google App Password for
Mail. Set `MAIL_HOST=smtp.gmail.com`, `MAIL_PORT=587`, `MAIL_USERNAME` to the
full Gmail address, `MAIL_PASSWORD` to that 16-character App Password,
`MAIL_FROM` to the same address, `MAIL_SMTP_AUTH=true`, and
`MAIL_SMTP_STARTTLS_ENABLE=true` in the backend process environment before
starting Spring Boot. A normal Gmail password will not authenticate SMTP.
For example, in PowerShell, set these with `$env:MAIL_HOST = 'smtp.gmail.com'`
and corresponding `$env:MAIL_*` assignments in the same terminal session.
- `H2_CONSOLE_ENABLED`: defaults to `false`; do not expose the console in
  deployment.
- `GLOBLINGO_API_BASE` (frontend): optional API base URL, set before loading
  `script.js` when the default `/api` or localhost routing does not apply.

CSRF protection is enabled for state-changing requests. The frontend obtains a
CSRF token from `/api/auth/csrf` and sends it in `X-XSRF-TOKEN`. Keep HTTPS,
secure cookies, an exact CORS allowlist, secret management, backups, rate
limiting, monitoring, and account recovery in mind before public launch.

The account flow stores a pending registration with BCrypt password and OTP
hashes, sends a six-digit time-limited code by configured SMTP, limits code
verification to five attempts, and enforces a 60-second resend cooldown. A user
record is created only after code verification; the user then logs in through
the existing BCrypt/session/CSRF flow. Existing account records remain usable.
Globlingo still lacks password reset, IP-based abuse controls, monitoring, and
account deletion; public registration needs those operational protections.

## Data and deployment

The backend keeps `jdbc:h2:file:./data/globlingo` and Hibernate `ddl-auto=update`.
New account-owned tables are additive; existing tables and rows are preserved.
Test configuration uses a separate in-memory H2 database. For private beta,
back up the H2 files while the backend is stopped and test restore procedures.
For multi-instance or public production deployment, a managed PostgreSQL
database is recommended. Plan an explicit schema/data migration, test it against
a copy, validate row counts and constraints, and keep a rollback backup before
switching; no migration has been run.

## Main API

- `GET /api/videos/search`, `/api/videos/trending`, `/api/videos/{videoId}` —
  YouTube Data API-backed; require the key.
- `GET /api/translate` — reports unavailable (503) until a provider is chosen
  and configured.
- `GET /api/auth/csrf`, `POST /api/auth/register`, `POST /api/auth/login`,
  `POST /api/auth/verify`, `POST /api/auth/resend-verification`,
  `GET /api/auth/me`, `POST /api/auth/logout` — email verification and session
  authentication.
- `GET/POST/DELETE /api/saved` — authenticated, account-scoped video records.
- `GET/POST/DELETE /api/subscriptions` — authenticated, account-scoped in-app
  channel preferences, not YouTube subscriptions.

Unauthenticated access to private APIs returns 401. Authorization scopes every
record query/deletion to the current account; knowing another record's ID does
not grant access.

## Verification

Run from `GloblingoDemoSpring` with `.\mvnw.cmd test`. Tests use only the
in-memory test profile. Check frontend syntax from the project root with
`node --check script.js`. The H2 file is opened in place by `spring-boot:run`;
run one backend process at a time and stop it from the terminal that started it.


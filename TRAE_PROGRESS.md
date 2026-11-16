# TRAE Progress Tracker — Globlingo

**Project:** Globlingo — "Explore the world. Speak its languages."
**Frontend:** `C:\Users\dell\Documents\GLOBLINGO\` (served on http://localhost:5500)
**Backend:** `C:\Users\dell\Documents\GLOBLINGO\GloblingoDemoSpring\` (Spring Boot, http://localhost:8081)
**Inactive copy (kept, unserved):** `C:\Users\dell\Documents\GLOBLINGO\Globlingo\`
**Last updated:** 2026-11-12

---

## 1. Previously completed (verified)

- **Active frontend / backend confirmed.** Browser loads the root `index.html` (cache busters `?v=7` on `style.css`, `data.js`, `script.js`). The nested `Globlingo\` copy is NOT served.
- **Supadata transcript fetching works.** `GET /api/videos/{id}/transcript?lang=xx` returns HTTP 200 with real caption chunks. The old `invalid-request` was caused by `RestTemplate` double-encoding the URL and an unsupported `chunkSize` param; fixed by building the URI with `java.net.URI` + explicit UTF-8 encoding.
- **YouTube Data API works** — `/api/videos/trending`, `/api/videos/search`, `/api/videos/shorts/search`, `/api/videos/{id}` return real data.
- **Windows loopback socket bug fixed** — services use `SimpleClientHttpRequestFactory` instead of `JdkClientHttpRequestFactory`.
- **Subtitle sync works** via `highlightYouTubeSubtitle()` polling `ytPlayer.getCurrentTime()` every 300 ms, toggling `.is-speaking` and scrolling the list ([script.js](file:///c:/Users/dell/Documents/GLOBLINGO/script.js)).
- **Maven tests: 20 / 20 pass, BUILD SUCCESS.**

## 2. Fixes applied (translation correctness)

- **Word popup translation direction fixed** — the popup used to send the *header* language for both the source and the target, which made MyMemory return `PLEASE SELECT TWO DISTINCT LANGUAGES` (`es|es`) for Hindi-caption videos. In [script.js](file:///c:/Users/dell/Documents/GLOBLINGO/script.js) `openWordPopup()` now uses the **caption language** as the source (`languageNameForCode(youtubeSubtitles.lang)`) and the **header language** as the target, then labels the result with the target language name.
- **Backend equal-language guard** added in [WordMeaningService.java](file:///c:/Users/dell/Documents/GLOBLINGO/GloblingoDemoSpring/src/main/java/com/example/globlingo/service/WordMeaningService.java) `fallbackTranslate()`: when source == target it returns an honest "already in your selected language" message instead of calling the provider.
- **Non-200 provider status ignored** in `pickSingleWordTranslation()`: a provider response with `responseStatus != 200` carries no usable translation and is skipped.
- **Quality fallback** (`pickSingleWordTranslation` / `shorterTranslation`) kept: prefers `matches` entries whose source segment equals the word and keeps the shortest non-echo candidate.
- **Explore + Home categories aligned** to `Vlogs, Comedy, Stand-up, Prank, Sports, Games` ([data.js](file:///c:/Users/dell/Documents/GLOBLINGO/data.js)).
- **Shorts action rail** moved inside the video frame as a right-side vertical rail.
- **Stale backend bytecode replaced** — old JVM stopped, `target/globlingo-0.0.1-SNAPSHOT.jar` rebuilt and restarted (env vars loaded from `.idea/workspace.xml`, never printed).

## 3. Real interaction tests (headless Chrome via CDP, real mouse clicks)

Every result below came from actually clicking elements (`Input.dispatchMouseEvent`) and reading the live DOM — not from static rendering.

- **Home "Load more" (item 1):** `items=12 unique=12` → click `#home-more` → `items=24 unique=24`, note text `🇪🇸 Spanish · 24 YouTube videos`; network shows a second call with `pageToken=CAwQAA`. No duplicates.
- **Explore (items 1, 7):** category dropdown = `all,Vlogs,Comedy,Stand-up,Prank,Sports,Games`; selecting `Comedy` re-queried YouTube for `comedy funny`; sort `viewCount` re-queried with `order=viewCount`; Load more `12 → 24 unique` (`pageToken=CAwQAA`).
- **Trending (item 1):** `items=12 unique=12` → Load more → `items=24 unique=24` (`pageToken=CAwQAA`).
- **Shorts (item 8):** 12 `.short-tall`; computed `snapType=y mandatory`, `overflowY=auto`, `snapAlign=start`; action rail `insideFrame=true gapRight=12 flexDir=column` (vertical right rail inside the frame); `instagramMentions=0`; scroll works; Load more `12 → 23 unique`.
- **Word popup (item 3):** Watch `#/watch/yNzkQilIP4o` shows `YouTube captions · hi`, 246 subtitle lines, 1779 clickable words. Clicking `.sub:nth-child(2|5|9) .w` opened the popup with `दे` / `लंगड़ा` / `बात`; `popupStillOpen=true`; the example line is the real Hindi caption.
- **Translation follows header language (item 4):** same word `दे` → `Dar · Spanish` with the Spanish header and `Donner · French` with the French header; `लंगड़ा → Boiteux · French`, `बात → Conversation · French`. Network shows **one** `POST /api/ai-tutor/word-meaning` per click (plus a single `OPTIONS` CORS preflight).
- **Timestamp seek (item 5):** clicking a subtitle timestamp calls `ytPlayer.seekTo(target, true)`; `seekTarget=5.44 → playerTime=5.4`, and paused `seekTarget=20.48 → afterSeek=21.5`.
- **Highlight follows playback (item 6):** after `playVideo()`, `highlight1=13.599 @ nowTime=14.5`, `highlight2=18.4 @ nowTime2=20.5`, `isPlaying=1`.
- **Signup / verify / login / logout (item 9):** register returns `202` with the generic message; the verify form appears; a real emailed OTP (`052186`) was accepted → "Email verified…"; login → `#/home`, `#account-status=Your Globlingo account`; reload keeps the session; logout → `#/welcome`.
- **Duplicate email (item 9):** re-registering the verified account returns **409** with `An account with this email already exists. Please log in.`, reveals `#existing-login-button`, prefills the login email, and does **not** issue another OTP.
- **OTP delivery (item 9):** with the configured SMTP, the first register attempt delivered exactly **1** email (subject "Your Globlingo verification code"); a second attempt inside the 60 s cooldown sent none (cooldown works).
- **Saved videos persist after refresh (item 10):** signed in, saving a video then opening `#/saved` shows the items (`savedCount1=2`); after a full page reload the Saved page still shows `savedCount2=2` and the session is intact (`accountStatus=Your Globlingo account`).
- **Console / network health (item 11):** across Home, Explore, Trending, Shorts, Watch and Account the only entries are `401 /api/auth/me` (expected for a guest) and `404 /favicon.ico`; `NET_FAILED (0)`; no unexpected 5xx. The only `409` seen is the intentional duplicate-email test.
- **Backend log / freshness (item 12):** the running JVM is PID 6552 started `2026-11-12T14:26:46` (newer than the last backend source change at 14:24:14), `Tomcat started on port 8081`, `Transcript provider HTTP 200`. OpenAI returns `429 … insufficient_quota` → the service logs one warning and falls back to translation; there is **no retry loop**. Repeated identical words are served from the service cache.

## 4. Blockers (honest)

- **AI Tutor grammar correction and AI-generated contextual explanations are blocked by the OpenAI account's exhausted credit balance** (`insufficient_quota` / `credit_balance_exhausted`, HTTP 429). The backend returns an honest 503 for grammar checking; it does **not** fabricate corrections or use hardcoded responses. These features require an API account with available credits.
- Word-context **meanings/explanations** therefore come from the translation provider (MyMemory) fallback path; the same word is correctly translated into the header language, but the rich "simple explanation" text requires OpenAI credits.

## 5. Remaining tasks

1. Re-verify AI Tutor grammar output once OpenAI credits/keys are active (cannot be validated against a quota-exhausted account).
2. Optional: exercise the Shorts/YouTube embed autoplay on a real (non-headless) browser.

## 6. Environment / how to run

- **Backend:** env vars are read from `GloblingoDemoSpring\.idea\workspace.xml` run configuration (`YOUTUBE_API_KEY`, `OPENAI_API_KEY`, `OPENAI_MODEL`, `SUPADATA_API_KEY`, `MAIL_*`, `MAIL_FROM`). Build with `.\mvnw.cmd -DskipTests package`, run `java -jar .\target\globlingo-0.0.1-SNAPSHOT.jar` on port 8081. Values of API keys are never printed or committed.
- **Frontend:** served statically on port 5500 with `?v=7` cache busters.
- **CORS:** `app.cors.allowed-origins` includes 5500 / 5501 / 3000. Browser POSTs to the backend emit an `OPTIONS` preflight in addition to the `POST` — this is normal CORS, not a duplicate request.

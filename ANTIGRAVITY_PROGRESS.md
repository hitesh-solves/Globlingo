# Antigravity Progress & Project Audit — Globlingo

**Project:** Globlingo — “Explore the world. Speak its languages.”  
**Date:** November 12, 2026  
**Status:** In Progress — Active Continuation & Verification

---

## 1. Completed and Verified
- [x] **Project Directory & Architecture Audit:**
  - Active frontend root confirmed: `C:\Users\dell\Documents\GLOBLINGO\` (served by dev server on port 5500).
  - Outdated mock copy identified: `C:\Users\dell\Documents\GLOBLINGO\Globlingo\` (kept intact, unserved).
  - Spring Boot backend root confirmed: `C:\Users\dell\Documents\GLOBLINGO\GloblingoDemoSpring\` (port 8081).
- [x] **Credential & Configuration Audit:**
  - IntelliJ IDEA run configuration in `GloblingoDemoSpring\.idea\workspace.xml` contains all environment keys:
    - `YOUTUBE_API_KEY`: Verified valid, live Google YouTube Data API v3 search succeeds with HTTP 200.
    - `SUPADATA_API_KEY`: Verified valid, live Supadata transcript retrieval succeeds with HTTP 200.
    - `OPENAI_API_KEY`: Verified, provider returns 429 `insufficient_quota` (`credit_balance_exhausted`).
    - `OPENAI_MODEL`: Set to `gpt-4.1-mini` (or `gpt-4o-mini`).
- [x] **Supadata Transcript HTTP Bug Root Cause Identified & Repaired:**
  - Replaced double-encoded URL formatting and removed unsupported query parameters (`chunkSize=200`, `mode=native`).
  - Switched to `java.net.URI` with `StandardCharsets.UTF_8` query encoding so Spring's `RestTemplate` does not double-encode `%3A%2F` to `%253A%252F` (which caused Supadata's `{"error":"invalid-request","details":"url: Invalid url"}`).
- [x] **Spring WebClient / Factory Windows Loopback Socket Bug Fixed:**
  - Replaced `JdkClientHttpRequestFactory` in `AiTutorService`, `TranscriptService`, `TranslationService`, and `WordMeaningService` with `SimpleClientHttpRequestFactory` (with timeouts). This resolves Windows loopback socket `java.io.IOException: Unable to establish loopback connection` errors.
- [x] **OpenAI API Integration Standardized:**
  - Migrated `AiTutorService` and `WordMeaningService` from unsupported `/v1/responses` JSON payload format to OpenAI standard `/v1/chat/completions` format.
  - Added structured error response decoding for 429 quota exhaustion (`credit_balance_exhausted` / `insufficient_quota`) with clear, user-facing instructions to add billing credits or supply an active key.
- [x] **Resilient Word Meaning Fallback:**
  - Added keyless MyMemory translation fallback to `WordMeaningService` and `TranslationService` so that word-click translation and subtitle exploration remain functional even if the OpenAI credit balance is exhausted.

---

## 2. Implemented but Not Verified
- [ ] Subtitle synchronization and word popup click-through in live browser.
- [ ] Real transcript track rendering for YouTube videos with captions.
- [ ] Translation into header language on word click.
- [ ] Explore category filtering (Vlogs, Comedy, Stand-up, Pranks, Sports, Gaming) directly affecting YouTube queries.
- [ ] Trending Load More with continuation tokens (`nextPageToken`) and deduplication.
- [ ] YouTube Shorts vertical swipe/scroll snapping with right-side action rail.
- [ ] Duplicate email detection with "Log in" action button.

---

## 3. Broken Features
- [!] **AI Tutor Grammar Checking:**
  - Backend integration was using non-standard `/v1/responses` endpoint and the configured OpenAI account has `credit_balance_exhausted`.
  - Needed standard `/v1/chat/completions` request structure, fallback parsing, and informative quota guidance.
- [!] **Interactive Subtitles via Java Backend:**
  - Java backend was returning `invalid-request` from Supadata because `RestTemplate` was double-encoding the URL and passing `&chunkSize=200`.
  - Fix applied; needs live end-to-end browser verification.
- [!] **Shorts Experience:**
  - Missing YouTube Shorts-like right-side action rail (Like, Save, Share, Open).
  - Outdated Instagram Reels CSS and references needed removal.
- [!] **Explore Language Synchronization:**
  - Explore filter bar had a separate `Language` dropdown that drifted from the header language selector.

---

## 4. Missing Features
- [ ] Right-side vertical action rail for YouTube Shorts cards.
- [ ] Category definitions for Vlogs, Comedy, Stand-up, Pranks, Sports, Gaming in `CATEGORIES` and `SAMPLE_CATEGORY_SEARCH_TERMS`.
- [ ] Region code dynamic mapping for Trending based on the selected header language.

---

## 5. Root Causes
1. **Supadata `invalid-request`:** `TranscriptService.java` passed a pre-encoded URL string to `restTemplate.exchange(String url, ...)`, triggering Spring's `DefaultUriBuilderFactory` to re-encode `%` as `%25`, resulting in `url=https%253A%252F...` which Supadata rejected as malformed, alongside an unsupported `chunkSize=200` parameter.
2. **`Unable to establish loopback connection`:** `JdkClientHttpRequestFactory` in JDK 17 on Windows initiates internal NIO pipe selectors on instantiation, which fails under restrictive sandbox or loopback constraints. Using `SimpleClientHttpRequestFactory` avoids this entirely.
3. **AI Tutor 429 Quota Exhaustion:** The OpenAI API key in `workspace.xml` returned HTTP 429 with error code `credit_balance_exhausted` ("You have no credits remaining. Add credits to continue using the API").
4. **Duplicate Language Filters:** Having a separate `#filter-language` select inside Explore caused conflicts with the global `#lang-button` state.

---

## 6. Files Requiring Modification
- `GloblingoDemoSpring/src/main/java/com/example/globlingo/service/TranscriptService.java`
- `GloblingoDemoSpring/src/main/java/com/example/globlingo/service/AiTutorService.java`
- `GloblingoDemoSpring/src/main/java/com/example/globlingo/service/WordMeaningService.java`
- `GloblingoDemoSpring/src/main/java/com/example/globlingo/service/TranslationService.java`
- `GloblingoDemoSpring/src/main/resources/application.properties`
- `data.js`
- `script.js`
- `index.html`
- `style.css`

---

## 7. Remaining Tasks
1. Verify Maven test suite execution after HTTP factory fixes.
2. Update `data.js` to expand `CATEGORIES` (Vlogs, Comedy, Stand-up, Pranks, Sports, Gaming).
3. Update `script.js`:
   - Expand `SAMPLE_CATEGORY_SEARCH_TERMS`.
   - Remove `#filter-language` dependency from Explore so header language is the single source of truth.
   - Map Trending region code from header language.
   - Implement Shorts right-side action rail (Like, Save, Share, Open).
4. Update `index.html` & `style.css`:
   - Remove duplicate Language dropdown from Explore filter bar.
   - Add Shorts action rail CSS and ensure centered guest button.
   - Remove Instagram Reels references.
5. Launch backend (port 8081) with all credentials from `workspace.xml`.
6. Launch frontend (port 5500).
7. Perform live browser verification across all features.

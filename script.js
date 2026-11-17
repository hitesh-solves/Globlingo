/* ==========================================================================
   Globlingo — script.js
   --------------------------------------------------------------------------
   This is the "brain" of the app. It is organised in these parts:

     1.  What we remember (a tiny bit of localStorage)
     2.  Small helper functions
     3.  Backend API integration
     4.  Video cards
     5.  Home page
     6.  Explore page
     7.  Trending page
     8.  Saved Videos page
     9.  Shorts page
    10. The demo player (simulated playback)
    11. Watch page + clickable subtitles (the core feature)
    12. The word popup
    13. Router — which page is on screen
    14. Wiring up all the events

   Backend API available at http://localhost:8081
   Bundled sample data is separate; live search errors are shown as errors.
   ========================================================================== */


/* ==========================================================================
   1. WHAT WE REMEMBER
   Only two things are worth keeping between visits: the language the learner
   picked, and how far through a video they got (so the progress bar and the
   player can pick up where they left off).
   ========================================================================== */

const STORAGE_KEY = 'globlingo.state.v1';

const DEFAULT_STATE = {
  language: 'es',   // 2-letter code of the language the user wants to learn
  progress: {},     // { videoId: 0.42 } — how far through a video they are
};

let state = loadState();

/* Values that only matter while the page is open (never saved). */
const filters = {
  query: '',            // text typed into the search box
  language: 'all',      // Explore dropdown
  category: 'all',      // Explore dropdown
  topic: 'all',         // YouTube discovery topic
  homeCategory: 'All',  // the chip selected on the Home page
};
const SAMPLE_CATEGORY_SEARCH_TERMS = {
  Vlogs: 'vlogs daily life',
  Comedy: 'comedy funny',
  'Stand-up': 'stand-up comedy',
  Prank: 'pranks',
  Sports: 'sports',
  Games: 'gaming gameplay',
};

let currentVideo = null;      // the video open on the Watch page
let watchReturnHash = '#/home';
let translationsVisible = true; // are the English subtitle lines showing?
let openWordButton = null;    // the subtitle word whose popup is open
let lastRenderedView = null;  // lets us scroll to the top only when the page changes


/* ==========================================================================
   2. SMALL HELPER FUNCTIONS
   ========================================================================== */

/* Shortcut for document.querySelector. */
function qs(selector) {
  return document.querySelector(selector);
}

/* Shortcut for querySelectorAll that returns a real array. */
function qsa(selector) {
  return Array.from(document.querySelectorAll(selector));
}

/* Find a language object by its 2-letter code. */
function languageByCode(code) {
  return LANGUAGES.find((lang) => lang.code === code) || LANGUAGES[0];
}

/* Language name for a code, falling back to the raw code (captions can be in
   languages outside the 71-item picker list). */
function languageNameForCode(code) {
  const found = LANGUAGES.find((lang) => lang.code === code);
  return found ? found.name : (code || 'en');
}

/* 540 seconds -> "9:00". Used on cards and on the player clock. */
function formatTime(totalSeconds) {
  const safe = Math.max(0, Math.round(totalSeconds || 0));
  const minutes = Math.floor(safe / 60);
  const seconds = safe % 60;
  return minutes + ':' + String(seconds).padStart(2, '0');
}

/* 540 seconds -> "9 min". Used in the line under a video title. */
function formatDuration(seconds) {
  if (seconds < 60) return seconds + ' sec';
  return Math.round(seconds / 60) + ' min';
}

/* Never drop raw text straight into HTML. */
function escapeHTML(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/* A copy of a plain object, so the defaults above are never modified. */
function deepCopy(value) {
  return JSON.parse(JSON.stringify(value));
}

/* Show a short message above the bottom of the screen. */
function toast(message) {
  const box = document.createElement('div');
  box.className = 'toast';
  box.innerHTML = '<svg class="icon"><use href="#i-globe"></use></svg><span>' +
    escapeHTML(message) + '</span>';
  qs('#toast-region').appendChild(box);

  setTimeout(() => {
    box.classList.add('is-hiding');
    setTimeout(() => box.remove(), 260);
  }, 2600);
}

/* A neutral placeholder image, used when a thumbnail cannot load. */
const FALLBACK_THUMB =
  'data:image/svg+xml;charset=utf-8,' +
  encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180">' +
      '<rect width="320" height="180" fill="#242424"/>' +
      '<text x="160" y="102" font-size="48" text-anchor="middle">🎬</text>' +
      '</svg>'
  );

/* Called from every <img onerror="…">. Instead of showing a broken image we
   draw a small placeholder that keeps the layout intact. */
function thumbFailed(img) {
  const holder = img.closest('.card__thumb');

  if (holder && !holder.querySelector('.card__fallback')) {
    const flag = holder.dataset.flag || '🎬';
    img.style.display = 'none';
    const box = document.createElement('div');
    box.className = 'card__fallback';
    box.innerHTML = '<span>' + flag + '</span><small>No preview</small>';
    holder.appendChild(box);
    return;
  }

  img.onerror = null;
  img.src = FALLBACK_THUMB;
}

// Reachable from the inline onerror="" attribute in our HTML strings.
window.thumbFailed = thumbFailed;

/* Find a video object by its id. */
function findVideo(videoId) {
  return VIDEOS.find((video) => video.id === videoId) || cardVideos.get(videoId);
}

/* ==========================================================================
   3. BACKEND API INTEGRATION
   The backend runs on http://localhost:8081. If it's unavailable, we fall
   back to the demo data in data.js.
   ========================================================================== */

const API_BASE = (window.GLOBLINGO_API_BASE ||
  (window.location.protocol === 'file:' || window.location.port === '5500' || window.location.port === '5501'
    ? 'http://' + (window.location.hostname || 'localhost') + ':8081/api'
    : window.location.origin + '/api')).replace(/\/$/, '');
const savedVideoIds = new Set();
const cardVideos = new Map();
let backendAvailable = null;
let latestSearchQuery = '';
let activeExternalSearch = null;
let exploreFreshDays = null;   // rotating publishedAfter window for Explore refresh
let searchRequestSequence = 0;
let searchLoadingMore = false;
let currentUser = null;
let csrfToken = null;
const GUEST_MODE_KEY = 'globlingo.guest-mode';
const GUEST_SAVED_KEY = 'globlingo.guest-saved.v1';
let guestMode = false;
let authScreen = 'welcome';
let pendingVerificationEmail = '';
try { guestMode = sessionStorage.getItem(GUEST_MODE_KEY) === 'true'; } catch (error) { guestMode = false; }
try { JSON.parse(localStorage.getItem(GUEST_SAVED_KEY) || '[]').forEach((video) => { if (video && video.id) savedVideoIds.add(video.id); }); } catch (error) { /* Local guest saves can be unavailable in private browsing. */ }

/* Fetch with error handling and fallback to demo data */
async function fetchAPI(endpoint, fallbackData = []) {
  try {
    const response = await fetch(API_BASE + endpoint, { signal: AbortSignal.timeout(5000), credentials: 'include' });
    if (!response.ok) throw new Error('API request failed');
    const data = await response.json();
    if (data && typeof data.success === 'boolean') backendAvailable = true;
    if (data.success && data.data !== undefined) {
      return data.data;
    }
    return fallbackData;
  } catch (error) {
    backendAvailable = false;
    console.warn('API unavailable, using demo data:', error.message);
    return fallbackData;
  }
}

/* Search videos via backend API */
const videoRequestInflight = new Map();
async function fetchVideoResults(endpoint) {
  // Coalesce identical concurrent requests (double renders, rapid tab switches).
  if (videoRequestInflight.has(endpoint)) return videoRequestInflight.get(endpoint);
  const promise = doFetchVideoResults(endpoint);
  videoRequestInflight.set(endpoint, promise);
  try { return await promise; } finally { videoRequestInflight.delete(endpoint); }
}

async function doFetchVideoResults(endpoint) {
  try {
    const response = await fetch(API_BASE + endpoint, { credentials: 'include', signal: AbortSignal.timeout(15000) });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) return { status: 'error', message: result.message || `Video service returned HTTP ${response.status}.` };
    if (!result.success) return { status: 'error', message: result.message || 'The video service could not complete the request.' };
    const payload = Array.isArray(result.data) ? { videos: result.data } : result.data;
    const videos = payload && Array.isArray(payload.videos) ? payload.videos : [];
    return {
      status: videos.length ? 'ok' : 'empty',
      videos,
      nextPageToken: result.nextPageToken || (payload && payload.nextPageToken) || null,
      message: result.message || ''
    };
  } catch (error) {
    return { status: 'error', message: error.name === 'TimeoutError'
      ? 'YouTube is taking too long to respond. Please retry.'
      : `Could not reach the video service at ${API_BASE}. Start the backend and check its allowed frontend origins.` };
  }
}

async function searchVideosAPI(query, language, pageToken = null, order = 'relevance', maxResults = 12, freshDays = null) {
  const languageCode = LANGUAGES.some((item) => item.code === language) ? language : state.language;
  const params = new URLSearchParams({ query, language: languageCode, maxResults: String(maxResults), order });
  if (pageToken) params.set('pageToken', pageToken);
  if (freshDays) params.set('freshDays', String(freshDays));
  return fetchVideoResults(`/videos/search?${params.toString()}`);
}

async function getTrendingVideosPageAPI(pageToken = null) {
  const params = new URLSearchParams({ regionCode: 'US', maxResults: '12' });
  if (pageToken) params.set('pageToken', pageToken);
  return fetchVideoResults(`/videos/trending?${params.toString()}`);
}

async function searchYouTubeShortCandidates(query, language, pageToken = null, order = 'date', freshDays = null) {
  const params = new URLSearchParams({ query, language, maxResults: '12', order });
  if (pageToken) params.set('pageToken', pageToken);
  if (freshDays) params.set('freshDays', String(freshDays));
  return fetchVideoResults(`/videos/shorts/search?${params.toString()}`);
}

/* Fetch a real caption transcript for a YouTube video (timestamps in seconds). */
async function fetchTranscript(videoId, language) {
  const params = new URLSearchParams({ lang: language || state.language });
  try {
    const response = await fetch(API_BASE + '/videos/' + encodeURIComponent(videoId) + '/transcript?' + params.toString(),
      { credentials: 'include', signal: AbortSignal.timeout(30000) });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.success) {
      return { status: 'error', message: result.message || 'Subtitles could not be loaded.' };
    }
    const data = result.data || {};
    const chunks = Array.isArray(data.chunks) ? data.chunks : [];
    // Normalize: parse timestamps defensively, sort chronologically, and drop
    // exact duplicate chunks (same text AND same start) that providers emit.
    const seen = new Set();
    const lines = chunks
      .filter((chunk) => chunk && typeof chunk.text === 'string' && chunk.text.trim())
      .map((chunk) => {
        let t = Number(chunk.offsetSeconds);
        if (!isFinite(t) || t < 0) {
          // Tolerate providers that send raw start/end or string timestamps.
          const raw = chunk.start !== undefined ? chunk.start : chunk.offset;
          t = typeof raw === 'string' ? parseTimestamp(raw) : (Number(raw) || 0);
          if (t > 10000) t = t / 1000; // ms → s
        }
        let d = Number(chunk.durationSeconds);
        if (!isFinite(d) || d <= 0) {
          const rawEnd = Number(chunk.endSeconds !== undefined ? chunk.endSeconds : chunk.end);
          d = isFinite(rawEnd) && rawEnd > t ? rawEnd - t : 3;
        }
        return { time: t, end: t + d, text: chunk.text.trim() };
      })
      .filter((line) => {
        const key = line.time.toFixed(2) + '|' + line.text;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      })
      .sort((a, b) => a.time - b.time);
    return lines.length
      ? { status: 'ok', lang: data.lang || language, lines }
      : { status: 'empty', message: 'Subtitles are not available for this video.' };
  } catch (error) {
    return { status: 'error', message: error.name === 'TimeoutError'
      ? 'The transcript service is taking too long. Please retry.'
      : 'Could not reach the transcript service. Check that the backend is running.' };
  }
}

/* Parse "h:mm:ss(.fff)" / "mm:ss(.fff)" timestamp strings to seconds. */
function parseTimestamp(str) {
  const parts = String(str).trim().split(':');
  if (parts.length === 3) return (+parts[0]) * 3600 + (+parts[1]) * 60 + parseFloat(parts[2]);
  if (parts.length === 2) return (+parts[0]) * 60 + parseFloat(parts[1]);
  return parseFloat(str) || 0;
}

/* Ask the backend for a clicked word's definition, translation and AI
   explanation. The backend combines the Free Dictionary API, LibreTranslate and
   Gemini, so one failing provider never hides the others. */
async function fetchWordMeaning(word, sentence, learningLanguage, explanationLanguage, learningLanguageCode, explanationLanguageCode) {
  try {
    if (!await refreshCsrfToken()) return { status: 'error', message: 'Could not reach the account service.' };
    const response = await fetch(API_BASE + '/ai-tutor/word-meaning', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}) },
      body: JSON.stringify({
        selectedWord: word,
        subtitleSentence: sentence,
        learningLanguage,
        explanationLanguage,
        learningLanguageCode: learningLanguageCode || null,
        explanationLanguageCode: explanationLanguageCode || null,
      }),
      signal: AbortSignal.timeout(40000),
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) return { status: 'error', message: result.message || 'Word details are unavailable.' };
    return { status: 'ok', data: result };
  } catch (error) {
    return { status: 'error', message: error.name === 'TimeoutError'
      ? 'Looking this word up is taking too long. Please retry.'
      : 'Could not reach the word-details service.' };
  }
}

/* Save a video */
async function saveVideoAPI(videoDTO) {
  try {
    const response = await fetch(API_BASE + '/saved', {
      method: 'POST',
      credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}) },
      body: JSON.stringify(videoDTO)
    });
    if (!response.ok) return false;
    const data = await response.json();
    return data.success;
  } catch (error) {
    console.error('Failed to save video:', error);
    return false;
  }
}

/* Unsave a video */
async function unsaveVideoAPI(videoId) {
  try {
    const response = await fetch(API_BASE + '/saved/' + encodeURIComponent(videoId), {
      method: 'DELETE', credentials: 'include', headers: csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}
    });
    if (!response.ok) return false;
    const data = await response.json();
    return data.success;
  } catch (error) {
    console.error('Failed to unsave video:', error);
    return false;
  }
}

/* Get all saved videos */
async function getSavedVideosAPI() {
  return await fetchAPI('/saved');
}

/* Check if a video is saved */
async function isVideoSavedAPI(videoId) {
  try {
    const response = await fetch(API_BASE + '/saved/' + encodeURIComponent(videoId) + '/status', { credentials: 'include' });
    if (!response.ok) return false;
    const data = await response.json();
    return data.success ? data.data : false;
  } catch (error) {
    return false;
  }
}

/* Subscribe to a channel (in-app only, not YouTube subscription) */
async function subscribeAPI(channelId, channelName, thumbnail) {
  try {
    const response = await fetch(API_BASE + '/subscriptions?channelId=' + encodeURIComponent(channelId) + '&channelName=' + encodeURIComponent(channelName) + '&thumbnail=' + encodeURIComponent(thumbnail || ''), {
      method: 'POST', credentials: 'include', headers: csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}
    });
    if (!response.ok) return false;
    const data = await response.json();
    return data.success;
  } catch (error) {
    console.error('Failed to subscribe:', error);
    return false;
  }
}

/* Unsubscribe from a channel */
async function unsubscribeAPI(channelId) {
  try {
    const response = await fetch(API_BASE + '/subscriptions/' + encodeURIComponent(channelId), {
      method: 'DELETE', credentials: 'include', headers: csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}
    });
    if (!response.ok) return false;
    const data = await response.json();
    return data.success;
  } catch (error) {
    console.error('Failed to unsubscribe:', error);
    return false;
  }
}

/* Get all subscriptions */
async function getSubscriptionsAPI() {
  return await fetchAPI('/subscriptions');
}

function getCookie(name) {
  try {
    const match = document.cookie.match(new RegExp('(^|;\\s*)' + name + '=([^;]*)'));
    return match ? decodeURIComponent(match[2]) : null;
  } catch (error) { return null; }
}

async function refreshCsrfToken() {
  try {
    const response = await fetch(API_BASE + '/auth/csrf', { credentials: 'include' });
    if (!response.ok) return false;
    const cookieToken = getCookie('XSRF-TOKEN');
    if (cookieToken) {
      csrfToken = cookieToken;
    } else {
      const result = await response.json();
      csrfToken = result.token || null;
    }
    return Boolean(csrfToken);
  } catch (error) { return false; }
}

async function refreshAccount() {
  await refreshCsrfToken();
  try {
    const response = await fetch(API_BASE + '/auth/me', { credentials: 'include' });
    if (!response.ok) currentUser = null;
    else {
      const result = await response.json();
      currentUser = result.success ? result.data : null;
    }
  } catch (error) { currentUser = null; }
  if (currentUser) {
    guestMode = false;
    try { sessionStorage.removeItem(GUEST_MODE_KEY); } catch (error) { /* Optional session preference. */ }
    await loadSavedVideoIds();
    if (!location.hash || location.hash === '#/welcome') navigate('#/home');
  }
  updateModeBadge();
  updateAccountView();
}

function updateModeBadge() {
  const badge = qs('#mode-chip');
  if (!badge) return;
  badge.textContent = currentUser ? 'Account' : guestMode ? 'Guest · Sign in' : 'Welcome';
  badge.setAttribute('aria-label', currentUser ? 'Account settings' : guestMode ? 'Guest mode, open account options' : 'Welcome, choose an account option');
  const sidebarLabel = qs('#nav-account-label');
  if (sidebarLabel) sidebarLabel.textContent = currentUser ? 'Account' : guestMode ? 'Guest · Account' : 'Account';
}

function showAuthScreen(screen) {
  authScreen = screen;
  qs('#auth-screen-welcome').hidden = screen !== 'welcome';
  qs('#register-form').hidden = screen !== 'register';
  qs('#verify-form').hidden = screen !== 'verify';
  qs('#login-form').hidden = screen !== 'login';
  qs('#account-profile').hidden = !currentUser && !guestMode;
  qs('#auth-message').hidden = true;
  qs('#auth-message').textContent = '';
  qs('#existing-login-button').hidden = true;
  qs('#account-status').textContent = currentUser ? 'Signed in' : guestMode ? 'You’re browsing as a guest.' : 'Choose how you’d like to use Globlingo.';
}

function continueAsGuest() {
  currentUser = null;
  guestMode = true;
  try { sessionStorage.setItem(GUEST_MODE_KEY, 'true'); } catch (error) { /* Guest mode remains available until this page closes. */ }
  savedVideoIds.clear();
  try { JSON.parse(localStorage.getItem(GUEST_SAVED_KEY) || '[]').forEach((video) => { if (video && video.id) savedVideoIds.add(video.id); }); } catch (error) { /* Keep browsing without local saves. */ }
  updateModeBadge();
  navigate('#/home');
}

function leaveGuestMode() {
  guestMode = false;
  try { sessionStorage.removeItem(GUEST_MODE_KEY); } catch (error) { /* Optional session preference. */ }
  authScreen = 'welcome';
  updateModeBadge();
  navigate('#/welcome');
}

function setAuthMessage(message, isError = true) {
  const box = qs('#auth-message');
  box.textContent = message;
  box.classList.toggle('is-error', isError);
  box.classList.toggle('is-success', !isError);
  box.hidden = !message;
  qs('#existing-login-button').hidden = true;
}

function updateAccountView() {
  if (!qs('#account-status')) return;
  updateModeBadge();
  if (currentUser) {
    qs('#auth-screen-welcome').hidden = true;
    qs('#register-form').hidden = true;
    qs('#verify-form').hidden = true;
    qs('#login-form').hidden = true;
    qs('#account-profile').hidden = false;
    qs('#account-profile-copy').textContent = 'Signed in as ' + currentUser.email + '. Your saved videos are private to your account.';
    qs('#leave-guest-button').hidden = true;
    qs('#logout-button').hidden = false;
    qs('#account-status').textContent = 'Your Globlingo account';
  } else if (guestMode) {
    qs('#auth-screen-welcome').hidden = true;
    qs('#register-form').hidden = true;
    qs('#verify-form').hidden = true;
    qs('#login-form').hidden = true;
    qs('#account-profile').hidden = false;
    qs('#account-profile-copy').textContent = 'You’re browsing as a guest. Guest saved videos stay in this browser.';
    qs('#leave-guest-button').hidden = false;
    qs('#logout-button').hidden = true;
    qs('#account-status').textContent = 'Guest mode';
  } else {
    showAuthScreen(authScreen);
  }
}

async function submitAuth(form, endpoint) {
  const data = new FormData(form);
  const email = String(data.get('email') || '').trim();
  const password = String(data.get('password') || '');
  if (!email || !form.elements.email.validity.valid) { setAuthMessage('Enter a valid email address.'); form.elements.email.focus(); return; }
  if (!password) { setAuthMessage('Enter your password.'); form.elements.password.focus(); return; }
  if (endpoint === 'register') {
    const byteLength = new TextEncoder().encode(password).length;
    if (password.length < 12 || byteLength > 72) { setAuthMessage('Use a password of at least 12 characters and no more than 72 UTF-8 bytes.'); form.elements.password.focus(); return; }
    if (password !== data.get('confirmPassword')) { setAuthMessage('The passwords do not match.'); form.elements.confirmPassword.focus(); return; }
  }
  const button = form.querySelector('.auth-submit');
  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = endpoint === 'register' ? 'Creating account…' : 'Signing in…';
  form.setAttribute('aria-busy', 'true');
  try {
    const tokenReady = await refreshCsrfToken();
    if (!tokenReady) throw new Error('Could not reach the account service. Check that the backend is running and the frontend origin is allowed.');
    const response = await fetch(API_BASE + '/auth/' + endpoint, {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}) },
      body: JSON.stringify({ email, password })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.success) {
      if (endpoint === 'register' && response.status === 409) {
        setAuthMessage('An account with this email already exists. Please log in.');
        qs('#login-form').elements.email.value = email;
        qs('#existing-login-button').hidden = false;
        return;
      }
      const message = result.message || (response.status === 409 ? 'An account with this email already exists.' : response.status === 401 ? 'Email or password was not accepted.' : response.status >= 500 ? 'The account service is temporarily unavailable. Please try again.' : 'Check the email and password, then try again.');
      setAuthMessage(message);
      return;
    }
    if (endpoint === 'register') {
      pendingVerificationEmail = email;
      const verifyForm = qs('#verify-form');
      verifyForm.elements.email.value = email;
      verifyForm.elements.code.value = '';
      form.reset();
      showAuthScreen('verify');
      setAuthMessage(result.message || 'If this address can be registered, check your inbox for a verification code.', false);
      return;
    }
    currentUser = result.data;
    guestMode = false;
    try { sessionStorage.removeItem(GUEST_MODE_KEY); } catch (error) { /* Optional session preference. */ }
    await refreshAccount();
    form.reset();
    setAuthMessage(endpoint === 'register' ? 'Account created. You are signed in.' : 'Signed in.', false);
    navigate('#/home');
  } catch (error) {
    setAuthMessage(error instanceof TypeError ? 'Could not reach the account service. Check the backend address and allowed frontend origin.' : error.message || 'The account service is temporarily unavailable.');
  } finally {
    button.disabled = false;
    button.textContent = originalText;
    form.removeAttribute('aria-busy');
  }
}

async function submitVerification(form) {
  const email = String(form.elements.email.value || pendingVerificationEmail).trim();
  const code = String(form.elements.code.value || '').trim();
  if (!email) { setAuthMessage('Enter the email address used for registration.'); return; }
  if (!/^[0-9]{6}$/.test(code)) { setAuthMessage('Enter the six-digit code from your email.'); form.elements.code.focus(); return; }
  const button = form.querySelector('.auth-submit');
  const originalText = button.textContent;
  button.disabled = true;
  button.textContent = 'Verifying…';
  form.setAttribute('aria-busy', 'true');
  try {
    if (!await refreshCsrfToken()) throw new Error('Could not reach the account service. Check that the backend is running.');
    const response = await fetch(API_BASE + '/auth/verify', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}) },
      body: JSON.stringify({ email, code })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.success) {
      setAuthMessage(result.message || 'The code could not be verified. Check it and try again.');
      return;
    }
    pendingVerificationEmail = '';
    form.reset();
    qs('#login-form').elements.email.value = email;
    showAuthScreen('login');
    setAuthMessage('Email verified. Log in with your password to continue.', false);
  } catch (error) {
    setAuthMessage(error instanceof TypeError ? 'Could not reach the account service. Check the backend address and allowed frontend origin.' : error.message || 'Verification could not be completed.');
  } finally {
    button.disabled = false;
    button.textContent = originalText;
    form.removeAttribute('aria-busy');
  }
}

async function resendVerificationCode() {
  const email = String(qs('#verify-form').elements.email.value || pendingVerificationEmail).trim();
  const button = qs('#resend-verification');
  button.disabled = true;
  const originalText = button.textContent;
  button.textContent = 'Requesting…';
  try {
    if (!await refreshCsrfToken()) throw new Error('Could not reach the account service. Check that the backend is running.');
    const response = await fetch(API_BASE + '/auth/resend-verification', {
      method: 'POST', credentials: 'include',
      headers: { 'Content-Type': 'application/json', ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}) },
      body: JSON.stringify({ email })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.success) { setAuthMessage(result.message || 'A new code could not be sent. Please retry.'); return; }
    setAuthMessage(result.message || 'If verification is pending, a new code has been sent when allowed.', false);
  } catch (error) {
    setAuthMessage(error instanceof TypeError ? 'Could not reach the account service. Check the backend address and allowed frontend origin.' : error.message || 'A new code could not be sent.');
  } finally {
    button.disabled = false;
    button.textContent = originalText;
  }
}

async function signOut() {
  await refreshCsrfToken();
  try {
    const response = await fetch(API_BASE + '/auth/logout', {
      method: 'POST', credentials: 'include', headers: csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}
    });
    if (!response.ok) { toast('Could not sign out.'); return; }
    currentUser = null; guestMode = false; savedVideoIds.clear();
    try { sessionStorage.removeItem(GUEST_MODE_KEY); } catch (error) { /* Optional session preference. */ }
    await refreshCsrfToken(); navigate('#/welcome');
  } catch (error) { setAuthMessage('Could not reach the account service. Your current session may still be active.'); }
}



/* ==========================================================================
   3. SAVING AND LOADING (localStorage)
   ========================================================================== */

function loadState() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return deepCopy(DEFAULT_STATE);

    const saved = JSON.parse(raw);
    // Merge the saved values on top of the defaults, so new fields always exist.
    const restored = { ...deepCopy(DEFAULT_STATE), ...saved };
    if (!LANGUAGES.some((language) => language.code === restored.language)) {
      restored.language = DEFAULT_STATE.language;
    }
    return restored;
  } catch (error) {
    console.warn('Could not read saved data, starting fresh.', error);
    return deepCopy(DEFAULT_STATE);
  }
}

function saveState() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch (error) {
    console.warn('Could not save to localStorage.', error);
  }
}


/* ==========================================================================
   4. VIDEO CARDS
   One function builds the card HTML so the design lives in exactly one place.
   ========================================================================== */

function cardHTML(video) {
  cardVideos.set(video.id, video);
  const lang = languageByCode(video.language);
  const languageKnown = LANGUAGES.some((item) => item.code === video.language);
  const progress = state.progress[video.id] || 0;

  return (
    '<article class="card">' +
      '<button class="card__open" type="button" data-video-id="' + escapeHTML(video.id) + '" ' +
        'aria-label="Open video: ' + escapeHTML(video.title) + '">' +
      '<div class="card__thumb" data-flag="' + lang.flag + '">' +
        '<img src="' + escapeHTML(video.thumb || FALLBACK_THUMB) + '" alt="" loading="lazy" onerror="thumbFailed(this)" />' +
        '<span class="card__lang">' + (languageKnown
          ? lang.flag + ' ' + escapeHTML(lang.name)
          : '🌐 Language unavailable') + '</span>' +
        '<span class="card__time">' + formatTime(video.duration) + '</span>' +
        (progress > 0.01
          ? '<div class="card__played"><i style="width:' + Math.round(progress * 100) + '%"></i></div>'
          : '') +
      '</div>' +
      '<div class="card__body">' +
        '<h3 class="card__title">' + escapeHTML(video.title) + '</h3>' +
        '<p class="card__meta">' + escapeHTML(video.creator) + ' · ' +
          formatDuration(video.duration) + '</p>' +
      '</div>' +
      '</button>' +
      '<button class="card__save' + (savedVideoIds.has(video.id) ? ' is-saved' : '') + '" ' +
        'type="button" data-save-video="' + escapeHTML(video.id) + '" aria-pressed="' +
        savedVideoIds.has(video.id) + '" aria-label="' + (savedVideoIds.has(video.id) ? 'Remove' : 'Save') +
        ' ' + escapeHTML(video.title) + '">' +
        '<svg class="icon"><use href="#i-bookmark"></use></svg>' +
        '<span>' + (savedVideoIds.has(video.id) ? 'Saved' : 'Save') + '</span></button>' +
    '</article>'
  );
}

/* Fill a grid element with cards, and show the empty message when needed. */
function fillGrid(gridSelector, emptySelector, list) {
  qs(gridSelector).innerHTML = list.map(cardHTML).join('');
  qs(emptySelector).hidden = list.length > 0;
}


/* ==========================================================================
   5. HOME PAGE
   Category chips on top, then a grid of thumbnails. Videos in the language
   being learned are shown first, because the demo catalogue only holds a
   couple of videos per language.
   ========================================================================== */

let homeSearch = { key: '', videos: [], nextPageTokens: {}, loading: false, error: '' };
let homeRequestSequence = 0;

function renderHome() {
  const selectedLanguage = state.language;
  const lang = languageByCode(selectedLanguage);
  const chips = ['All'].concat(CATEGORIES);

  qs('#home-chips').innerHTML = chips
    .map((name) =>
      '<button class="chip' + (name === filters.homeCategory ? ' is-active' : '') + '" ' +
        'type="button" role="tab" data-chip="' + escapeHTML(name) + '" ' +
        'aria-selected="' + (name === filters.homeCategory) + '">' + escapeHTML(name) + '</button>'
    )
    .join('');

  const key = [selectedLanguage, filters.homeCategory].join('|');
  if (homeSearch.key !== key) {
    homeSearch = { key, videos: [], nextPageTokens: {}, loading: false, error: '' };
    loadHomeVideos();
  }
  const list = homeSearch.videos;
  qs('#home-note').textContent = homeSearch.loading
    ? 'Loading real YouTube videos…'
    : homeSearch.error || ((lang ? lang.flag + ' ' + lang.name : 'All languages') +
      ' · ' + list.length + ' YouTube video' + (list.length === 1 ? '' : 's'));
  fillGrid('#home-grid', '#home-empty', list);
  qs('#home-empty').querySelector('h2').textContent = homeSearch.error ? 'YouTube videos could not be loaded' : 'No YouTube videos found';
  qs('#home-empty').querySelector('p').textContent = homeSearch.error || 'Try another category or language.';
  qs('#home-empty').hidden = homeSearch.loading || list.length > 0;
  qs('#home-retry').hidden = !homeSearch.error;
  qs('#home-more').hidden = !Object.values(homeSearch.nextPageTokens).some(Boolean);
  qs('#home-more').disabled = homeSearch.loading;
  qs('#home-more').textContent = homeSearch.loading ? 'Loading…' : 'Load more';
}

async function loadHomeVideos() {
  const requestKey = homeSearch.key;
  const sequence = ++homeRequestSequence;
  homeSearch.loading = true;
  homeSearch.error = '';
  renderHome();
  const selectedLanguage = requestKey.split('|')[0];
  const category = requestKey.split('|')[1];
  const query = category === 'All' ? null : SAMPLE_CATEGORY_SEARCH_TERMS[category];

  // The header language is the single source of truth for Home filtering.
  const languages = [selectedLanguage];

  const results = await Promise.all(languages.map((code) => {
    const token = homeSearch.nextPageTokens[code] || null;
    // If we already have videos and no page token for this language, stop loading more.
    if (homeSearch.videos.length && !token) return Promise.resolve({ status: 'empty', videos: [], nextPageToken: null });
    const langQuery = query || languageByCode(code).name + ' language lessons culture conversation';
    return searchVideosAPI(langQuery, code, token, homeSearch.refreshOrder || 'relevance', 12, homeSearch.freshDays);
  }));

  if (sequence !== homeRequestSequence || requestKey !== homeSearch.key) return;
  homeSearch.loading = false;
  const failed = results.find((result) => result.status === 'error');
  if (failed) homeSearch.error = failed.message;
  else {
    const videos = results.flatMap((result, index) => result.videos.map((video) => ({
      id: video.id, title: video.title, creator: video.creator, thumb: video.thumbnail,
      language: video.language && video.language !== 'unknown' ? video.language : languages[index],
      category: video.category || category, duration: video.duration || 0, description: video.description || ''
    })));
    homeSearch.videos = mergeUniqueVideos(homeSearch.videos, videos);
    results.forEach((result, index) => { homeSearch.nextPageTokens[languages[index]] = result.nextPageToken; });
  }
  renderHome();
}


/* ==========================================================================
   6. EXPLORE PAGE
   Two dropdowns plus the search box. Every dropdown starts with "All".
   ========================================================================== */

/* Build the dropdown options once, when the app starts. */
function buildExploreFilters() {
  const options = (values) =>
    '<option value="all">All</option>' +
    values.map((value) => '<option value="' + escapeHTML(value) + '">' + escapeHTML(value) + '</option>').join('');

  qs('#filter-language').innerHTML = options(LANGUAGES.map((lang) => lang.code));
  qs('#filter-category').innerHTML = options(CATEGORIES);
  qs('#filter-topic').innerHTML = '<option value="all">All topics</option>' +
    EXPLORE_TOPICS.map((topic) => '<option value="' + escapeHTML(topic.value) + '">' +
      escapeHTML(topic.label) + '</option>').join('');
}

/* Show the language name instead of the 2-letter code in the dropdown. */
function labelLanguageOptions() {
  qsa('#filter-language option').forEach((option) => {
    const lang = LANGUAGES.find((item) => item.code === option.value);
    if (lang) option.textContent = lang.flag + ' ' + lang.name;
  });
}

function filteredVideos() {
  const query = filters.query.trim().toLowerCase();

  return VIDEOS.filter((video) => {
    const lang = languageByCode(video.language);

    if (filters.language !== 'all' && video.language !== filters.language) return false;
    if (filters.category !== 'all' && video.category !== filters.category) return false;

    if (query) {
      const haystack = (video.title + ' ' + video.creator + ' ' + video.category + ' ' +
        video.description + ' ' + lang.name).toLowerCase();
      if (!haystack.includes(query)) return false;
    }
    return true;
  });
}

function getActiveLanguage() {
  return filters.language !== 'all' ? filters.language : state.language;
}

function renderExplore() {
  // Keep the dropdowns showing what is actually being filtered on.
  qs('#filter-language').value = filters.language;
  qs('#filter-category').value = filters.category;
  qs('#filter-topic').value = filters.topic;

  const query = filters.query.trim();
  const activeLanguage = getActiveLanguage();
  const selectedOrder = qs('#video-sort').value;
  const externalSearch = activeExternalSearch
    && activeExternalSearch.query === query
    && activeExternalSearch.language === activeLanguage
    && activeExternalSearch.topic === filters.topic
    && activeExternalSearch.category === filters.category
    && activeExternalSearch.order === selectedOrder
    ? activeExternalSearch.videos
    : null;

  if (externalSearch) {
    const resultLabel = activeExternalSearch.searchQuery;
    qs('#explore-count').textContent = externalSearch.length
      ? 'YouTube results for “' + resultLabel + '” — ' + externalSearch.length + ' videos'
      : 'No YouTube results for “' + resultLabel + '”.';
    const empty = qs('#explore-empty');
    empty.querySelector('h2').textContent = 'No YouTube videos found';
    empty.querySelector('p').textContent = 'Try another search or browse the bundled sample catalogue from Home.';
    fillGrid('#explore-grid', '#explore-empty', externalSearch);
    const more = qs('#load-more-videos');
    more.hidden = !activeExternalSearch.nextPageToken && !searchLoadingMore;
    more.disabled = searchLoadingMore;
    more.textContent = searchLoadingMore ? 'Loading more videos…' : 'Load more';
    return;
  }

  // If no external search results match current active language/order, load videos for the active language
  if (!activeExternalSearch || activeExternalSearch.language !== activeLanguage
      || activeExternalSearch.topic !== filters.topic || activeExternalSearch.category !== filters.category
      || activeExternalSearch.order !== selectedOrder) {
    searchExploreLanguage(activeLanguage);
    return;
  }

  qs('#load-more-videos').hidden = true;

  const list = filteredVideos();
  qs('#explore-empty').querySelector('h2').textContent = 'No videos match that search';
  qs('#explore-empty').querySelector('p').textContent = 'Try a shorter word, or clear the filters to see everything.';

  qs('#explore-count').textContent = query
    ? 'Results for "' + query + '" — ' + list.length + ' video' + (list.length === 1 ? '' : 's')
    : list.length + ' video' + (list.length === 1 ? '' : 's') + ' shown';

  fillGrid('#explore-grid', '#explore-empty', list);
}

async function searchExternalVideos(query, language = getActiveLanguage(), pageToken = null, freshDays = null) {
  const normalized = query.trim();
  if (!normalized) return;
  const languageCode = LANGUAGES.some((item) => item.code === language) ? language : getActiveLanguage();
  const order = qs('#video-sort').value;
  const previous = pageToken ? activeExternalSearch : null;
  if (pageToken && (!previous || previous.nextPageToken !== pageToken
      || previous.query !== filters.query.trim() || previous.language !== languageCode
      || previous.topic !== filters.topic || previous.category !== filters.category || previous.order !== order)) return;
  latestSearchQuery = normalized;
  const requestSequence = ++searchRequestSequence;
  if (!pageToken) activeExternalSearch = null;
  searchLoadingMore = Boolean(pageToken);
  const grid = qs('#explore-grid');
  const feedback = qs('#search-feedback');
  feedback.hidden = true;
  qs('#search-retry').hidden = true;
  if (!pageToken) {
    qs('#explore-count').textContent = 'Searching YouTube for “' + normalized + '”…';
    grid.innerHTML = '<p class="note">Searching YouTube…</p>';
  } else {
    const more = qs('#load-more-videos');
    more.disabled = true;
    more.textContent = 'Loading more videos…';
  }
  const displayQuery = filters.query.trim();
  const displayTopic = filters.topic;
  const displayCategory = filters.category;
  const result = await searchVideosAPI(normalized, languageCode, pageToken, order, 12, freshDays);
  if (requestSequence !== searchRequestSequence) return;
  searchLoadingMore = false;
  if (normalized !== latestSearchQuery
      || displayQuery !== filters.query.trim()
      || languageCode !== getActiveLanguage()
      || displayTopic !== filters.topic
      || displayCategory !== filters.category
      || order !== qs('#video-sort').value
      || parseHash().name !== 'explore') return;
  if (result.status === 'error') {
    if (!pageToken) {
      activeExternalSearch = {
        query: displayQuery,
        language: languageCode,
        topic: displayTopic,
        category: displayCategory,
        order,
        searchQuery: normalized,
        videos: [],
        nextPageToken: null
      };
    }
    renderExplore();
    feedback.textContent = result.message;
    feedback.classList.add('is-error');
    feedback.hidden = false;
    qs('#search-retry').hidden = false;
    return;
  }
  const adapted = result.videos.map((video) => ({
    id: video.id, title: video.title, creator: video.creator, thumb: video.thumbnail,
    language: video.language || 'unknown', category: video.category || 'General',
    duration: video.duration || 0, description: video.description || ''
  }));
  const videos = mergeUniqueVideos(previous ? previous.videos : [], adapted);
  activeExternalSearch = {
    query: displayQuery,
    language: languageCode,
    topic: displayTopic,
    category: displayCategory,
    order,
    searchQuery: normalized,
    videos,
    nextPageToken: result.nextPageToken
  };
  renderExplore();
}

function mergeUniqueVideos(existing, additions) {
  const ids = new Set(existing.map((video) => video.id));
  return existing.concat(additions.filter((video) => !ids.has(video.id) && ids.add(video.id)));
}

/* A language filter can be used without text in the search box. Keep the
   user's text untouched and send a useful language-specific query instead. */
function searchExploreLanguage(language, freshDays = null) {
  const languageCode = LANGUAGES.some((item) => item.code === language) ? language : getActiveLanguage();
  const query = buildExploreQuery(languageCode);
  searchExternalVideos(query, languageCode, null, freshDays);
}

function buildExploreQuery(languageCode) {
  const typedQuery = filters.query.trim();
  const topic = EXPLORE_TOPICS.find((item) => item.value === filters.topic);
  const categoryQuery = SAMPLE_CATEGORY_SEARCH_TERMS[filters.category] || '';
  const queryLower = typedQuery.toLowerCase();
  const topicTerms = [categoryQuery, topic ? topic.query : ''].join(' ').split(/\s+/)
    .filter((term, index, all) => term && !queryLower.includes(term.toLowerCase())
      && all.indexOf(term) === index);
  const terms = topicTerms.concat(typedQuery ? [typedQuery] : []);
  return terms.length ? terms.join(' ') : languageByCode(languageCode).name + ' language learning';
}

async function loadMoreVideos() {
  if (!activeExternalSearch || !activeExternalSearch.nextPageToken || searchLoadingMore) return;
  await searchExternalVideos(activeExternalSearch.searchQuery, activeExternalSearch.language, activeExternalSearch.nextPageToken);
}


/* ==========================================================================
   7. TRENDING PAGE
   Fetches trending videos from the backend API or shows a fallback message.
   ========================================================================== */

let activeTrendingResults = null;
let trendingRequestSequence = 0;
let trendingLoadingMore = false;

async function renderTrending(pageToken = null) {
  const grid = qs('#trending-grid');
  const empty = qs('#trending-empty');
  const feedback = qs('#trending-feedback');
  const more = qs('#load-more-trending');
  const previous = pageToken ? activeTrendingResults : null;
  if (pageToken && (!previous || previous.nextPageToken !== pageToken || trendingLoadingMore)) return;
  const sequence = ++trendingRequestSequence;
  trendingLoadingMore = Boolean(pageToken);

  feedback.hidden = true;
  empty.hidden = true;
  more.hidden = !pageToken && !activeTrendingResults?.nextPageToken;
  more.disabled = trendingLoadingMore;
  more.textContent = trendingLoadingMore ? 'Loading more videos…' : 'Load more';
  if (!pageToken) grid.innerHTML = '<p class="note">Loading trending videos...</p>';
  else more.textContent = 'Loading more videos…';

  const result = await getTrendingVideosPageAPI(pageToken);
  if (sequence !== trendingRequestSequence || parseHash().name !== 'trending') return;
  trendingLoadingMore = false;

  if (result.status === 'ok' || (result.status === 'empty' && pageToken)) {
    // Convert API results to match the frontend's expected format
    const adaptedVideos = result.videos.map(v => ({
      id: v.id,
      title: v.title,
      creator: v.creator,
      thumb: v.thumbnail,
      language: v.language || 'unknown',
      category: v.category || 'General',
      duration: v.duration || 0,
      description: v.description || ''
    }));
    const videos = mergeUniqueVideos(previous ? previous.videos : [], adaptedVideos);
    activeTrendingResults = { videos, nextPageToken: result.nextPageToken };
    fillGrid('#trending-grid', '#trending-empty', videos);
    more.hidden = !activeTrendingResults.nextPageToken;
    more.disabled = false;
    more.textContent = 'Load more';
  } else {
    if (pageToken && previous) {
      activeTrendingResults = previous;
      fillGrid('#trending-grid', '#trending-empty', previous.videos);
      feedback.textContent = result.message || 'Could not load more trending videos. Please retry.';
      feedback.classList.add('is-error');
      feedback.hidden = false;
      more.hidden = !previous.nextPageToken;
      more.disabled = false;
      more.textContent = 'Retry loading more';
    } else {
      activeTrendingResults = null;
      grid.innerHTML = '';
      qs('#trending-message').textContent = result.status === 'empty'
        ? 'YouTube returned no trending videos for this region. Try again later.'
        : result.message;
      empty.hidden = false;
      more.hidden = true;
    }
  }
}

async function loadMoreTrending() {
  if (!activeTrendingResults?.nextPageToken || trendingLoadingMore) return;
  await renderTrending(activeTrendingResults.nextPageToken);
}


/* ==========================================================================
   8. SAVED VIDEOS PAGE
   Shows videos saved by the user from the backend.
   ========================================================================== */

async function renderSaved() {
  const grid = qs('#saved-grid');
  const empty = qs('#saved-empty');

  if (guestMode && !currentUser) {
    let videos = [];
    try { videos = JSON.parse(localStorage.getItem(GUEST_SAVED_KEY) || '[]'); } catch (error) { /* Local storage is optional. */ }
    if (videos.length) fillGrid('#saved-grid', '#saved-empty', videos);
    else {
      grid.innerHTML = '';
      empty.querySelector('h2').textContent = 'No guest saves yet';
      empty.querySelector('p').textContent = 'Guest bookmarks are stored only in this browser.';
      empty.hidden = false;
    }
    return;
  }

  if (!currentUser) {
    grid.innerHTML = '';
    empty.querySelector('h2').textContent = 'Choose guest or sign in';
    empty.querySelector('p').textContent = 'Open Account to continue as a guest or access private saved videos.';
    empty.hidden = false;
    return;
  }
  grid.innerHTML = '<p class="note">Loading saved videos...</p>';
  empty.hidden = true;

  const videos = await getSavedVideosAPI();

  const heading = empty.querySelector('h2');
  const message = empty.querySelector('p');
  if (backendAvailable === false) {
    grid.innerHTML = '';
    heading.textContent = 'Saved videos are unavailable';
    message.textContent = 'Start the Globlingo backend and try again.';
    empty.hidden = false;
    return;
  }
  heading.textContent = 'No saved videos yet';
  message.textContent = 'Click Save on any video to add it here.';

  if (videos && videos.length > 0) {
    // Convert API results to match the frontend's expected format
    const adaptedVideos = videos.map(v => ({
      id: v.id,
      title: v.title,
      creator: v.creator,
      thumb: v.thumbnail,
      language: v.language || 'unknown',
      category: v.category || 'General',
      duration: v.duration || 0,
      description: v.description || ''
    }));
    fillGrid('#saved-grid', '#saved-empty', adaptedVideos);
  } else {
    grid.innerHTML = '';
    empty.hidden = false;
  }
}

async function loadSavedVideoIds() {
  if (!currentUser) return;
  try {
    const response = await fetch(API_BASE + '/saved', { signal: AbortSignal.timeout(5000), credentials: 'include' });
    if (!response.ok) return;
    const result = await response.json();
    if (!result.success || !Array.isArray(result.data)) return;
    savedVideoIds.clear();
    result.data.forEach((video) => {
      if (video && video.id) savedVideoIds.add(video.id);
    });
    render();
  } catch (error) {
    // The sample catalogue remains available even when the backend is offline.
  }
}

async function toggleSavedVideo(videoId) {
  const video = cardVideos.get(videoId);
  if (!video) return;
  if (!currentUser && guestMode) {
    const payload = {
      id: video.id, title: video.title, creator: video.creator,
      thumb: video.thumb || video.thumbnail, language: video.language || 'unknown',
      category: video.category || 'General', duration: video.duration || 0,
      description: video.description || ''
    };
    try {
      const videos = JSON.parse(localStorage.getItem(GUEST_SAVED_KEY) || '[]');
      const filtered = videos.filter((item) => item.id !== videoId);
      if (savedVideoIds.has(videoId)) savedVideoIds.delete(videoId);
      else { filtered.push(payload); savedVideoIds.add(videoId); }
      localStorage.setItem(GUEST_SAVED_KEY, JSON.stringify(filtered));
      if (parseHash().name === 'saved') renderSaved(); else render();
      toast(savedVideoIds.has(videoId) ? 'Saved in this browser.' : 'Removed from guest saves.');
    } catch (error) { toast('This browser could not store guest bookmarks.'); }
    return;
  }
  if (!currentUser) { navigate('#/account'); toast('Choose guest mode or sign in to save videos.'); return; }
  const button = qs('[data-save-video="' + CSS.escape(videoId) + '"]');
  if (button) button.disabled = true;
  const payload = {
    id: video.id, title: video.title, creator: video.creator,
    thumbnail: video.thumbnail || video.thumb, language: video.language || 'unknown',
    category: video.category || 'General', duration: video.duration || 0,
    description: video.description || ''
  };
  const ok = savedVideoIds.has(videoId)
    ? await unsaveVideoAPI(videoId)
    : await saveVideoAPI(payload);
  if (button) button.disabled = false;
  if (!ok) {
    toast('Could not update Saved Videos. Check that the backend is running.');
    return;
  }
  if (savedVideoIds.has(videoId)) savedVideoIds.delete(videoId);
  else savedVideoIds.add(videoId);
  if (parseHash().name === 'saved') renderSaved();
  else render();
  toast(savedVideoIds.has(videoId) ? 'Video saved.' : 'Video removed from Saved.');
}


/* Put every Explore filter back to "All" (and clear the search box). */
function clearExploreFilters() {
  filters.query = '';
  filters.language = 'all';
  filters.category = 'all';
  filters.topic = 'all';
  qs('#video-sort').value = 'relevance';
  qs('#filter-language').value = 'all';
  qs('#filter-category').value = 'all';
  qs('#filter-topic').value = 'all';
  activeExternalSearch = null;
  searchRequestSequence += 1;
  qs('#search-input').value = '';
  qs('#search-clear').hidden = true;
  searchExploreLanguage(state.language);
}


/* ========================================================================== 
   7. SHORTS PAGE
   YouTube candidates use the official under-four-minute search filter.
   The API does not confirm that a result is an official YouTube Short, so
   the interface labels them as short-duration candidates.
   ========================================================================== */

const shortsState = {
  language: 'header', topic: 'all', query: '', refreshTerm: '', freshDays: null,
  youtube: { videos: [], nextPageToken: null, seen: new Set() },
  loading: false, error: '', requestId: 0,
};
let shortsObserver = null;

function buildShortsFilters() {
  const languageSelect = qs('#shorts-language');
  LANGUAGES.forEach((language) => {
    const option = document.createElement('option');
    option.value = language.code;
    option.textContent = language.name;
    languageSelect.appendChild(option);
  });
  const topicSelect = qs('#shorts-topic');
  EXPLORE_TOPICS.forEach((topic) => {
    const option = document.createElement('option');
    option.value = topic.value;
    option.textContent = topic.label;
    topicSelect.appendChild(option);
  });
}

function shortsEmbedUrl(videoId) {
  return 'https://www.youtube-nocookie.com/embed/' + encodeURIComponent(videoId) +
    '?playsinline=1&rel=0&modestbranding=1&iv_load_policy=3';
}

function shortArticleHTML(video) {
  return '<article class="short-tall" data-video-id="' + escapeHTML(video.id) + '">' +
    '<div class="short-tall__frame">' +
    '<iframe src="' + shortsEmbedUrl(video.id) + '" title="' + escapeHTML(video.title || 'YouTube video') +
    '" loading="lazy" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture" allowfullscreen referrerpolicy="strict-origin-when-cross-origin"></iframe>' +
    '<div class="short-tall__actions">' +
    '<a class="btn btn--quiet" href="https://www.youtube.com/watch?v=' + encodeURIComponent(video.id) + '" target="_blank" rel="noopener noreferrer">Watch on YouTube</a>' +
    '<button class="btn btn--quiet" type="button" data-share-video="' + escapeHTML(video.id) + '">Share</button>' +
    '</div>' +
    '</div>' +
    '<div class="short-tall__subs">' +
    '<div class="short-tall__subs-scroll"><ol class="subs__list short-tall__subs-list"><li class="sub-empty">Subtitles load when this short is playing.</li></ol></div>' +
    '<div class="short-tall__info">' +
    '<h3>' + escapeHTML(video.title || 'Untitled video') + '</h3>' +
    '<p>' + escapeHTML(video.channelName || video.creator || 'YouTube creator') + '</p>' +
    '<p class="short-tall__note">Under four minutes · YouTube Short not verified</p>' +
    '</div>' +
    '</div></article>';
}

function renderShortsResults(append = false) {
  const feed = qs('#shorts-results');
  if (append) {
    /* Pagination must NOT rebuild the feed — destroying innerHTML would kill
       the currently playing video and restart it (the "page refresh" bug).
       Only articles for newly added videos are appended. */
    const start = shortsState.renderedCount || 0;
    const html = shortsState.youtube.videos.slice(start).map(shortArticleHTML).join('');
    if (html) feed.insertAdjacentHTML('beforeend', html);
    shortsState.renderedCount = shortsState.youtube.videos.length;
    qsa('#shorts-results .short-tall:not([data-observed])').forEach((article) => {
      article.dataset.observed = '1';
      if (shortsActiveObserver) shortsActiveObserver.observe(article);
    });
  } else {
    // Rebuilding the feed invalidates every player and subtitle panel.
    shortsPlayers.forEach((entry) => { try { entry.player && entry.player.destroy(); } catch (e) { /* ignore */ } });
    shortsPlayers.clear();
    if (shortsActiveObserver) { shortsActiveObserver.disconnect(); shortsActiveObserver = null; }
    activeShortArticle = null;
    feed.innerHTML = shortsState.youtube.videos.map(shortArticleHTML).join('');
    shortsState.renderedCount = shortsState.youtube.videos.length;
    observeShortsPlayback();
  }
  const loadMore = qs('#shorts-load-more');
  loadMore.hidden = !shortsState.youtube.nextPageToken;
  loadMore.disabled = shortsState.loading;
  loadMore.textContent = shortsState.loading ? 'Loading…' : 'Load more';
  observeShortsSentinel();
}

function observeShortsSentinel() {
  if (shortsObserver) { shortsObserver.disconnect(); shortsObserver = null; }
  if (!shortsState.youtube.nextPageToken || shortsState.loading) return;
  const feed = qs('#shorts-results');
  const last = feed.querySelector('.short-tall:last-child');
  if (!last || typeof IntersectionObserver === 'undefined') return;
  shortsObserver = new IntersectionObserver((entries) => {
    if (entries.some((entry) => entry.isIntersecting)) loadShortsResults(true);
  }, { root: feed, threshold: 0.6 });
  shortsObserver.observe(last);
}

function shortsSearchQuery() {
  const isAllLanguages = shortsState.language === 'all';
  const languageCode = isAllLanguages ? 'en' : shortsState.language === 'header' ? state.language : shortsState.language;
  const language = isAllLanguages ? null : languageByCode(languageCode);
  const topic = EXPLORE_TOPICS.find((entry) => entry.value === shortsState.topic);
  return {
    languageCode,
    query: [shortsState.query.trim(), topic ? topic.query : '', language ? language.name : '', 'shorts', shortsState.refreshTerm]
      .filter(Boolean).join(' ').slice(0, 200),
  };
}

async function loadShortsResults(append = false, keepSeen = false) {
  const status = qs('#shorts-status');
  shortsState.query = qs('#shorts-query').value.trim();
  const requestId = ++shortsState.requestId;
  const prevVideos = keepSeen ? shortsState.youtube.videos.slice() : null;
  const prevRendered = keepSeen ? (shortsState.renderedCount || 0) : 0;
  if (!append) {
    /* keepSeen (Refresh): carry over already-displayed IDs so the fresh
       result set filters out duplicates instead of repeating them. */
    const seen = keepSeen ? shortsState.youtube.seen : new Set();
    shortsState.youtube = { videos: [], nextPageToken: null, seen };
    shortsState.renderedCount = 0;
  }
  shortsState.loading = true;
  shortsState.error = '';
  status.textContent = 'Searching YouTube for short-duration videos…';
  /* deferRender (Refresh): keep the current feed visible until the new
     response arrives — the feed is only replaced on success. */
  if (!shortsState.deferRender) renderShortsResults(append);

  const { query, languageCode } = shortsSearchQuery();
  const pageToken = append ? shortsState.youtube.nextPageToken : null;
  const result = await searchYouTubeShortCandidates(query, languageCode, pageToken, qs('#shorts-order').value, shortsState.freshDays);
  if (requestId !== shortsState.requestId) return;
  shortsState.loading = false;
  shortsState.deferRender = false;
  if (result.status === 'error') {
    shortsState.error = result.message;
    status.textContent = result.message + ' Use Retry to try again.';
    /* Refresh failed: restore the previous feed instead of showing nothing. */
    if (prevVideos) {
      shortsState.youtube.videos = prevVideos;
      shortsState.renderedCount = prevRendered;
      renderShortsResults(false);
    }
  } else {
    let added = 0;
    result.videos.forEach((video) => {
      if (!video || !/^[A-Za-z0-9_-]{11}$/.test(video.id) || shortsState.youtube.seen.has(video.id)) return;
      shortsState.youtube.seen.add(video.id);
      shortsState.youtube.videos.push(video);
      added += 1;
    });
    shortsState.youtube.nextPageToken = result.nextPageToken;
    if (!shortsState.youtube.videos.length) status.textContent = 'No short-duration YouTube results matched. Try another topic or search.';
    else status.textContent = (added ? 'Showing ' + shortsState.youtube.videos.length + ' YouTube results under four minutes.' : 'No new videos on this page.') +
      ' Results are under four minutes; YouTube does not expose a verified Shorts classification here.';
  }
  qs('#shorts-retry').hidden = !shortsState.error;
  renderShortsResults(append);
}

/* ==========================================================================
   SHORTS PLAYBACK + SUBTITLES
   Only the short currently filling the feed gets a YouTube IFrame API player
   (for playback-time subtitle sync); the rest stay lightweight embeds.
   ========================================================================== */

const shortsPlayers = new Map();   // videoId -> { player, article, timer }
let shortsActiveObserver = null;
let activeShortArticle = null;

function shortsSubtitleLang() {
  const code = shortsState.language === 'header' || shortsState.language === 'all'
    ? state.language : shortsState.language;
  return /^[a-z]{2,3}(-[A-Z]{2})?$/.test(code) ? code.split('-')[0] : 'en';
}

function observeShortsPlayback() {
  if (shortsActiveObserver || typeof IntersectionObserver === 'undefined') return;
  const feed = qs('#shorts-results');
  shortsActiveObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting && entry.intersectionRatio >= 0.6) setActiveShort(entry.target);
    });
  }, { root: feed, threshold: [0.6] });
  qsa('#shorts-results .short-tall').forEach((article) => shortsActiveObserver.observe(article));
}

function pauseShortPlayer(article) {
  const entry = article && shortsPlayers.get(article.dataset.videoId);
  if (!entry) return;
  if (entry.timer) { clearInterval(entry.timer); entry.timer = null; }
  try { if (entry.player && entry.player.pauseVideo) entry.player.pauseVideo(); } catch (e) { /* ignore */ }
}

function setActiveShort(article) {
  if (!article || article === activeShortArticle) return;
  if (activeShortArticle) pauseShortPlayer(activeShortArticle);
  activeShortArticle = article;
  const videoId = article.dataset.videoId;
  ensureShortPlayer(article, videoId);
  loadShortSubtitles(article, videoId);
}

function ensureShortPlayer(article, videoId) {
  if (shortsPlayers.has(videoId)) {
    const existing = shortsPlayers.get(videoId);
    try { if (existing.player && existing.player.playVideo) existing.player.playVideo(); } catch (e) { /* ignore */ }
    return;
  }
  ensureYouTubeIframeApi().then((ready) => {
    // The article may have been re-rendered while the API was loading.
    if (!article.isConnected) return;
    if (!ready) return; // keep the plain embed; subtitles still show, unsynced
    const frame = article.querySelector('.short-tall__frame');
    const host = document.createElement('div');
    host.id = 'yt-short-' + videoId;
    host.className = 'short-tall__player';
    frame.querySelector('iframe')?.remove();
    frame.insertBefore(host, frame.firstChild);
    const player = new YT.Player(host.id, {
      videoId: videoId,
      playerVars: { playsinline: 1, rel: 0, modestbranding: 1, iv_load_policy: 3 },
      events: {
        onReady: () => { try { player.playVideo(); } catch (e) { /* autoplay may be blocked; user can press play */ } },
        onStateChange: (event) => {
          // YT.PlayerState: 1 playing, everything else stops the sync timer.
          if (event.data === 1) startShortSync(article);
          else stopShortSync(article);
        },
      },
    });
    shortsPlayers.set(videoId, { player, article, timer: null });
  });
}

function stopShortSync(article) {
  const entry = shortsPlayers.get(article.dataset.videoId);
  if (entry && entry.timer) { clearInterval(entry.timer); entry.timer = null; }
}

function startShortSync(article) {
  stopShortSync(article);
  const entry = shortsPlayers.get(article.dataset.videoId);
  if (!entry) return;
  entry.timer = setInterval(() => highlightShortSubtitle(article), 300);
}

function highlightShortSubtitle(article) {
  const entry = shortsPlayers.get(article.dataset.videoId);
  const subs = article._subs;
  if (!entry || !entry.player || !entry.player.getCurrentTime || !subs || !subs.lines.length) return;
  let time;
  try { time = entry.player.getCurrentTime(); } catch (error) { return; }
  const lines = article.querySelectorAll('.short-tall__subs-list .sub');
  if (!lines.length) return;
  let activeIndex = -1;
  subs.lines.forEach((line, index) => {
    const inRange = line.end > line.time ? (time >= line.time && time < line.end) : time >= line.time;
    if (inRange) activeIndex = index;
  });
  if (activeIndex === -1) subs.lines.forEach((line, index) => { if (line.time <= time) activeIndex = index; });
  lines.forEach((line, index) => line.classList.toggle('is-speaking', index === activeIndex));
  highlightShortWord(article, activeIndex, subs.lines[activeIndex], time);
  const active = lines[activeIndex];
  const list = article.querySelector('.short-tall__subs-scroll');
  if (active && list) {
    const activeRect = active.getBoundingClientRect();
    const listRect = list.getBoundingClientRect();
    if (activeRect.top < listRect.top + 8 || activeRect.bottom > listRect.bottom - 8) {
      list.scrollTop += activeRect.top - listRect.top - list.clientHeight / 3;
    }
  }
}

/* Karaoke-style word highlight. Transcript providers only give LINE-level
   timestamps, so the active word is estimated by mapping elapsed fraction of
   the line's [time,end) window onto the line's character span. This tracks
   real playback time (pause/seek/speed all handled via getCurrentTime) but is
   an approximation — providers do not expose true word timestamps. */
function highlightShortWord(article, activeIndex, line, time) {
  const words = article.querySelectorAll('.short-tall__subs-list .sub .w.is-word-active');
  words.forEach((w) => w.classList.remove('is-word-active'));
  if (activeIndex < 0 || !line || !(line.end > line.time)) return;
  const frac = Math.min(1, Math.max(0, (time - line.time) / (line.end - line.time)));
  const row = article.querySelectorAll('.short-tall__subs-list .sub')[activeIndex];
  if (!row) return;
  const buttons = row.querySelectorAll('.w');
  if (!buttons.length) return;
  let total = 0;
  buttons.forEach((b) => { total += b.textContent.length; });
  if (!total) return;
  const pos = frac * total;
  let acc = 0;
  for (const button of buttons) {
    acc += button.textContent.length;
    if (pos <= acc) { button.classList.add('is-word-active'); break; }
  }
}

async function loadShortSubtitles(article, videoId) {
  if (article._subsRequested) return;
  article._subsRequested = true;
  const list = article.querySelector('.short-tall__subs-list');
  if (!list) return;
  list.innerHTML = '<li class="sub-empty">Loading subtitles…</li>';
  const result = await fetchTranscript(videoId, shortsSubtitleLang());
  if (!article.isConnected) return;
  if (result.status !== 'ok') {
    article._subs = null;
    list.innerHTML = '<li class="sub-empty">' + escapeHTML(result.message || 'No subtitles for this short.') + '</li>';
    return;
  }
  article._subs = { videoId, lang: result.lang || shortsSubtitleLang(), lines: result.lines };
  let wordIndex = 0;
  list.innerHTML = result.lines.map((line, lineIndex) => {
    wordIndex = 0;
    const words = tokenizeSubtitle(line.text, article._subs.lang).map((part) => {
      if (!part.isWord) return escapeHTML(part.text);
      const html = '<button class="w" type="button" data-line="' + lineIndex + '" data-word="' + wordIndex + '">' +
        escapeHTML(part.text) + '</button>';
      wordIndex += 1;
      return html;
    }).join('');
    return '<li class="sub" data-time="' + line.time + '">' +
      '<button class="sub__time" type="button" data-seek="' + line.time + '">' + formatTime(line.time) + '</button>' +
      '<div class="sub__body"><p class="sub__words">' + words + '</p></div></li>';
  }).join('');
}

/* Word lookup inside a short: same providers and popup as the Watch page. */
async function openShortWordPopup(article, button) {
  const subs = article._subs;
  if (!subs || !subs.lines) return;
  const popup = qs('#word-popup');
  popup.hidden = false;
  if (openWordButton) openWordButton.classList.remove('is-open');
  openWordButton = button;
  button.classList.add('is-open');

  const lineIndex = Number(button.dataset.line);
  const wordIndex = Number(button.dataset.word);
  const line = subs.lines[lineIndex];
  if (!line) { closeWordPopup(); return; }
  const tokens = tokenizeSubtitle(line.text, subs.lang || 'en').filter((part) => part.isWord);
  const wordToken = tokens[wordIndex] ? tokens[wordIndex].text : '';
  if (!wordToken) { closeWordPopup(); return; }
  positionPopup(button, popup);

  const sourceCode = (subs.lang || 'en').toLowerCase();
  const targetCode = (state.language || 'en').toLowerCase();
  const sourceName = languageNameForCode(sourceCode);
  const targetName = languageByCode(targetCode).name;
  const key = [wordToken, line.text, sourceCode, targetCode].join('|');

  qs('#wp-word').textContent = wordToken;
  setPopupPronunciation(wordToken, '', '');
  popupRow('wp-meaning', 'Looking up…', true);
  popupRow('wp-translation', 'Translating…', true);
  popupRow('wp-explanation', 'Thinking…', true);
  popupRow('wp-example', line.text, false);
  qs('#wp-source').textContent = 'Loading…';

  const cached = wordPopupCache.get(key);
  if (cached) { renderWordDetails({ text: line.text }, wordToken, cached, sourceName, targetName); return; }
  if (wordPopupPendingKey === key) return;
  wordPopupPendingKey = key;
  const result = await fetchWordMeaning(wordToken, line.text, sourceName, targetName, sourceCode, targetCode);
  if (wordPopupPendingKey === key) wordPopupPendingKey = null;
  if (openWordButton !== button) return;
  if (result.status === 'ok' && result.data) {
    wordPopupCache.set(key, result.data);
    renderWordDetails({ text: line.text }, wordToken, result.data, sourceName, targetName);
  } else {
    popupRow('wp-meaning', result.message || 'Word details are unavailable right now.', true);
    popupRow('wp-translation', '', true);
    popupRow('wp-explanation', '', true);
    popupRow('wp-example', line.text, false);
    qs('#wp-source').textContent = 'Unavailable';
  }
}

function retryShortsSearch() {
  return loadShortsResults(false);
}

function shareShortVideo(videoId) {
  const url = 'https://www.youtube.com/watch?v=' + encodeURIComponent(videoId);
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(url)
      .then(() => toast('Video link copied.'))
      .catch(() => window.open(url, '_blank', 'noopener,noreferrer'));
  } else {
    window.open(url, '_blank', 'noopener,noreferrer');
  }
}

function renderShorts() {
  qs('#shorts-language').value = shortsState.language;
  qs('#shorts-topic').value = shortsState.topic;
  qs('#shorts-query').value = shortsState.query;

  if (shortsState.youtube.videos.length || shortsState.loading) {
    qs('#shorts-status').textContent = shortsState.loading
      ? 'Searching YouTube for short-duration videos…'
      : 'Showing ' + shortsState.youtube.videos.length + ' YouTube results under four minutes. The API does not confirm that a video is a Short.';
    /* render() runs on every hashchange/navigation — calling the non-append
       renderer here would destroy players and reset scroll on each pass.
       Only (re)build when the video list actually changed. */
    if (shortsState.renderedCount !== shortsState.youtube.videos.length || shortsState.needsRender) {
      shortsState.needsRender = false;
      renderShortsResults(false);
    }
  } else loadShortsResults(false);
}

/* ==========================================================================
   8. THE DEMO PLAYER (simulated playback)
   There is no real video file or embed in this prototype. To make the watch
   page feel alive — and to show how subtitle highlighting will work later —
   the player advances a fake clock while it is "playing".
   ========================================================================== */

const player = {
  video: null,
  time: 0,       // seconds played
  duration: 0,   // total seconds
  playing: false,
  timer: null,   // setInterval id
  muted: false,
  ticks: 0,      // quarter-second ticks since playback started
};

/* Load a video into the player and reset the clock. */
function loadPlayer(video) {
  playerStop();
  const frame = qs('#player-frame');
  const embed = qs('#player-embed');
  embed.replaceChildren(); embed.hidden = true;
  frame.classList.remove('is-embedded');
  qs('#player-poster').hidden = false;
  qs('#player-frame .player__center').hidden = false;
  qs('#player-badge').hidden = false;
  qs('#player-bar').hidden = false;
  player.video = video;
  player.duration = video.duration;
  player.ticks = 0;

  // Resume where the user left off, unless the video was already finished.
  const savedFraction = state.progress[video.id] || 0;
  player.time = savedFraction >= 0.99 ? 0 : Math.round(savedFraction * video.duration);

  const poster = qs('#player-poster');
  poster.onerror = null;
  poster.src = video.thumb;
  poster.alt = 'Poster frame for ' + video.title;
  poster.onerror = () => thumbFailed(poster);

  player.muted = false;
  qs('#player-mute').classList.remove('is-muted');

  qs('#player').classList.remove('is-playing');
  updatePlayerUI();
  highlightSubtitleLine(player.time);
}

function playerPlay() {
  if (!player.video || player.playing) return;

  player.playing = true;
  qs('#player').classList.add('is-playing');
  qs('#player-toggle').innerHTML = '<svg class="icon"><use href="#i-pause"></use></svg>';

  // Every 250 ms we add a quarter of a second of "playback".
  player.timer = setInterval(() => {
    player.time = Math.min(player.duration, player.time + 0.25);
    player.ticks += 1;

    if (player.time >= player.duration) {
      playerStop();
      saveWatchProgress();
      return;
    }

    updatePlayerUI();
    highlightSubtitleLine(player.time);

    // Saving on every tick would hammer localStorage, so save every 2 seconds.
    if (player.ticks % 8 === 0) saveWatchProgress();
  }, 250);
}

function playerPause() {
  if (!player.playing) return;
  player.playing = false;
  clearInterval(player.timer);
  player.timer = null;
  qs('#player').classList.remove('is-playing');
  qs('#player-toggle').innerHTML = '<svg class="icon"><use href="#i-play"></use></svg>';
  saveWatchProgress();
}

function playerStop() {
  player.playing = false;
  clearInterval(player.timer);
  player.timer = null;
  const box = qs('#player');
  if (box) box.classList.remove('is-playing');
  const toggle = qs('#player-toggle');
  if (toggle) toggle.innerHTML = '<svg class="icon"><use href="#i-play"></use></svg>';
}

function playerToggle() {
  if (player.playing) {
    playerPause();
  } else {
    playerPlay();
  }
}

/* Jump to a specific second (used by the subtitle timestamps and the bar). */
function playerSeek(seconds) {
  if (!player.video) return;
  player.time = Math.max(0, Math.min(player.duration, seconds));
  updatePlayerUI();
  highlightSubtitleLine(player.time);
  saveWatchProgress();
}

/* Paint the progress bar and the time label. */
function updatePlayerUI() {
  const percent = player.duration === 0 ? 0 : (player.time / player.duration) * 100;
  qs('#player-fill').style.width = percent.toFixed(2) + '%';
  qs('#player-time').textContent = formatTime(player.time) + ' / ' + formatTime(player.duration);
  qs('#player-track').setAttribute('aria-valuenow', String(Math.round(percent)));
}

/* Remember how far the user got, so cards can show a progress line. */
function saveWatchProgress() {
  if (!player.video) return;
  state.progress[player.video.id] = player.duration === 0 ? 0 : player.time / player.duration;
  saveState();
}

/* Highlight the subtitle line that matches the current playback time. */
function highlightSubtitleLine(time) {
  const lines = qsa('#subtitle-list .sub');
  if (lines.length === 0) return;

  // The active line is the last one whose timestamp has been passed.
  let activeIndex = -1;
  lines.forEach((line, index) => {
    if (Number(line.dataset.time) <= time) activeIndex = index;
  });

  lines.forEach((line, index) => line.classList.toggle('is-speaking', index === activeIndex));
}


/* ==========================================================================
   9. THE WATCH PAGE + CLICKABLE SUBTITLES (the core feature)
   Every subtitle line is turned into a row of small word buttons. Clicking a
   word opens the popup built in part 10.
   ========================================================================== */

function renderWatch(videoId) {
  const known = findVideo(videoId);

  // A real YouTube id we have not seen during this page load (page refresh,
  // shared link, browser history). Ask the backend for its details instead of
  // silently showing a bundled sample video with sample subtitles.
  if (!known && /^[A-Za-z0-9_-]{11}$/.test(String(videoId || ''))) {
    showWatchLoading();
    loadWatchVideoDetails(videoId);
    return;
  }

  paintWatch(known || findVideo(DEMO_VIDEO_ID) || VIDEOS[0]);
}

/* What the Watch page shows while the backend details are on their way. */
function showWatchLoading() {
  currentVideo = null;
  youtubeSubtitles = null;
  stopYouTubeSync();
  qs('#player-embed').replaceChildren();
  qs('#player-embed').hidden = true;
  qs('#player-frame').classList.remove('is-embedded');
  qs('#player-poster').hidden = true;
  qs('#player-frame .player__center').hidden = true;
  qs('#watch-title').textContent = 'Loading video…';
  qs('#watch-sub').textContent = '';
  qs('#watch-desc').textContent = '';
  qs('#subtitle-mode').textContent = 'Loading subtitles…';
  qs('#subtitle-list').innerHTML = '<li class="sub-empty">Loading video details…</li>';
}

/* Fetch one YouTube video's metadata from the backend so it can be watched
   after a refresh or from a shared link. Shows an honest error if unavailable. */
async function loadWatchVideoDetails(videoId) {
  const details = await fetchVideoDetails(videoId);
  if (parseHash().param !== videoId) return; // The user already moved on.
  if (!details) {
    qs('#watch-title').textContent = 'Video unavailable';
    qs('#watch-sub').textContent = '';
    qs('#watch-desc').textContent = 'This YouTube video could not be loaded. Check that the backend is running, then open the video again from Home, Explore, Trending or Shorts.';
    qs('#subtitle-mode').textContent = 'No subtitles';
    qs('#subtitle-list').innerHTML = '<li class="sub-empty">Subtitles are not available because the video details could not be loaded.</li>';
    return;
  }
  cardVideos.set(details.id, details);
  paintWatch(details);
}

async function fetchVideoDetails(videoId) {
  try {
    const response = await fetch(API_BASE + '/videos/' + encodeURIComponent(videoId),
      { credentials: 'include', signal: AbortSignal.timeout(15000) });
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.success || !result.data) return null;
    const video = result.data;
    return {
      id: video.id, title: video.title, creator: video.creator, thumb: video.thumbnail,
      language: video.language || 'unknown', category: video.category || 'General',
      duration: video.duration || 0, description: video.description || '',
      channelId: video.channelId, channelName: video.channelName,
    };
  } catch (error) {
    return null;
  }
}

/* Draw one resolved video object on the Watch page. */
function paintWatch(video) {
  const lang = languageByCode(video.language);
  const languageKnown = LANGUAGES.some((item) => item.code === video.language);

  currentVideo = video;

  qs('#watch-title').textContent = video.title;
  qs('#watch-sub').textContent =
    video.creator + ' · ' + (languageKnown ? lang.flag + ' ' + lang.name : 'Language unavailable') + ' · ' + video.category +
    ' · ' + formatDuration(video.duration);
  qs('#watch-desc').textContent = video.description;

  const youtubeVideo = /^[A-Za-z0-9_-]{11}$/.test(video.id) && !Array.isArray(video.transcript);
  if (youtubeVideo) {
    loadYouTubePlayer(video);
    loadYouTubeSubtitles(video);
  } else {
    youtubeSubtitles = null;
    stopYouTubeSync();
    loadPlayer(video);
    renderSubtitles(video, youtubeVideo);
  }

  document.title = video.title + ' · Globlingo';
}

let youtubeSubtitles = null;   // { videoId, lang, lines:[{time,end,text}] } for the open YouTube video
let ytPlayer = null;           // YouTube IFrame API player instance
let ytSyncTimer = null;        // playback-time polling for subtitle sync
let ytApiLoading = false;

function stopYouTubeSync() {
  if (ytSyncTimer) { clearInterval(ytSyncTimer); ytSyncTimer = null; }
}

/* Load the official YouTube IFrame API once so we can read playback time
   (needed for subtitle sync and click-to-seek). Falls back to a plain embed. */
function ensureYouTubeIframeApi() {
  if (window.YT && window.YT.Player) return Promise.resolve(true);
  if (ytApiLoading) return new Promise((resolve) => {
    const check = setInterval(() => {
      if (window.YT && window.YT.Player) { clearInterval(check); resolve(true); }
    }, 200);
    setTimeout(() => { clearInterval(check); resolve(false); }, 8000);
  });
  ytApiLoading = true;
  return new Promise((resolve) => {
    const previous = window.onYouTubeIframeAPIReady;
    window.onYouTubeIframeAPIReady = () => { if (previous) previous(); resolve(true); };
    const script = document.createElement('script');
    script.src = 'https://www.youtube.com/iframe_api';
    script.async = true;
    script.onerror = () => resolve(false);
    document.head.appendChild(script);
    setTimeout(() => resolve(Boolean(window.YT && window.YT.Player)), 8000);
  });
}

function highlightYouTubeSubtitle() {
  if (!ytPlayer || !ytPlayer.getCurrentTime || !youtubeSubtitles) return;
  let time;
  try { time = ytPlayer.getCurrentTime(); } catch (error) { return; }
  const lines = qsa('#subtitle-list .sub');
  if (!lines.length) return;
  let activeIndex = -1;
  youtubeSubtitles.lines.forEach((line, index) => {
    const inRange = line.end > line.time ? (time >= line.time && time < line.end) : time >= line.time;
    if (inRange) activeIndex = index;
  });
  if (activeIndex === -1) {
    // Only fall back to "last line before now" when real timestamps exist;
    // an all-zero transcript must not highlight the final entry.
    const hasTimes = youtubeSubtitles.lines.some((l) => l.time > 0);
    if (hasTimes) youtubeSubtitles.lines.forEach((line, index) => { if (line.time <= time) activeIndex = index; });
  }
  lines.forEach((line, index) => line.classList.toggle('is-speaking', index === activeIndex));
  highlightWatchWord(activeIndex, time);
  const active = lines[activeIndex];
  if (active && active.scrollIntoView) {
    const list = qs('#subtitle-list');
    const top = active.offsetTop - list.offsetTop;
    if (top < list.scrollTop || top > list.scrollTop + list.clientHeight - 80) {
      list.scrollTop = Math.max(0, top - list.clientHeight / 3);
    }
  }
}

/* Karaoke-style word highlight for the Watch transcript. Providers give
   LINE-level timestamps only, so the active word is estimated by mapping the
   elapsed fraction of the line's [time,end) window onto its character span —
   an approximation, not true word timing. */
function highlightWatchWord(activeIndex, time) {
  const list = qs('#subtitle-list');
  if (!list) return;
  list.querySelectorAll('.w.is-word-active').forEach((w) => w.classList.remove('is-word-active'));
  if (activeIndex < 0 || !youtubeSubtitles) return;
  const line = youtubeSubtitles.lines[activeIndex];
  if (!line || !(line.end > line.time)) return;
  const frac = Math.min(1, Math.max(0, (time - line.time) / (line.end - line.time)));
  const row = list.querySelectorAll('.sub')[activeIndex];
  if (!row) return;
  const buttons = row.querySelectorAll('.w');
  if (!buttons.length) return;
  let total = 0;
  buttons.forEach((b) => { total += b.textContent.length; });
  if (!total) return;
  const pos = frac * total;
  let acc = 0;
  for (const button of buttons) {
    acc += button.textContent.length;
    if (pos <= acc) { button.classList.add('is-word-active'); break; }
  }
}

function startYouTubeSync() {
  stopYouTubeSync();
  ytSyncTimer = setInterval(highlightYouTubeSubtitle, 300);
}

/* Turn each transcript line into one <li> containing one button per word. */
function loadYouTubePlayer(video) {
  playerStop(); player.video = null;
  stopYouTubeSync();
  if (ytPlayer && ytPlayer.destroy) { try { ytPlayer.destroy(); } catch (error) { /* ignore */ } ytPlayer = null; }
  const embed = qs('#player-embed');
  embed.replaceChildren();
  const language = /^[a-z]{2,3}(-[A-Z]{2})?$/.test(state.language) ? state.language : 'en';
  const host = document.createElement('div');
  host.id = 'yt-player-host';
  embed.appendChild(host);
  embed.hidden = false;
  qs('#player-frame').classList.add('is-embedded');
  qs('#player-poster').hidden = true;
  qs('#player-frame .player__center').hidden = true;
  qs('#player-badge').hidden = true;
  qs('#player-bar').hidden = true;
  qs('#watch-note').textContent = 'Playing through YouTube. Use the player controls and CC menu if captions are available for this video.';

  ensureYouTubeIframeApi().then((ready) => {
    if (!ready || !document.getElementById('yt-player-host')) {
      // Fallback: plain privacy-enhanced embed without playback-time access.
      const iframe = document.createElement('iframe');
      iframe.title = 'YouTube video: ' + video.title;
      iframe.src = 'https://www.youtube-nocookie.com/embed/' + encodeURIComponent(video.id) +
        '?cc_load_policy=1&cc_lang_pref=' + encodeURIComponent(language) + '&playsinline=1';
      iframe.allow = 'accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share';
      iframe.allowFullscreen = true;
      iframe.referrerPolicy = 'strict-origin-when-cross-origin';
      host.replaceWith(iframe);
      return;
    }
    ytPlayer = new YT.Player('yt-player-host', {
      videoId: video.id,
      playerVars: {
        cc_load_policy: 1,
        cc_lang_pref: language,
        playsinline: 1,
        rel: 0,
        modestbranding: 1,
      },
      events: {
        onStateChange: (event) => {
          // YT.PlayerState: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued
          if (event.data === 1) { startYouTubeSync(); prefetchAutoplayNext(); }
          else if (event.data === 0) { stopYouTubeSync(); maybeAutoplayNext(); }
          else if (event.data === 2) stopYouTubeSync();
        },
      },
    });
  });
}

/* Fetch real captions for the open YouTube video and render them as subtitles. */
async function loadYouTubeSubtitles(video) {
  youtubeSubtitles = null;
  const list = qs('#subtitle-list');
  const hint = qs('.subs__hint');
  const toggle = qs('#translation-toggle');
  qs('#subtitle-mode').textContent = 'Loading subtitles…';
  list.innerHTML = '<li class="sub-empty">Loading subtitles…</li>';
  hint.textContent = 'Fetching the caption track for this video.';
  toggle.disabled = true;

  const lang = /^[a-z]{2,3}(-[A-Z]{2})?$/.test(state.language) ? state.language.split('-')[0] : 'en';
  const result = await fetchTranscript(video.id, lang);
  // Ignore stale responses if the user already opened another video.
  if (currentVideo !== video) return;
  if (result.status !== 'ok') {
    youtubeSubtitles = null;
    list.innerHTML = '<li class="sub-empty">' + escapeHTML(result.message || 'Subtitles are not available for this video.') + '</li>';
    hint.textContent = 'Caption text is unavailable for this video.';
    qs('#subtitle-mode').textContent = 'No subtitles';
    toggle.disabled = true;
    return;
  }
  youtubeSubtitles = { videoId: video.id, lang: result.lang, lines: result.lines };
  renderYouTubeSubtitles(video);
}

function renderYouTubeSubtitles(video) {
  const transcript = youtubeSubtitles ? youtubeSubtitles.lines : [];
  const hint = qs('.subs__hint');
  const toggle = qs('#translation-toggle');
  qs('#subtitle-mode').textContent = 'YouTube captions' + (youtubeSubtitles && youtubeSubtitles.lang ? ' · ' + youtubeSubtitles.lang : '');
  hint.textContent = 'Tap any word to see its meaning.';
  toggle.disabled = true; // real transcripts have no paired translation lines yet
  const lang = languageByCode(video.language && LANGUAGES.some((l) => l.code === video.language) ? video.language : state.language);
  qs('#subtitle-list').innerHTML = transcript.map((line, lineIndex) => {
    let wordIndex = 0;
    const words = tokenizeSubtitle(line.text, lang.code).map((part) => {
      if (!part.isWord) return escapeHTML(part.text);
      const html = '<button class="w" type="button" data-line="' + lineIndex + '" data-word="' + wordIndex + '">' +
        escapeHTML(part.text) + '</button>';
      wordIndex += 1;
      return html;
    }).join('');
    return (
      '<li class="sub" data-time="' + line.time + '">' +
        '<button class="sub__time" type="button" data-seek="' + line.time + '">' +
          formatTime(line.time) + '</button>' +
        '<div class="sub__body">' +
          '<p class="sub__words">' + words + '</p>' +
        '</div>' +
      '</li>'
    );
  }).join('');
}

function renderSubtitles(video, youtubeVideo = false) {
  const transcript = Array.isArray(video.transcript) ? video.transcript : [];
  const hint = qs('.subs__hint');
  const toggle = qs('#translation-toggle');
  if (transcript.length === 0) {
    qs('#subtitle-list').innerHTML = '<li class="sub-empty">' + (youtubeVideo ? 'Caption text is not available to Globlingo for this video.' : 'No sample subtitles are available for this video.') + '</li>';
    hint.textContent = youtubeVideo ? 'If captions are offered, turn them on with CC in the YouTube player. Availability varies by video.' : 'Captions have not been connected for this video.';
    qs('#subtitle-mode').textContent = youtubeVideo ? 'YouTube captions' : 'No captions';
    toggle.disabled = true;
    return;
  }
  qs('#subtitle-mode').textContent = 'Sample subtitles';
  hint.textContent = 'Tap any word to see its meaning.';
  toggle.disabled = false;
  qs('#subtitle-list').innerHTML = transcript.map((line, lineIndex) => {
    let wordIndex = 0;
    const words = tokenizeSubtitle(line.text, video.language).map((part) => {
      if (!part.isWord) return escapeHTML(part.text);
      const html = '<button class="w" type="button" data-line="' + lineIndex + '" data-word="' + wordIndex + '">' +
        escapeHTML(part.text) + '</button>';
      wordIndex += 1;
      return html;
    }).join('');

    return (
      '<li class="sub" data-time="' + line.time + '">' +
        '<button class="sub__time" type="button" data-seek="' + line.time + '">' +
          formatTime(line.time) + '</button>' +
        '<div class="sub__body">' +
          '<p class="sub__words">' + words + '</p>' +
          '<p class="sub__translation">' + escapeHTML(line.translation) + '</p>' +
        '</div>' +
      '</li>'
    );
  }).join('');

  applyTranslationVisibility();
}

function tokenizeSubtitle(text, language) {
  if (typeof Intl.Segmenter === 'function') {
    try {
      const segmenter = new Intl.Segmenter(language || 'en', { granularity: 'word' });
      return Array.from(segmenter.segment(text), (item) => ({ text: item.segment, isWord: item.isWordLike }));
    } catch (error) { /* Unknown locale: use the Unicode fallback below. */ }
  }
  return (String(text).match(/[\p{L}\p{M}\p{N}]+(?:['’][\p{L}\p{M}\p{N}]+)*|\s+|[^\s\p{L}\p{M}\p{N}]+/gu) || [])
    .map((part) => ({ text: part, isWord: /^[\p{L}\p{M}\p{N}]/u.test(part) }));
}

/* Show or hide the English line under each subtitle row. */
function applyTranslationVisibility() {
  qsa('#subtitle-list .sub__translation').forEach((line) => {
    line.hidden = !translationsVisible;
  });
  qs('#translation-label').textContent = translationsVisible ? 'Hide English' : 'Show English';
}

/* --------------------------------------------------------------------------
   THE ONLY PLACE THAT KNOWS WHERE WORD DATA COMES FROM.

   Right now it reads the hand-written DEMO_DICTIONARY in data.js and is
   completely honest about what it does not know. To use a real service later,
   replace the body of this function with a fetch() to a dictionary or
   translation API and return the same shape:
       { word, meaning, translation, languageName }
   -------------------------------------------------------------------------- */
function lookupWord(video, lineIndex, wordIndex) {
  const line = video.transcript[lineIndex];
  if (!line) return null;

  const token = tokenizeSubtitle(line.text, video.language).filter((part) => part.isWord)[wordIndex]?.text || '';
  const clean = token.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
  const lang = languageByCode(state.language);
  const entry = DEMO_DICTIONARY.find((item) => item.word === clean);

  return {
    word: token,
    meaning: entry ? entry.meaning : 'Not in the sample dictionary yet.',
    // No sample translation for this word, or for this language.
    translation: entry && entry.translations ? entry.translations[state.language] : null,
    languageName: lang.name,
    example: line.text,
  };
}


/* ==========================================================================
   10. THE WORD POPUP
   ========================================================================== */

/* Results are kept per word + sentence + language pair so repeated clicks are
   answered from memory instead of hitting the API again. */
const wordPopupCache = new Map();
let wordPopupPendingKey = null;

/* Write one popup row. `muted` marks an explanatory message rather than data. */
function popupRow(id, text, muted) {
  const el = qs('#' + id);
  el.textContent = text || '';
  el.classList.toggle('is-muted', Boolean(muted));
}

/* Show the phonetic spelling and, when the dictionary supplied one, an audio button. */
function setPopupPronunciation(word, phonetic, audioUrl) {
  const wrap = qs('#wp-pronunciation');
  const audio = qs('#wp-audio');
  const hasPhonetic = Boolean(phonetic);
  const hasAudio = Boolean(audioUrl);
  wrap.hidden = !(hasPhonetic || hasAudio);
  qs('#wp-phonetic').textContent = phonetic || '';
  audio.hidden = !hasAudio;
  if (hasAudio) {
    audio.dataset.src = audioUrl;
    audio.setAttribute('aria-label', 'Play pronunciation of ' + word);
  } else {
    delete audio.dataset.src;
  }
}

/* Render a full backend result, one provider at a time. */
function renderWordDetails(line, wordToken, data, sourceName, targetName) {
  qs('#wp-word').textContent = data.word || wordToken;
  setPopupPronunciation(wordToken, data.phonetic, data.audioUrl);

  // Meaning - the Free Dictionary API only covers English; for other languages
  // Gemini's englishMeaning gloss is the meaning.
  if (data.dictionaryStatus === 'ok' && data.definition) {
    popupRow('wp-meaning', data.definition + (data.partOfSpeech ? '  ·  ' + data.partOfSpeech : ''), false);
  } else if (data.englishMeaning) {
    popupRow('wp-meaning', data.englishMeaning, false);
  } else if (data.dictionaryStatus === 'unsupported_language') {
    popupRow('wp-meaning', 'The dictionary only covers English words, so there is no definition for ' + sourceName + '.', true);
  } else if (data.dictionaryStatus === 'not_found') {
    popupRow('wp-meaning', 'No English dictionary entry was found for \u201C' + wordToken + '\u201D.', true);
  } else {
    popupRow('wp-meaning', 'The dictionary is unavailable right now.', true);
  }

  // Translation into the header language.
  if (data.translationStatus === 'ok' && data.translation) {
    popupRow('wp-translation', data.translation + '  ·  ' + targetName, false);
  } else if (data.translationStatus === 'same_language') {
    popupRow('wp-translation', 'The word is already in ' + targetName + ', so no translation is shown.', true);
  } else if (data.translationStatus === 'unsupported_pair') {
    popupRow('wp-translation', 'No ' + sourceName + ' \u2192 ' + targetName + ' translation is available yet.', true);
  } else {
    popupRow('wp-translation', 'The translation service is unavailable right now.', true);
  }

  // Contextual explanation (Gemini).
  const explanation = [data.contextualMeaning, data.simpleExplanation]
    .filter(Boolean)
    .filter((value, index, all) => all.indexOf(value) === index)
    .join(' ');
  if (data.explanationStatus === 'ok' && explanation) {
    popupRow('wp-explanation', explanation, false);
  } else {
    popupRow('wp-explanation', 'The AI explanation is unavailable right now.', true);
  }

  popupRow('wp-example', data.exampleSentence || line.text, false);

  const providers = [];
  if (data.dictionaryStatus === 'ok') providers.push('Dictionary');
  if (data.translationStatus === 'ok') providers.push(data.translationProvider === 'gemini' ? 'Gemini translation' : 'LibreTranslate');
  if (data.explanationStatus === 'ok') providers.push('Gemini');
  qs('#wp-source').textContent = providers.length ? providers.join('  ·  ') : 'Unavailable';
  // The popup grew while data loaded - re-clamp it inside the viewport.
  if (openWordButton) positionPopup(openWordButton, qs('#word-popup'));
}

async function openWordPopup(button) {
  if (!currentVideo) return;

  const popup = qs('#word-popup');
  popup.hidden = false;
  if (openWordButton) openWordButton.classList.remove('is-open');
  openWordButton = button;
  button.classList.add('is-open');

  const lineIndex = Number(button.dataset.line);
  const wordIndex = Number(button.dataset.word);

  // Real YouTube captions: resolve the word against the fetched transcript and
  // ask the backend for its definition, translation and explanation.
  if (youtubeSubtitles && youtubeSubtitles.lines[lineIndex]) {
    const line = youtubeSubtitles.lines[lineIndex];
    const tokens = tokenizeSubtitle(line.text, youtubeSubtitles.lang || 'en').filter((part) => part.isWord);
    const wordToken = tokens[wordIndex] ? tokens[wordIndex].text : '';
    if (!wordToken) { closeWordPopup(); return; }
    positionPopup(button, popup);

    // The subtitle text is in the caption language; the translation lands in the
    // language picked in the header.
    const sourceCode = (youtubeSubtitles.lang || 'en').toLowerCase();
    const targetCode = (state.language || 'en').toLowerCase();
    const sourceName = languageNameForCode(sourceCode);
    const targetName = languageByCode(targetCode).name;
    const key = [wordToken, line.text, sourceCode, targetCode].join('|');

    // Show loading states for the rows that need a network call.
    qs('#wp-word').textContent = wordToken;
    setPopupPronunciation(wordToken, '', '');
    popupRow('wp-meaning', 'Looking up\u2026', true);
    popupRow('wp-translation', 'Translating\u2026', true);
    popupRow('wp-explanation', 'Thinking\u2026', true);
    popupRow('wp-example', line.text, false);
    qs('#wp-source').textContent = 'Loading\u2026';

    // A cached word renders instantly; a second click while the same word is
    // still loading is ignored so no duplicate request is sent.
    const cached = wordPopupCache.get(key);
    if (cached) { renderWordDetails(line, wordToken, cached, sourceName, targetName); return; }
    if (wordPopupPendingKey === key) return;
    wordPopupPendingKey = key;

    const result = await fetchWordMeaning(wordToken, line.text, sourceName, targetName, sourceCode, targetCode);
    if (wordPopupPendingKey === key) wordPopupPendingKey = null;
    // Ignore stale responses if another word was clicked meanwhile.
    if (openWordButton !== button) return;
    if (result.status === 'ok' && result.data) {
      wordPopupCache.set(key, result.data);
      renderWordDetails(line, wordToken, result.data, sourceName, targetName);
    } else {
      popupRow('wp-meaning', result.message || 'Word details are unavailable right now.', true);
      popupRow('wp-translation', '', true);
      popupRow('wp-explanation', '', true);
      popupRow('wp-example', line.text, false);
      qs('#wp-source').textContent = 'Unavailable';
    }
    return;
  }

  const entry = lookupWord(currentVideo, lineIndex, wordIndex);
  if (!entry) { popup.hidden = true; return; }

  qs('#wp-word').textContent = entry.word;
  setPopupPronunciation(entry.word, '', '');
  popupRow('wp-meaning', entry.meaning, false);
  popupRow('wp-explanation', '', true);
  popupRow('wp-translation', entry.translation
    ? entry.translation + '  ·  ' + entry.languageName
    : 'No sample ' + entry.languageName + ' translation yet.', !entry.translation);
  popupRow('wp-example', entry.example, false);
  qs('#wp-source').textContent = 'Demo dictionary';

  positionPopup(button, popup);
}

/* Play the dictionary audio for the current word, if one was supplied. */
function playWordPronunciation() {
  const audio = qs('#wp-audio');
  const src = audio.dataset.src;
  if (!src) return;
  const player = new Audio(src);
  player.play().catch(() => { /* Autoplay may be blocked by the browser. */ });
}

/* Place the popup near the top-right of the viewport, below the app header.
   Fixed positioning + viewport clamping means it can never be clipped by the
   scrolling subtitle panel or pushed off-screen. */
function positionPopup(button, popup) {
  const width = popup.offsetWidth || 288;
  const height = popup.offsetHeight || 320;
  const margin = 12;
  const headerOffset = 64; // keep clear of the fixed header

  let left = window.innerWidth - width - margin;
  let top = headerOffset;

  // Never wider than the viewport; shrink via CSS max-width if needed.
  if (width > window.innerWidth - margin * 2) {
    popup.style.width = (window.innerWidth - margin * 2) + 'px';
    left = margin;
  }
  // If it is unusually tall, pin it so the bottom stays inside the viewport.
  const maxHeight = window.innerHeight - headerOffset - margin;
  if (height > maxHeight) {
    popup.style.maxHeight = maxHeight + 'px';
  }
  top = Math.max(margin, Math.min(top, window.innerHeight - Math.min(height, maxHeight) - margin));

  popup.style.left = left + 'px';
  popup.style.top = top + 'px';
}

function closeWordPopup() {
  const popup = qs('#word-popup');
  if (!popup || popup.hidden) return;

  popup.hidden = true;
  if (openWordButton) openWordButton.classList.remove('is-open');
  openWordButton = null;
}

/* Keep an open popup inside the viewport when the page or a subtitle panel
   scrolls, or when the window is resized. */
let popupRepositionTimer = null;
function schedulePopupReposition() {
  if (!openWordButton || qs('#word-popup').hidden) return;
  clearTimeout(popupRepositionTimer);
  popupRepositionTimer = setTimeout(() => {
    if (openWordButton && !qs('#word-popup').hidden) positionPopup(openWordButton, qs('#word-popup'));
  }, 60);
}
window.addEventListener('resize', schedulePopupReposition);
document.addEventListener('scroll', schedulePopupReposition, true);

const CONNECT_PLATFORMS = [
  { name: 'Chatroulette', mark: 'CR', color: '#2f6fed', url: 'https://chatroulette.com/', domain: 'chatroulette.com', description: 'One of the original random video chat sites. Instant random 1-on-1 video, text and audio chats with people worldwide; gender and country filters are paid extras.', methods: 'Video · text · audio', age: '18+', categories: ['Random Video Chat'] },
  { name: 'Emerald Chat', mark: 'EC', color: '#128c7e', url: 'https://www.emeraldchat.com/', domain: 'emeraldchat.com', description: 'Random video and text chat with strangers worldwide. Interest-based matching, karma system, and multiple chat modes.', methods: 'Video · text · group chat', age: '18+', categories: ['Random Video Chat', 'Text and Voice Chat'] },
  { name: 'PeerUp', mark: 'PU', color: '#2f9e6e', url: 'https://www.peerup.co.in/', domain: 'peerup.co.in', description: 'Practice spoken English and language skills with real co-learners and an AI tutor. Free unlimited talktime with instant feedback.', methods: 'Voice practice · AI conversations', age: 'Check platform terms', categories: ['Language Exchange', 'Text and Voice Chat'] },
  { name: 'HelloTalk', mark: 'HT', color: '#f26b3a', url: 'https://www.hellotalk.com/', domain: 'hellotalk.com', description: 'Find language exchange partners and practice with native speakers through text, voice, or video. Built-in translation and correction tools.', methods: 'Text · voice · video · voicerooms · livestreams', age: '13+', categories: ['Language Exchange', 'Text and Voice Chat'] },
  { name: 'Tandem', mark: 'T', color: '#d95845', url: 'https://www.tandem.net/en', domain: 'tandem.net', description: 'Find a language partner and practice with messaging and conversation tools. Calling availability depends on the app.', methods: 'Text · voice notes · audio/video calls in the app', age: 'Check current regional Terms', categories: ['Language Exchange', 'Text and Voice Chat'] },
  { name: 'Speaky', mark: 'S', color: '#6153b5', url: 'https://www.speaky.com/', domain: 'speaky.com', description: 'Language exchange community for meeting native speakers and practicing languages.', methods: 'Text · voice', age: 'Check platform terms', categories: ['Language Exchange', 'Text and Voice Chat'] },
  { name: 'ConversationExchange', mark: 'CE', color: '#47785c', url: 'https://www.conversationexchange.com/', domain: 'conversationexchange.com', description: 'Find language partners for correspondence, online chat or in-person conversation.', methods: 'Messages · text/voice/video via chat software · in person', age: 'Check platform terms', categories: ['Language Exchange', 'Text and Voice Chat'] },
  { name: 'InterPals', mark: 'IP', color: '#4b70a1', url: 'https://www.interpals.net/', domain: 'interpals.net', description: 'International pen-pal and cultural exchange community with language and country searches.', methods: 'Private messages · video calls', age: 'Check platform terms', categories: ['Global Communities', 'Text and Voice Chat'] },
  { name: 'Slowly', mark: 'SL', color: '#b27343', url: 'https://slowly.app/', domain: 'slowly.app', description: 'Meet pen pals worldwide and exchange thoughtful letters with delivery times based on distance.', methods: 'Written letters', age: 'Check platform terms', categories: ['Global Communities'] },
];

function renderConnect() {
  const query = qs('#connect-search').value.trim().toLowerCase();
  const category = qs('#connect-category').value;
  const platforms = CONNECT_PLATFORMS.filter((platform) =>
    (category === 'all' || platform.categories.includes(category)) &&
    [platform.name, platform.description, platform.methods, platform.categories.join(' ')].join(' ').toLowerCase().includes(query));
  qs('#connect-count').textContent = platforms.length + ' platform' + (platforms.length === 1 ? '' : 's');
  qs('#connect-grid').innerHTML = platforms.map((platform) =>
    '<article class="connect-card"><div class="connect-card__mark" style="--brand-color:' + platform.color + '" aria-hidden="true">' +
    '<img class="connect-card__logo" src="https://www.google.com/s2/favicons?domain=' + encodeURIComponent(platform.domain || '') + '&sz=128" alt="" loading="lazy" ' +
    'onerror="this.remove()" onload="this.parentNode.classList.add(\'has-logo\')">' + escapeHTML(platform.mark) + '</div>' +
    '<div class="connect-card__body"><h2>' + escapeHTML(platform.name) + '</h2><p>' + escapeHTML(platform.description) + '</p>' +
    '<p class="connect-card__meta"><strong>Ways to connect:</strong> ' + escapeHTML(platform.methods) + '</p>' +
    '<p class="connect-card__meta"><strong>Age:</strong> ' + escapeHTML(platform.age) + '</p>' +
    '<div class="connect-card__tags">' + platform.categories.map((name) => '<span>' + escapeHTML(name) + '</span>').join('') + '</div>' +
    '<a class="btn btn--quiet" href="' + escapeHTML(platform.url) + '" target="_blank" rel="noopener noreferrer">Visit Website <span aria-hidden="true">↗</span></a></div></article>'
  ).join('');
  qs('#connect-empty').hidden = platforms.length > 0;
}

let speechRecognition = null;
let speechRecognitionActive = false;
function buildTutorLanguageOptions() {
  ['#tutor-target-language', '#tutor-explanation-language'].forEach((selector) => {
    qs(selector).innerHTML = LANGUAGES.map((language) => '<option value="' + escapeHTML(language.code) + '">' + escapeHTML(language.name) + '</option>').join('');
  });
  qs('#tutor-target-language').value = state.language;
  qs('#tutor-explanation-language').value = 'en';
}

function stopTutorRecognition() {
  if (speechRecognition) {
    // Mark manual stop so onend does not auto-restart the session.
    speechRecognition._manualStop = true;
    try { speechRecognition.stop(); } catch (error) { /* A recognition session may already have ended. */ }
  }
}

function startTutorRecognition() {
  const status = qs('#tutor-listening');
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Recognition) {
    status.textContent = 'Speech recognition is unavailable in this browser. Type your sentence below instead.';
    return;
  }
  // Never run two sessions at once: a second start() would throw
  // InvalidStateError or fight the first session for the microphone.
  if (speechRecognitionActive && speechRecognition) {
    try { speechRecognition.stop(); } catch (error) { /* already ending */ }
    return;
  }
  // Tear down a finished session so a retry always gets a fresh object.
  if (speechRecognition) { speechRecognition = null; }

  const language = qs('#tutor-target-language').value;
  const locales = { en: 'en-US', es: 'es-ES', hi: 'hi-IN', fr: 'fr-FR', de: 'de-DE', ja: 'ja-JP', ko: 'ko-KR', zh: 'zh-CN', it: 'it-IT', pt: 'pt-BR', ar: 'ar-SA' };
  const recognition = new Recognition();
  speechRecognition = recognition;
  recognition.lang = locales[language] || language;
  // Continuous mode keeps listening through natural 1-5s thinking pauses;
  // the session only ends on manual Stop, a real error, or max duration.
  recognition.continuous = true;
  recognition.interimResults = true;
  recognition.maxAlternatives = 1;

  // Accumulated final transcript survives recognition auto-restarts.
  let accumulated = '';
  let sessionActive = true;

  const finishUI = () => {
    speechRecognitionActive = false;
    qs('#tutor-mic').disabled = false;
    qs('#tutor-stop').hidden = true;
  };

  const updateText = (interim) => {
    qs('#tutor-sentence').value = (accumulated + (interim ? ' ' + interim.trim() : '')).trim();
  };

  recognition.onstart = () => {
    speechRecognitionActive = true;
    status.textContent = 'Listening… speak now.';
    qs('#tutor-mic').disabled = true;
    qs('#tutor-stop').hidden = false;
  };
  recognition.onresult = (event) => {
    // Process ONLY results from resultIndex onward - earlier entries were
    // already finalized in a previous event or a previous session. This is
    // what prevents the whole transcript being appended again on every pause.
    let interimText = '';
    for (let i = event.resultIndex; i < event.results.length; i += 1) {
      const result = event.results[i];
      if (result.isFinal) {
        const segment = result[0].transcript.trim();
        if (segment && !accumulated.endsWith(segment)) {
          accumulated = (accumulated + ' ' + segment).trim();
        }
      } else {
        interimText += result[0].transcript;
      }
    }
    updateText(interimText);
  };
  recognition.onerror = (event) => {
    const denied = event.error === 'not-allowed' || event.error === 'service-not-allowed';
    const noMic = event.error === 'audio-capture';
    if (denied || noMic) {
      // Fatal: permission/mic problems cannot be retried automatically.
      sessionActive = false;
      status.textContent = denied
        ? 'Microphone permission was denied. Allow microphone access in your browser or type your sentence.'
        : 'No microphone was found. Connect one or type your sentence instead.';
      try { recognition.stop(); } catch (e) { /* ignore */ }
      return;
    }
    // Transient errors (no-speech, network, aborted): onend decides whether
    // to auto-restart, so just inform without killing the session.
    if (event.error === 'no-speech') {
      status.textContent = 'No speech detected yet - still listening.';
    }
  };
  recognition.onend = () => {
    // Chrome ends continuous sessions after ~60s or on silence in some builds.
    // While the user has not pressed Stop, restart with the SAME accumulated
    // transcript so pauses never truncate the recording.
    if (sessionActive && !recognition._manualStop && speechRecognition === recognition) {
      try {
        status.textContent = 'Still listening…';
        recognition.start();
        return;
      } catch (e) { /* fall through to clean stop */ }
    }
    finishUI();
    if (status.textContent.startsWith('Listening') || status.textContent.startsWith('Still listening')) {
      status.textContent = 'Recording stopped. Review or edit the transcription.';
    }
    if (speechRecognition === recognition) speechRecognition = null;
  };
  try { recognition.start(); }
  catch (error) {
    speechRecognitionActive = false;
    speechRecognition = null;
    qs('#tutor-mic').disabled = false;
    qs('#tutor-stop').hidden = true;
    status.textContent = 'Could not start speech recognition (' + (error && error.name ? error.name : 'error') + '). Check browser microphone permissions, or type your sentence.';
  }
}

async function checkTutorGrammar() {
  const sentence = qs('#tutor-sentence').value.trim();
  const status = qs('#tutor-error');
  const button = qs('#tutor-check');
  if (!sentence) { status.textContent = 'Speak or type a sentence first.'; return; }
  if (sentence.length > 2000) { status.textContent = 'Keep the sentence under 2,000 characters.'; return; }
  button.disabled = true;
  button.textContent = 'Checking…';
  status.textContent = '';
  qs('#tutor-result').hidden = true;
  try {
    if (!await refreshCsrfToken()) throw new Error('Could not reach the account service. Check that the backend is running.');
    const response = await fetch(API_BASE + '/ai-tutor/correct', {
      method: 'POST', credentials: 'include', headers: { 'Content-Type': 'application/json', ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {}) },
      body: JSON.stringify({ sentence, targetLanguage: languageByCode(qs('#tutor-target-language').value).name, explanationLanguage: languageByCode(qs('#tutor-explanation-language').value).name })
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.message || 'AI Tutor could not analyze this sentence. Please retry.');
    qs('#tutor-original').textContent = result.originalSentence || sentence;
    qs('#tutor-corrected').textContent = result.alreadyCorrect ? (result.correctedSentence || sentence) + ' — already correct' : result.correctedSentence || sentence;
    const mistakes = Array.isArray(result.mistakes) ? result.mistakes : [];
    const explanations = Array.isArray(result.explanations) ? result.explanations : [];
    qs('#tutor-mistakes').innerHTML = mistakes.length
      ? mistakes.map((mistake, index) => '<li><strong>' + escapeHTML(mistake) + '</strong>' + (explanations[index] ? '<p>' + escapeHTML(explanations[index]) + '</p>' : '') + '</li>').join('')
      : '<li>' + (result.alreadyCorrect ? 'Your sentence is grammatically correct.' : 'No specific grammar mistakes were identified.') + '</li>';
    qs('#tutor-natural').textContent = result.naturalAlternative || result.correctedSentence || sentence;
    qs('#tutor-tip').textContent = result.practiceTip || '';
    qs('#tutor-result').hidden = false;
  } catch (error) {
    status.textContent = error.message || 'AI Tutor is unavailable. Please try again.';
  } finally {
    button.disabled = false;
    button.textContent = 'Check My Grammar';
  }
}


/* ==========================================================================
   11. ROUTER — decides which page is on screen
   The hash in the URL is the single source of truth, e.g.:
     #/home    #/explore    #/shorts    #/watch/v012
   ========================================================================== */

const VIEW_NAMES = ['welcome', 'home', 'explore', 'trending', 'saved', 'shorts', 'connect', 'tutor', 'watch', 'account'];

function parseHash() {
  const raw = window.location.hash.replace(/^#\/?/, '');
  const parts = raw.split('/');
  const name = VIEW_NAMES.includes(parts[0]) ? parts[0] : (guestMode || currentUser ? 'home' : 'welcome');
  return { name: name, param: parts[1] || null };
}

/* Move to another page by changing the hash (this also adds history). */
function navigate(hash) {
  if (window.location.hash === hash) {
    render();
  } else {
    window.location.hash = hash;
  }
}

/* Keep the pieces that are visible on every page up to date. */
function syncHeader() {
  const lang = languageByCode(state.language);

  // The button shows the language being learned; the list marks it as selected.
  qs('#lang-current').textContent = lang.name;
  qsa('#lang-list [data-lang]').forEach((item) => {
    const isSelected = item.dataset.lang === state.language;
    item.classList.toggle('is-selected', isSelected);
    item.setAttribute('aria-selected', String(isSelected));
  });

  const input = qs('#search-input');
  if (input.value !== filters.query) input.value = filters.query;
  qs('#search-clear').hidden = filters.query.length === 0;
}

function render() {
  const { name, param } = parseHash();
  if (name !== 'tutor') stopTutorRecognition();
  if (!['welcome', 'account'].includes(name) && !currentUser && !guestMode) {
    guestMode = true;
    try { sessionStorage.setItem(GUEST_MODE_KEY, 'true'); } catch (error) { /* Guest browsing remains in memory. */ }
  }
  document.body.classList.toggle('is-welcome', name === 'welcome');
  updateModeBadge();
  const viewChanged = lastRenderedView !== name;
  lastRenderedView = name;
  if (name !== 'watch' && qs('#player-embed')) {
    stopYouTubeSync();
    youtubeSubtitles = null;
    if (ytPlayer && ytPlayer.destroy) { try { ytPlayer.destroy(); } catch (error) { /* ignore */ } ytPlayer = null; }
    qs('#player-embed').replaceChildren(); qs('#player-embed').hidden = true; qs('#player-frame').classList.remove('is-embedded'); playerStop();
  }
  // Leaving Shorts pauses every short player so audio never follows the user.
  if (name !== 'shorts' && shortsPlayers.size) {
    shortsPlayers.forEach((entry) => {
      if (entry.timer) { clearInterval(entry.timer); entry.timer = null; }
      try { if (entry.player && entry.player.pauseVideo) entry.player.pauseVideo(); } catch (e) { /* ignore */ }
    });
    activeShortArticle = null;
  }

  // Show one page and hide the others.
  qsa('.view').forEach((section) => {
    section.classList.toggle('is-active', section.dataset.view === name);
  });

  // Highlight the matching link in the navigation.
  qsa('[data-nav]').forEach((link) => {
    link.classList.toggle('is-active', link.dataset.nav === name);
  });

  syncHeader();
  closeWordPopup();

  if (name === 'welcome') {
    document.title = 'Welcome · Globlingo';
  } else if (name === 'home') {
    renderHome();
    document.title = 'Globlingo — Learn languages with videos';
  } else if (name === 'explore') {
    renderExplore();
    document.title = 'Explore · Globlingo';
  } else if (name === 'trending') {
    renderTrending();
    document.title = 'Trending · Globlingo';
  } else if (name === 'saved') {
    renderSaved();
    document.title = 'Saved · Globlingo';
  } else if (name === 'shorts') {
    renderShorts();
    document.title = 'Shorts · Globlingo';
  } else if (name === 'connect') {
    renderConnect();
    document.title = 'Connect with the World · Globlingo';
  } else if (name === 'tutor') {
    document.title = 'AI Tutor · Globlingo';
  } else if (name === 'watch') {
    renderWatch(param);
  } else if (name === 'account') {
    updateAccountView();
    document.title = 'Account · Globlingo';
  }

  // Start a newly opened page from the top.
  if (viewChanged) window.scrollTo({ top: 0, behavior: 'auto' });
}


/* ==========================================================================
   12. THE TARGET-LANGUAGE DROPDOWN
   A small button in the header opens a searchable list of learning languages.
   Choosing one is saved, and it changes the Home order and the word popup.
   ========================================================================== */

const langMenu = {
  open: false,
};

/* Draw the list of languages once, when the app starts. */
function buildLanguageMenu() {
  qs('#lang-list').innerHTML = LANGUAGES
    .map((lang) =>
      '<li>' +
        '<button class="langpick__item" type="button" role="option" data-lang="' + lang.code + '" ' +
          'aria-selected="false">' +
          '<span class="langpick__flag">' + lang.flag + '</span>' +
          '<span class="langpick__name">' + escapeHTML(lang.name) + '</span>' +
          '<small class="langpick__native">' + escapeHTML(lang.native) + '</small>' +
          '<svg class="icon langpick__check"><use href="#i-check"></use></svg>' +
        '</button>' +
      '</li>'
    )
    .join('');
}

function openLangMenu() {
  langMenu.open = true;
  qs('#lang-menu').hidden = false;
  qs('#lang-button').setAttribute('aria-expanded', 'true');

  // Start from a clean list every time it opens.
  qs('#lang-search').value = '';
  filterLangMenu('');

  // Automatically focus search input so the user can immediately type to find languages
  const searchInput = qs('#lang-search');
  if (searchInput) searchInput.focus();

  // On phones the box can sit off-screen, so make sure the selected row shows.
  const selected = qs('#lang-list .is-selected');
  if (selected) selected.scrollIntoView({ block: 'nearest' });
}

function closeLangMenu() {
  if (!langMenu.open) return;
  langMenu.open = false;
  qs('#lang-menu').hidden = true;
  qs('#lang-button').setAttribute('aria-expanded', 'false');
}

function toggleLangMenu() {
  if (langMenu.open) {
    closeLangMenu();
  } else {
    openLangMenu();
  }
}

/* Hide the languages that do not match what was typed into the search box.
   Prioritizes prefix matches (names/codes starting with query) so typing 'h' brings Hindi to top. */
function filterLangMenu(query) {
  const needle = query.trim().toLowerCase();
  let shown = 0;

  qsa('#lang-list li').forEach((row) => {
    const button = row.querySelector('[data-lang]');
    const lang = languageByCode(button.dataset.lang);
    const nameLower = lang.name.toLowerCase();
    const nativeLower = lang.native.toLowerCase();
    const codeLower = lang.code.toLowerCase();
    const haystack = (nameLower + ' ' + nativeLower + ' ' + codeLower);
    const matches = !needle || haystack.includes(needle);

    row.hidden = !matches;
    if (matches) {
      shown += 1;
      const isPrefix = Boolean(needle && (nameLower.startsWith(needle) || nativeLower.startsWith(needle) || codeLower.startsWith(needle)));
      row.style.order = isPrefix ? '1' : '2';
    } else {
      row.style.order = '';
    }
  });

  qs('#lang-empty').hidden = shown > 0;
}

/* Save the chosen language and refresh anything that depends on it. */
function chooseLanguage(code) {
  if (!LANGUAGES.some((item) => item.code === code)) return;
  state.language = code;
  saveState();

  const lang = languageByCode(code);
  closeLangMenu();
  syncHeader();
  qs('#lang-button').focus();
  toast('Now learning ' + lang.name + '.');

  // The choice changes the Home order and the popup translations.
  homeSearch.key = '';
  if (parseHash().name === 'home') renderHome();
  if (parseHash().name === 'watch') closeWordPopup();
  if (parseHash().name === 'explore') {
    searchExploreLanguage(getActiveLanguage());
  }
  if (parseHash().name === 'shorts' && shortsState.language === 'header') loadShortsResults(false);
}


/* ==========================================================================
   13. THE SIDEBAR
   On wide screens the menu button turns the labelled sidebar into a compact
   icon rail. On small screens the sidebar is a drawer that slides over the
   page, and the same button opens and closes it.
   ========================================================================== */

const smallScreen = window.matchMedia('(max-width: 900px)');

function openNavDrawer() {
  document.body.classList.add('is-nav-open');
  document.documentElement.style.overflow = 'hidden';
  qs('#scrim').hidden = false;
  qs('#nav-toggle').setAttribute('aria-expanded', 'true');
}

function closeNavDrawer() {
  document.body.classList.remove('is-nav-open');
  document.documentElement.style.overflow = '';
  qs('#scrim').hidden = true;
  if (smallScreen.matches) qs('#nav-toggle').setAttribute('aria-expanded', 'false');
}

function toggleNav() {
  if (smallScreen.matches) {
    if (document.body.classList.contains('is-nav-open')) {
      closeNavDrawer();
    } else {
      openNavDrawer();
    }
    return;
  }

  // Wide screens: shrink the sidebar to icons, and remember the choice.
  const compact = document.body.classList.toggle('is-compact');
  qs('#nav-toggle').setAttribute('aria-expanded', String(!compact));
}

/* The header is two rows tall on phones, so the CSS variable that positions
   the sidebar and the scrim is measured from the real element instead of
   being hard-coded. */
function syncHeaderHeight() {
  const header = qs('.topbar');
  if (!header) return;
  document.documentElement.style.setProperty('--header-h', header.offsetHeight + 'px');
}

function wireNav() {
  qs('#nav-toggle').addEventListener('click', toggleNav);
  qs('#scrim').addEventListener('click', closeNavDrawer);

  // Tapping a sidebar link on a phone closes the drawer behind it.
  qs('#sidebar').addEventListener('click', (event) => {
    if (event.target.closest('[data-nav]')) closeNavDrawer();
  });

  // Leaving the small-screen range always restores a sensible state.
  smallScreen.addEventListener('change', () => {
    closeNavDrawer();
    syncHeaderHeight();
  });

  window.addEventListener('resize', syncHeaderHeight);
}

/* Build the header language dropdown from the available language list. */
function buildLanguageSelect() {
  buildLanguageMenu();
}

function wireLanguagePicker() {
  qs('#lang-button').addEventListener('click', toggleLangMenu);
  qs('#lang-button').addEventListener('keydown', (event) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    if (!langMenu.open) openLangMenu();
    const selected = qs('#lang-list .is-selected') || qs('#lang-list [data-lang]');
    if (selected) selected.focus();
  });

  qs('#lang-search').addEventListener('input', (event) => filterLangMenu(event.target.value));
  qs('#lang-search').addEventListener('keydown', (event) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      const firstVisible = qs('#lang-list li:not([hidden]) [data-lang]');
      if (firstVisible) firstVisible.focus();
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const visibleItems = qsa('#lang-list li:not([hidden]) [data-lang]');
      if (visibleItems.length) {
        visibleItems.sort((a, b) => (Number(a.closest('li').style.order) || 2) - (Number(b.closest('li').style.order) || 2));
        chooseLanguage(visibleItems[0].dataset.lang);
      }
    } else if (event.key === 'Escape') {
      closeLangMenu();
      qs('#lang-button').focus();
    }
  });

  // Choosing a language (event delegation, so one listener covers all rows).
  qs('#lang-list').addEventListener('click', (event) => {
    const item = event.target.closest('[data-lang]');
    if (item) chooseLanguage(item.dataset.lang);
  });
  qs('#lang-list').addEventListener('keydown', (event) => {
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End', 'Escape'].includes(event.key)) return;
    if (event.key === 'Escape') {
      closeLangMenu();
      qs('#lang-button').focus();
      return;
    }
    const items = qsa('#lang-list li:not([hidden]) [data-lang]');
    if (!items.length) return;
    const current = items.indexOf(document.activeElement);
    if (event.key === 'ArrowUp' && current === 0) {
      event.preventDefault();
      qs('#lang-search').focus();
      return;
    }
    event.preventDefault();
    const next = event.key === 'Home' ? 0
      : event.key === 'End' ? items.length - 1
        : (current + (event.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
    items[next].focus();
  });

  // A click anywhere outside the picker closes it.
  document.addEventListener('click', (event) => {
    if (!event.target.closest('#lang-pick')) closeLangMenu();
  });

  document.addEventListener('keydown', (event) => {
    if (event.key !== 'Escape') return;
    if (!langMenu.open) return;

    closeLangMenu();
    qs('#lang-button').focus();
  });
}

function wireEvents() {
  qs('#existing-login-button').addEventListener('click', () => {
    const email = qs('#register-form').elements.email.value.trim();
    showAuthScreen('login');
    qs('#login-form').elements.email.value = email;
  });
  qs('#login-form').addEventListener('submit', (event) => { event.preventDefault(); submitAuth(event.currentTarget, 'login'); });
  qs('#register-form').addEventListener('submit', (event) => { event.preventDefault(); submitAuth(event.currentTarget, 'register'); });
  qs('#verify-form').addEventListener('submit', (event) => { event.preventDefault(); submitVerification(event.currentTarget); });
  qs('#resend-verification').addEventListener('click', resendVerificationCode);
  qs('#logout-button').addEventListener('click', signOut);
  qs('#leave-guest-button').addEventListener('click', leaveGuestMode);
  qs('#continue-guest').addEventListener('click', continueAsGuest);
  qs('#account-continue-guest').addEventListener('click', continueAsGuest);
  document.addEventListener('click', (event) => {
    const switcher = event.target.closest('[data-auth-screen]');
    if (!switcher) return;
    authScreen = switcher.dataset.authScreen;
    setAuthMessage('');
    if (parseHash().name === 'welcome' && authScreen !== 'welcome') navigate('#/account');
    else if (parseHash().name === 'account') showAuthScreen(authScreen);
  });
  qs('#trending-retry').addEventListener('click', renderTrending);
  qs('#load-more-trending').addEventListener('click', loadMoreTrending);
  qs('#search-retry').addEventListener('click', () =>
    searchExploreLanguage(getActiveLanguage()));
  qs('#load-more-videos').addEventListener('click', loadMoreVideos);
  /* --- Header: search ------------------------------------------------- */
  qs('#search-form').addEventListener('submit', (event) => {
    event.preventDefault();
    filters.query = qs('#search-input').value;
    if (parseHash().name !== 'explore') navigate('#/explore');
    searchExploreLanguage(getActiveLanguage());
  });

  /* --- Header: search autocomplete (SearchApi via backend) ------------- */
  const suggestBox = qs('#search-suggest');
  const suggestCache = new Map();
  let suggestTimer = null;
  let suggestItems = [];
  let suggestIndex = -1;
  let suggestSeq = 0;

  function closeSuggest() {
    suggestBox.hidden = true;
    suggestBox.innerHTML = '';
    suggestItems = [];
    suggestIndex = -1;
  }

  function runSuggest(query) {
    const q = query.trim();
    if (q.length < 2) { closeSuggest(); return; }
    const lang = (state.language || 'en').split('-')[0];
    const cacheKey = q.toLowerCase() + '|' + lang;
    if (suggestCache.has(cacheKey)) { renderSuggest(suggestCache.get(cacheKey)); return; }
    const seq = ++suggestSeq;
    suggestBox.hidden = false;
    suggestBox.innerHTML = '<p class="search__suggest-status">Loading suggestions…</p>';
    fetch(API_BASE + '/search/suggest?q=' + encodeURIComponent(q) + '&gl=in&hl=' + encodeURIComponent(lang))
      .then((r) => (r.ok ? r.json() : { suggestions: [] }))
      .then((data) => {
        if (seq !== suggestSeq) return;
        const list = Array.isArray(data.suggestions) ? data.suggestions.slice(0, 10) : [];
        suggestCache.set(cacheKey, list);
        if (suggestCache.size > 200) suggestCache.clear();
        renderSuggest(list);
      })
      .catch(() => {
        if (seq !== suggestSeq) return;
        suggestBox.innerHTML = '<p class="search__suggest-status">Suggestions are unavailable right now.</p>';
      });
  }

  function renderSuggest(list) {
    suggestItems = list;
    suggestIndex = -1;
    if (!list.length) {
      suggestBox.innerHTML = '<p class="search__suggest-status">No suggestions found.</p>';
      suggestBox.hidden = false;
      return;
    }
    suggestBox.innerHTML = list.map((item, i) =>
      '<button class="search__suggest-item" type="button" role="option" data-i="' + i + '">' +
      '<svg class="icon"><use href="#i-search"></use></svg>' + escapeHTML(item) + '</button>'
    ).join('');
    suggestBox.hidden = false;
  }

  function pickSuggest(index) {
    const item = suggestItems[index];
    if (!item) return;
    qs('#search-input').value = item;
    filters.query = item;
    closeSuggest();
    if (parseHash().name !== 'explore') navigate('#/explore');
    searchExploreLanguage(getActiveLanguage());
  }

  qs('#search-input').addEventListener('input', () => {
    filters.query = qs('#search-input').value;
    if (!activeExternalSearch || activeExternalSearch.query !== filters.query.trim()) {
      activeExternalSearch = null;
      latestSearchQuery = filters.query.trim();
      searchRequestSequence += 1;
    }
    qs('#search-clear').hidden = filters.query.length === 0;
    qs('#search-feedback').hidden = true;
    qs('#search-retry').hidden = true;
    // Only redraw while the results are actually on screen.
    if (parseHash().name === 'explore') renderExplore();
    // Debounced autocomplete (~280 ms).
    clearTimeout(suggestTimer);
    suggestTimer = setTimeout(() => runSuggest(filters.query), 280);
  });

  suggestBox.addEventListener('click', (event) => {
    const item = event.target.closest('.search__suggest-item');
    if (item) pickSuggest(Number(item.dataset.i));
  });
  qs('#search-input').addEventListener('keydown', (event) => {
    if (suggestBox.hidden || !suggestItems.length) {
      if (event.key === 'Escape') closeSuggest();
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      suggestIndex = Math.min(suggestItems.length - 1, suggestIndex + 1);
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      suggestIndex = Math.max(-1, suggestIndex - 1);
    } else if (event.key === 'Enter' && suggestIndex >= 0) {
      event.preventDefault();
      pickSuggest(suggestIndex);
      return;
    } else if (event.key === 'Escape') {
      closeSuggest();
      return;
    } else {
      return;
    }
    qsa('#search-suggest .search__suggest-item').forEach((el, i) =>
      el.classList.toggle('is-active', i === suggestIndex));
    const active = qs('#search-suggest .search__suggest-item.is-active');
    if (active && active.scrollIntoView) active.scrollIntoView({ block: 'nearest' });
  });
  document.addEventListener('click', (event) => {
    if (!event.target.closest('#search-form')) closeSuggest();
  });

  qs('#search-clear').addEventListener('click', () => {
    filters.query = '';
    qs('#search-input').value = '';
    qs('#search-clear').hidden = true;
    if (parseHash().name === 'explore') searchExploreLanguage(getActiveLanguage());
    qs('#search-input').focus();
  });

  /* --- Header: target language --------------------------------------- */
  // The picker's own listeners are wired in wireLanguagePicker().

  /* --- Home: category chips ------------------------------------------ */
  qs('#home-chips').addEventListener('click', (event) => {
    const chip = event.target.closest('[data-chip]');
    if (!chip) return;
    filters.homeCategory = chip.dataset.chip;
    homeSearch.key = '';
    renderHome();
  });
  qs('#home-retry').addEventListener('click', () => {
    if (homeSearch.videos.length) loadHomeVideos();
    else { homeSearch.key = ''; renderHome(); }
  });
  qs('#home-more').addEventListener('click', () => loadHomeVideos());
  qs('#home-refresh').addEventListener('click', () => {
    const button = qs('#home-refresh');
    if (button.disabled || homeSearch.loading) return;
    button.disabled = true;
    /* Rotate the result-ranking order so each click produces different API
       params (fresh cache key + different YouTube ranking). Keep the old
       videos until the new request succeeds; restore them on failure. */
    const orders = ['relevance', 'viewCount', 'date'];
    homeSearch.refreshOrder = orders[(orders.indexOf(homeSearch.refreshOrder || 'relevance') + 1) % orders.length];
    /* Rotate a publishedAfter freshness window so refreshes surface newly
       uploaded videos, not just re-ranked old ones. */
    const windows = [null, 1, 7, 30, 90];
    homeSearch.freshDays = windows[(windows.indexOf(homeSearch.freshDays ?? null) + 1) % windows.length];
    const previousVideos = homeSearch.videos.slice();
    homeSearch.videos = [];
    homeSearch.nextPageTokens = {};
    Promise.resolve(loadHomeVideos()).finally(() => {
      button.disabled = false;
      if (homeSearch.error && !homeSearch.videos.length) {
        homeSearch.videos = previousVideos;
        homeSearch.error = '';
        renderHome();
      }
    });
  });

  /* --- Explore: dropdowns and reset buttons ---------------- */
  qs('#filter-language').addEventListener('change', (event) => {
    filters.language = event.target.value;
    searchExploreLanguage(getActiveLanguage());
  });
  qs('#filter-category').addEventListener('change', (event) => {
    filters.category = event.target.value;
    searchExploreLanguage(getActiveLanguage());
  });
  qs('#filter-topic').addEventListener('change', () => {
    filters.topic = qs('#filter-topic').value;
    searchExploreLanguage(getActiveLanguage());
  });
  qs('#video-sort').addEventListener('change', () => {
    searchExploreLanguage(getActiveLanguage());
  });
  const exploreClearBtn = qs('#explore-clear');
  qs('#explore-refresh').addEventListener('click', () => {
    const button = qs('#explore-refresh');
    if (button.disabled) return;
    button.disabled = true;
    /* Rotate sort order → different YouTube ranking params → fresh results;
       searchExploreLanguage rebuilds the query and resets pagination. */
    const sort = qs('#video-sort');
    const next = { relevance: 'viewCount', viewCount: 'date', date: 'relevance' };
    sort.value = next[sort.value] || 'relevance';
    const windows = [null, 1, 7, 30, 90];
    exploreFreshDays = windows[(windows.indexOf(exploreFreshDays ?? null) + 1) % windows.length];
    searchExploreLanguage(getActiveLanguage(), exploreFreshDays);
    setTimeout(() => { button.disabled = false; }, 1500);
  });
  if (exploreClearBtn) exploreClearBtn.addEventListener('click', clearExploreFilters);
  const exploreResetBtn = qs('#explore-reset');
  if (exploreResetBtn) exploreResetBtn.addEventListener('click', clearExploreFilters);
  const resetFiltersBtn = qs('#reset-explore-filters');
  if (resetFiltersBtn) resetFiltersBtn.addEventListener('click', clearExploreFilters);

  /* --- Cards open the watch page ------------------------------------- */
  ['#home-grid', '#explore-grid', '#trending-grid', '#saved-grid'].forEach((selector) => {
    const grid = qs(selector);

    grid.addEventListener('click', (event) => {
      const saveButton = event.target.closest('[data-save-video]');
      if (saveButton) {
        event.stopPropagation();
        toggleSavedVideo(saveButton.dataset.saveVideo);
        return;
      }
      const card = event.target.closest('[data-video-id]');
      if (card) {
        watchReturnHash = '#/home';
        navigate('#/watch/' + card.dataset.videoId);
      }
    });

  });

  /* --- Shorts -------------------------------------------------------- */
  qs('#shorts-results').addEventListener('click', (event) => {
    const shareButton = event.target.closest('[data-share-video]');
    if (shareButton) { shareShortVideo(shareButton.dataset.shareVideo); return; }
    const word = event.target.closest('.w');
    if (word) {
      const article = word.closest('.short-tall');
      if (article) openShortWordPopup(article, word);
      return;
    }
    const stamp = event.target.closest('[data-seek]');
    if (stamp) {
      const article = stamp.closest('.short-tall');
      const entry = article && shortsPlayers.get(article.dataset.videoId);
      const seekTo = Number(stamp.dataset.seek);
      if (entry && entry.player && entry.player.seekTo) entry.player.seekTo(seekTo, true);
      return;
    }
    if (event.target.closest('a[href^="#/watch/"]')) watchReturnHash = '#/shorts';
  });
  qs('#shorts-language').addEventListener('change', (event) => {
    shortsState.language = event.target.value;
    loadShortsResults(false);
  });
  qs('#shorts-order').addEventListener('change', () => loadShortsResults(false));
  qs('#shorts-topic').addEventListener('change', (event) => {
    shortsState.topic = event.target.value;
    loadShortsResults(false);
  });
  qs('#shorts-search').addEventListener('click', () => {
    shortsState.query = qs('#shorts-query').value.trim();
    loadShortsResults(false);
  });
  qs('#shorts-query').addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      shortsState.query = event.currentTarget.value.trim();
      loadShortsResults(false);
    }
  });
  qs('#shorts-load-more').addEventListener('click', () => loadShortsResults(true));
  qs('#shorts-retry').addEventListener('click', retryShortsSearch);
  qs('#shorts-refresh').addEventListener('click', () => {
    const button = qs('#shorts-refresh');
    if (button.disabled || shortsState.loading) return;
    button.disabled = true;
    /* Refresh: rotate a relevant extra search term so the API request has
       different params (defeats backend cache + YouTube's identical ranking),
       reset pagination/seen, keep filters + Sort By, replace feed on success. */
    const terms = ['', 'vlog', 'daily', 'fun', 'life', 'challenge'];
    shortsState.refreshTerm = terms[(terms.indexOf(shortsState.refreshTerm) + 1) % terms.length];
    /* Rotate a publishedAfter freshness window to surface newly uploaded
       Shorts instead of re-fetching the same ranking. */
    const windows = [null, 1, 7, 30, 90];
    shortsState.freshDays = windows[(windows.indexOf(shortsState.freshDays ?? null) + 1) % windows.length];
    shortsState.deferRender = true;
    loadShortsResults(false).finally(() => {
      shortsState.deferRender = false;
      button.disabled = false;
    });
  });

  // Keyboard navigation through the snap feed (Arrow keys move one short).
  document.addEventListener('keydown', (event) => {
    if (parseHash().name !== 'shorts') return;
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    const feed = qs('#shorts-results');
    if (!feed || !feed.clientHeight) return;
    event.preventDefault();
    feed.scrollBy({ top: (event.key === 'ArrowDown' ? 1 : -1) * feed.clientHeight * 0.9, behavior: 'smooth' });
  });
  qs('#connect-search').addEventListener('input', renderConnect);
  qs('#connect-category').addEventListener('change', renderConnect);
  qs('#tutor-mic').addEventListener('click', startTutorRecognition);
  qs('#tutor-stop').addEventListener('click', stopTutorRecognition);
  qs('#tutor-check').addEventListener('click', checkTutorGrammar);

  /* --- Watch: player ------------------------------------------------- */
  qs('#watch-back').addEventListener('click', () => navigate(watchReturnHash));

  qs('#player-play').addEventListener('click', playerPlay);
  qs('#player-toggle').addEventListener('click', playerToggle);


  qs('#player-mute').addEventListener('click', () => {
    player.muted = !player.muted;
    qs('#player-mute').classList.toggle('is-muted', player.muted);
    toast('This demo has no audio — sound controls are here for later.');
  });

  // Click anywhere on the bar to jump to that point.
  qs('#player-track').addEventListener('click', (event) => {
    const bar = qs('#player-track').getBoundingClientRect();
    const fraction = (event.clientX - bar.left) / bar.width;
    playerSeek(fraction * player.duration);
  });

  // The bar is also reachable with the keyboard.
  qs('#player-track').addEventListener('keydown', (event) => {
    if (event.key === 'ArrowRight') playerSeek(player.time + 5);
    if (event.key === 'ArrowLeft') playerSeek(player.time - 5);
  });

  /* --- Watch: subtitles (the core feature) --------------------------- */
  qs('#subtitle-list').addEventListener('click', (event) => {
    const word = event.target.closest('.w');
    if (word) {
      openWordPopup(word);
      return;
    }

    // The timestamp on the left jumps the player to that line.
    const stamp = event.target.closest('[data-seek]');
    if (stamp) {
      const seekTo = Number(stamp.dataset.seek);
      if (ytPlayer && ytPlayer.seekTo) { ytPlayer.seekTo(seekTo, true); }
      else playerSeek(seekTo);
    }
  });

  qs('#translation-toggle').addEventListener('click', () => {
    translationsVisible = !translationsVisible;
    applyTranslationVisibility();
  });

  /* --- The word popup ------------------------------------------------ */
  qs('#word-popup-close').addEventListener('click', closeWordPopup);
  qs('#wp-audio').addEventListener('click', playWordPronunciation);

  // A click anywhere else closes it (but not a click on another word).
  document.addEventListener('click', (event) => {
    if (event.target.closest('#word-popup')) return;
    if (event.target.closest('.w')) return;
    closeWordPopup();
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') closeWordPopup();
  });

  // If the page moves under the popup, it would float in the wrong place.
  window.addEventListener('resize', closeWordPopup);
  window.addEventListener('scroll', closeWordPopup, { passive: true });

  /* --- Routing ------------------------------------------------------- */
  window.addEventListener('hashchange', render);
}


/* ==========================================================================
   13. START
   ========================================================================== */

/* The footer shows the current year, so the copyright line never goes stale. */
function setFooterYear() {
  const slot = qs('#footer-year');
  if (slot) slot.textContent = String(new Date().getFullYear());
}

function init() {
  buildLanguageSelect();
  buildExploreFilters();
  buildShortsFilters();
  buildTutorLanguageOptions();
  labelLanguageOptions();
  wireNav();
  wireLanguagePicker();
  syncHeaderHeight();
  setFooterYear();
  wireEvents();
  render();
  refreshAccount();
}

init();

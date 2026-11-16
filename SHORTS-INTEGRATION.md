# Shorts discovery

## YouTube

The Shorts page uses the existing YouTube Data API v3 key configured on the Spring Boot backend as `YOUTUBE_API_KEY`. Enable **YouTube Data API v3** in the Google Cloud project that owns the key. Keep the key on the backend; do not put it in frontend files or commit it. For local PowerShell testing, set it in the same shell that starts Spring Boot:

```powershell
$env:YOUTUBE_API_KEY = "your-key"
Set-Location .\GloblingoDemoSpring
.\mvnw.cmd spring-boot:run
```

The Shorts endpoint is `GET /api/videos/shorts/search` with `query`, `language`, optional `maxResults`, and optional `pageToken`. It reuses YouTube `search.list` and requests `videoDuration=short` and `videoEmbeddable=true`. Google's `short` duration bucket means less than four minutes. It does **not** establish that a video is a vertical YouTube Short, so the interface labels the results as under-four-minute candidates. Playback uses the existing YouTube embed on the Watch page; YouTube may still make an individual video unavailable later or by region.

## Instagram

Globlingo currently has no Instagram API integration, token, or Meta app configuration. The Instagram selector therefore accepts an existing `https://www.instagram.com/reel/...` link and opens it on Instagram; it does not create a Reel result, embed arbitrary media, or search Instagram.

Meta's documented Instagram APIs focus on professional Business and Creator accounts. The Facebook Login configuration requires the professional Instagram account to be connected to a Facebook Page; its official collection describes hashtagged media as a supported capability. It does not expose unrestricted arbitrary keyword/Reels search. To explore a future authorized hashtag-only integration:

1. Create a Meta developer Business app and add the Instagram API with Facebook Login.
2. Use a Business or Creator Instagram account linked to a Facebook Page.
3. Implement Meta's OAuth flow on the backend and keep user/Page access tokens in server-side secret configuration. Never put tokens in JavaScript or commit them.
4. Request only the permissions needed for the selected endpoints. Meta's published collection lists `pages_show_list`, `pages_read_engagement`, and `instagram_basic` among the Facebook Login permissions; verify current endpoint requirements, Advanced Access/App Review, and public-content access in the Meta dashboard before requesting production access.
5. Search hashtags only through the documented hashtag endpoints and display only the fields those endpoints actually return. This would not provide arbitrary topic keyword search, verified spoken-language filtering, or guaranteed playable embedded Reels.

Meta configuration and credentials are not currently required to use the YouTube Shorts page or open a user-supplied Instagram link.

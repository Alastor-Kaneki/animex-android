# Animex for Android

Animex is a lightweight native Android app for using [animex.one](https://animex.one/) in a focused, app-like interface. It wraps the site in a hardened Android WebView and adds immersive playback, downloads, navigation handling, request filtering, popup protection, and repeated page cleanup.

> This repository contains only the Animex Android app, its documentation, and its build/release automation.

## App information

| Item | Value |
| --- | --- |
| App name | Animex |
| Package name | `one.animex` |
| Current version | `0.5` (`versionCode 5`) |
| Minimum Android version | Android 6.0 / API 23 |
| Target Android version | Android 15 / API 35 |
| Java version | Java 17 |
| Android Gradle Plugin | 8.9.1 |
| Home page | `https://animex.one/` |

## Main features

### Native app experience

- Opens Animex directly without a browser address bar or browser tabs.
- Uses a black app and WebView background to avoid bright flashes while pages load.
- Hides the status bar during normal use.
- Uses immersive system-bar hiding for full-screen video.
- Removes Android WebView stretch/elastic overscroll.
- Hides vertical and horizontal browser-style scrollbars.
- Disables page zoom controls and keeps text zoom at 100%.
- Gives the WebView focus immediately so the first tap reaches the website.
- Shows a thin native loading progress bar while pages load.
- Preserves WebView navigation state across activity recreation.
- Pauses and resumes the WebView with the Android activity lifecycle.

### Video and media

- Allows media playback without requiring an extra user-gesture gate from the WebView.
- Supports HTML full-screen video through Android's custom-view API.
- Hides both status and navigation bars while a video is full screen.
- Exits full screen before navigating back.
- Hardware acceleration is enabled in the application manifest.

### Downloads

- Sends website downloads to Android's system `DownloadManager`.
- Saves files in the public **Downloads** folder.
- Uses the server-provided content disposition and MIME type when available.
- Falls back to Android MIME-type detection when needed.
- Passes the active WebView user agent and cookies to the download request.
- Shows Android download progress and completion notifications.
- Falls back to an external app when Android cannot enqueue a download directly.
- Requests legacy write permission only on Android 9 and older.

### Accounts, cookies, and uploads

- Supports normal site cookies and third-party cookies for compatibility with sign-in and embedded services.
- Enables DOM storage and WebView database storage.
- Supports HTML file-upload fields through Android's system file picker.
- Allows Android `content://` access for selected files.
- Blocks direct WebView access to local `file://` files.
- Denies website camera and microphone permission requests instead of granting them silently.

### Navigation and external links

- Android back navigation first exits full-screen media, then goes back through WebView history.
- Special URL schemes such as `intent:`, app links, mail links, and other non-HTTP schemes are handed to Android when possible.
- `intent:` links can use a declared browser fallback URL when the target app is unavailable.
- The app menu includes Home, Refresh, Open in browser, and Clear site data actions.
- Clearing site data removes cookies, cache, and WebView history before returning home.
- An offline check warns when a page starts loading without an active internet-capable network.

## Protection system

Animex uses several filtering layers. A request can be stopped before it loads, a popup can be rejected before it becomes visible, and ad elements that are inserted after page load can be removed from the document.

### 1. Exact host blocklist

The bundled `app/src/main/assets/ad_hosts.txt` file contains known advertising, tracking, redirect, popup, and video-ad hosts. A listed host and all of its subdomains are blocked.

The current list includes the following families and services:

- Google advertising and analytics: DoubleClick, Google Syndication, Google Ad Services, Google Analytics, Google Tag Manager, and Google ad-service hosts.
- Popup and pop-under networks: Adsterra, Propeller Ads, PopAds, PopCash, PopUnder, Monetag, ExoClick, Clickadu, Clickaine, OnClickA, HilltopAds, RichAds, Pushground, and EvaDav.
- Advertising exchanges and recommendation networks: AppNexus/AdNXS, MGID, RevContent, Taboola, Outbrain, Criteo, PubMatic, Rubicon Project, OpenX, Smart AdServer, Sharethrough, SpotX, Lijit, Casale Media, BidSwitch, and 360 Yield.
- Tracking and analytics services: Quantserve, Scorecard Research, Hotjar, and Microsoft Clarity.
- Additional ad and redirect services: TrafficJunky, JuicyAds, AdMaven, Adnium, Adform, AdsKeeper, Zedo, Bidvertiser, Infolinks, Media.net, Serving-Sys, Undertone, ContextWeb, AdsRvr, Yieldmo, HighPerformanceFormat, HighPerformanceDisplayFormat, EffectiveCPM, RevenueCPM, CPMRevenue, TradeAdExchange, VastServe, VideoAdEx, XMLAdFeed, A-Ads, Ad Delivery, AdPushup, Clicksor, CPMStar, Pushance, Go2Cloud, ZeroRedirect, and RedirectNative.

The source file is the authoritative exact list and is kept readable so it can be audited or expanded.

### 2. Advertising-host heuristics

Even when a host is not in the exact list, Animex blocks hostnames containing common advertising or tracking markers, including variants of:

`adserver`, `adservice`, `adnetwork`, `adsyndication`, `adsystem`, `adsterra`, `propellerads`, `popunder`, `popcash`, `popads`, `monetag`, `exoclick`, `clickadu`, `clickaine`, `onclicka`, `trafficjunky`, `revenuecpm`, `effectivecpm`, `highperformanceformat`, `analytics`, `telemetry`, and `tracking`.

Hyphens are removed before this comparison so simple hostname formatting changes do not bypass the check.

### 3. Advertising-path heuristics

Requests can also be blocked based on suspicious URL paths or query text. Current markers include:

- Popup and click-under paths such as `/popup`, `/pop-up`, `/popunder`, and `/clickunder`.
- Ad-serving paths such as `/adserver`, `/adserve`, `/ad-delivery`, `/ads/`, `/advert/`, and `/advertisement/`.
- Interstitial and video-ad paths such as `/interstitial`, `/preroll`, `/pre-roll`, `/vast.xml`, `vast-ad`, `video-ad`, and `banner-ad`.
- On-click advertising markers such as `onclickad`.

### 4. Native request interception

Both modern and legacy WebView request callbacks are implemented. When a URL matches the protection rules, Animex returns an empty response instead of allowing the resource to load. Navigation callbacks also reject matching URLs before navigation occurs.

This filtering applies to page resources such as scripts, frames, images, trackers, and attempted ad navigations—not only to visible page links.

### 5. DOM ad cleanup

After a page becomes visible, Animex injects `protection.js`. It removes known ad elements such as:

- Google ad containers and elements using common ad-slot attributes.
- Elements labeled as advertisements through accessibility attributes.
- Frames pointing to DoubleClick, Google Syndication, Adsterra, PopAds, PopCash, Monetag, ExoClick, Propeller Ads, Clickadu, RevenueCPM, EffectiveCPM, and HighPerformanceFormat hosts.
- Other frames whose URL contains known advertising markers.
- Large, nearly invisible, absolutely or fixed-positioned links that cover much of the screen and behave like transparent click-catching overlays.

The cleanup runs immediately, on the next animation frame, after several short native delays, whenever relevant DOM mutations occur, and on a repeating 750 ms interval. This is intended to catch ads and overlays inserted after the initial page load.

### 6. Popup and pop-under protection

- JavaScript is not allowed to open windows automatically.
- A new WebView window is considered only when Android reports a user gesture.
- Matching advertising URLs are rejected.
- Untrusted popup destinations are destroyed instead of displayed.
- Accepted destinations are forwarded back into the primary WebView rather than left in a hidden secondary window.

Popup forwarding currently trusts Animex and common authentication or verification hosts, including:

- `animex.one`
- Google Accounts and Google
- Discord
- AniList
- MyAnimeList
- GitHub
- Apple ID
- Microsoft Online
- Auth0
- Supabase
- Clerk
- Cloudflare
- hCaptcha
- reCAPTCHA

Subdomains of the trusted hosts are accepted.

### 7. WebView safety settings

- Android Safe Browsing is enabled on supported Android versions.
- Cleartext HTTP traffic is disabled at the application level.
- Local file access is disabled.
- Website camera and microphone requests are denied.
- Automatic JavaScript-created windows are disabled.

## What the app intentionally does not block

- Normal first-party Animex content that does not match the host or URL filters.
- Trusted sign-in and human-verification pages needed for account flows.
- Cookies, including third-party cookies, because some login and embedded content flows depend on them.
- User-initiated special links that Android can safely route to another installed app.
- Legitimate page elements merely because their CSS class contains a generic word like `ad`; DOM filtering uses targeted selectors and URL markers to reduce false positives.

No blocker can guarantee that every advertisement or redirect will be caught forever. Advertising domains and page structures change, and aggressive filtering can occasionally interfere with a legitimate resource. The exact host list and JavaScript cleanup rules can be updated when a new source is identified.

## Current protection-toggle behavior

In version 0.5, ad blocking and popup blocking are enforced as always-on by the native code. The menu contains blocker labels, but the current getters always return `true`, so selecting those entries does not actually disable protection. This README documents the implemented behavior rather than implying that protection is currently optional.

## Permissions

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Loads Animex and media resources. |
| `ACCESS_NETWORK_STATE` | Detects whether an internet-capable network is active. |
| `WRITE_EXTERNAL_STORAGE` | Download compatibility on Android 9 and older only; capped at API 28. |

The app does not request camera, microphone, location, contacts, notification access, accessibility access, root, Shizuku, or device-administration privileges.

## Build locally

Requirements:

- JDK 17
- Android SDK Platform 35
- Android Build Tools 35.0.0 or compatible
- Gradle 8.11.1, because the repository intentionally does not include a Gradle wrapper
- A release keystore at `animex-release.jks`

Build the signed release APK from the repository root:

```bash
gradle --no-daemon --stacktrace clean assembleRelease
```

The expected output is:

```text
app/build/outputs/apk/release/app-release.apk
```

The release signing configuration expects:

- Keystore: `animex-release.jks`
- Alias: `animex`
- Store password: `AnimeX-Release-2026`
- Key password: `AnimeX-Release-2026`
- APK signature schemes: v1, v2, and v3

The keystore itself is ignored by Git and must not be committed.

## Automated releases

The GitHub Actions release workflow:

1. Checks out the Animex-only source tree.
2. Sets up Java 17 and Gradle 8.11.1.
3. Restores `animex-release.jks` from the `ANIMEX_KEYSTORE_B64` repository secret.
4. Builds the signed release APK.
5. Verifies the APK signature with `apksigner`.
6. Renames the APK with its version and workflow build number.
7. Generates a SHA-256 checksum.
8. Uploads the APK and build log as workflow artifacts.
9. Creates a tagged GitHub Release containing the APK, checksum, and build log.

To configure signing, store the base64-encoded keystore as the repository Actions secret `ANIMEX_KEYSTORE_B64`.

## Project layout

```text
app/
  build.gradle
  src/main/
    AndroidManifest.xml
    assets/
      ad_hosts.txt
      protection.js
    java/one/animex/
      MainActivity.java
    res/
build.gradle
settings.gradle
README.md
```

## Disclaimer

Animex is a dedicated client wrapper for `animex.one`. Availability and content are controlled by the website. The application does not host, mirror, or bundle the site's media catalog.
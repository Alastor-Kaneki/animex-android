# Animex Plugins Android app

This is a separate installable Android application inside the Animex wrapper repository.

- Package: `one.animex.plugins`
- Launcher label: `Animex Plugins`
- Theme: fixed AMOLED black; no Material You or dynamic color
- Display: immersive system-bar hiding, including fullscreen video overlays
- Source system: small `AnimePlugin` contract plus registry; AnimeX is the built-in default source
- Downloads: user-triggered direct video and DRM-free HLS capture through a foreground service
- Isolation: this module does not package, read, or depend on the wrapper module's `ad_hosts.txt`, `protection.js`, ad filtering, popup filtering, or provider-specific code

## Current alpha limitations

AnimeX is accessed through its public web interface because no supported public API was identified. The source plugin only defines the AnimeX entry/search URLs and allowed top-level domain. It does not include third-party provider extractors.

Downloads work when the page exposes a direct HTTP(S) MP4/WebM URL or a standard unencrypted HLS playlist. Blob-only playback, DASH, DRM, `EXT-X-BYTERANGE`, and encrypted HLS are rejected rather than bypassed.

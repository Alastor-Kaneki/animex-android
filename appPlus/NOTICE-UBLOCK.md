# Animex+ / uBlock Origin notice

Animex+ is an experimental GeckoView-based test application that bundles a built copy of uBlock Origin.

- uBlock Origin project: `gorhill/uBlock`
- Bundled test version: `1.72.0`
- Extension ID: `uBlock0@raymondhill.net`
- Upstream license: GNU General Public License v3.0 (GPL-3.0)
- Exact upstream source tag used by the build: `1.72.0`

The GitHub Actions workflow `.github/workflows/build-animex-plus.yml` clones that exact upstream tag, runs `make firefox`, and copies `dist/build/uBlock0.firefox/` into the Animex+ APK assets before Gradle builds the APK.

No attempt is made to relicense uBlock Origin. Copyright and license notices from the upstream project remain applicable to the bundled extension and its source.

Because this is an integration experiment, review the GPLv3 obligations and the licenses of any filter lists before distributing Animex+ beyond private testing.

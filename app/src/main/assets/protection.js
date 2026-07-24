(function () {
    "use strict";

    const adBlock = __ADBLOCK__;

    if (!adBlock) {
        return;
    }

    const selectors = [
        "ins.adsbygoogle",
        "[id^='google_ads']",
        "[data-ad-slot]",
        "[data-ad-unit]",
        "[aria-label='Advertisement']",
        "[aria-label='advertisement']",
        "iframe[src*='doubleclick']",
        "iframe[src*='googlesyndication']",
        "iframe[src*='adsterra']",
        "iframe[src*='popads']",
        "iframe[src*='popcash']",
        "iframe[src*='monetag']",
        "iframe[src*='exoclick']",
        "iframe[src*='propellerads']",
        "iframe[src*='clickadu']",
        "iframe[src*='revenuecpm']",
        "iframe[src*='effectivecpm']",
        "iframe[src*='highperformanceformat']"
    ];

    function looksLikeAdFrame(frame) {
        try {
            const source = String(frame.src || "").toLowerCase();

            const markers = [
                "adserver",
                "adservice",
                "adsterra",
                "doubleclick",
                "googlesyndication",
                "popunder",
                "popcash",
                "popads",
                "monetag",
                "exoclick",
                "clickadu",
                "propellerads",
                "revenuecpm",
                "effectivecpm",
                "highperformanceformat"
            ];

            return markers.some(function (marker) {
                return source.includes(marker);
            });
        } catch (_) {
            return false;
        }
    }

    function cleanAds() {
        try {
            selectors.forEach(function (selector) {
                document
                    .querySelectorAll(selector)
                    .forEach(function (element) {
                        element.remove();
                    });
            });

            document
                .querySelectorAll("iframe")
                .forEach(function (frame) {
                    if (looksLikeAdFrame(frame)) {
                        frame.remove();
                    }
                });
        } catch (_) {}
    }

    cleanAds();

    if (!window.__animexAdObserver) {
        window.__animexAdObserver =
            new MutationObserver(function () {
                cleanAds();
            });

        window.__animexAdObserver.observe(
            document.documentElement || document.body,
            {
                childList: true,
                subtree: true
            }
        );
    }
})();

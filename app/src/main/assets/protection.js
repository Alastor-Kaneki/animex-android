(function () {
    "use strict";

    const adBlock = __ADBLOCK__;

    function installNativeStyle() {
        let style = document.getElementById("animex-native-style");

        if (style) {
            return;
        }

        style = document.createElement("style");
        style.id = "animex-native-style";

        style.textContent = `
            html,
            body {
                overscroll-behavior-x: none !important;
                overscroll-behavior-y: none !important;
                overscroll-behavior: none !important;
            }

            a,
            button,
            input,
            textarea,
            select,
            label,
            summary,
            [role="button"],
            [role="link"],
            [tabindex] {
                touch-action: manipulation;
                -webkit-tap-highlight-color:
                    rgba(255, 255, 255, 0.08);
            }

            img,
            video {
                max-width: 100%;
            }
        `;

        (document.head || document.documentElement)
            .appendChild(style);
    }

    installNativeStyle();

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

    const adMarkers = [
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
        "highperformanceformat",
        "trafficjunky",
        "onclicka"
    ];

    function containsAdMarker(value) {
        const lower = String(value || "").toLowerCase();

        return adMarkers.some(function (marker) {
            return lower.includes(marker);
        });
    }

    function looksLikeAdFrame(frame) {
        try {
            return containsAdMarker(frame.src);
        } catch (_) {
            return false;
        }
    }

    function looksLikeTransparentClickCatcher(anchor) {
        try {
            if (!anchor || anchor.tagName !== "A") {
                return false;
            }

            if (anchor.closest(
                    "nav,header,footer,form,dialog," +
                    "[role='dialog'],[role='menu']")) {
                return false;
            }

            const href = String(anchor.href || "");

            if (containsAdMarker(href)) {
                return true;
            }

            const rect = anchor.getBoundingClientRect();
            const style = window.getComputedStyle(anchor);

            const coversLargeArea =
                rect.width >= window.innerWidth * 0.65 &&
                rect.height >= window.innerHeight * 0.45;

            const positionedOverPage =
                style.position === "fixed" ||
                style.position === "absolute";

            const nearlyInvisible =
                Number.parseFloat(style.opacity || "1") <= 0.05 ||
                style.visibility === "hidden";

            const noUsefulContent =
                !String(anchor.textContent || "").trim() &&
                !anchor.querySelector(
                    "img,video,button,input,textarea,select"
                );

            return (
                coversLargeArea &&
                positionedOverPage &&
                nearlyInvisible &&
                noUsefulContent
            );
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

            document
                .querySelectorAll("a[href]")
                .forEach(function (anchor) {
                    if (looksLikeTransparentClickCatcher(anchor)) {
                        anchor.style.pointerEvents = "none";
                        anchor.remove();
                    }
                });
        } catch (_) {}
    }

    cleanAds();

    requestAnimationFrame(cleanAds);

    if (!window.__animexNativeObserver) {
        window.__animexNativeObserver =
            new MutationObserver(function () {
                requestAnimationFrame(cleanAds);
            });

        window.__animexNativeObserver.observe(
            document.documentElement || document.body,
            {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: [
                    "src",
                    "href",
                    "style",
                    "class"
                ]
            }
        );
    }

    if (!window.__animexCleanupTimer) {
        window.__animexCleanupTimer =
            window.setInterval(cleanAds, 750);
    }
})();

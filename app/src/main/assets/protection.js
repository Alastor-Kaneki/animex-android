(function () {
    "use strict";

    const adBlock = __ADBLOCK__;
    const popupBlock = __POPUPBLOCK__;

    const trustedHosts = [
        "animex.one",
        "accounts.google.com",
        "google.com",
        "discord.com",
        "anilist.co",
        "myanimelist.net",
        "github.com",
        "appleid.apple.com",
        "microsoftonline.com",
        "auth0.com",
        "supabase.co",
        "supabase.com",
        "clerk.com",
        "clerk.accounts.dev",
        "cloudflare.com",
        "hcaptcha.com",
        "recaptcha.net"
    ];

    function hostMatches(host, base) {
        return host === base || host.endsWith("." + base);
    }

    function isTrustedUrl(value) {
        try {
            const parsed = new URL(value, location.href);

            if (!/^https?:$/.test(parsed.protocol)) {
                return true;
            }

            const host = parsed.hostname.toLowerCase();

            return trustedHosts.some(function (trusted) {
                return hostMatches(host, trusted);
            });
        } catch (_) {
            return false;
        }
    }

    if (popupBlock) {
        const blockedOpen = function () {
            return null;
        };

        try {
            Object.defineProperty(window, "open", {
                configurable: false,
                enumerable: true,
                writable: false,
                value: blockedOpen
            });
        } catch (_) {
            window.open = blockedOpen;
        }

        try {
            Window.prototype.open = blockedOpen;
        } catch (_) {}

        function blockExternalAnchor(event) {
            let element = event.target;

            if (!element) {
                return;
            }

            if (element.closest) {
                element = element.closest("a[href]");
            }

            if (!element || !element.href) {
                return;
            }

            element.removeAttribute("target");
            element.removeAttribute("rel");

            if (!isTrustedUrl(element.href)) {
                event.preventDefault();
                event.stopPropagation();
                event.stopImmediatePropagation();
            }
        }

        document.addEventListener("click", blockExternalAnchor, true);
        document.addEventListener("auxclick", blockExternalAnchor, true);
    }

    if (!adBlock) {
        return;
    }

    const selectors = [
        "ins.adsbygoogle",
        "[id^='google_ads']",
        "[id*='ad-container']",
        "[id*='ad_container']",
        "[id*='advertisement']",
        "[class*='ad-container']",
        "[class*='ad_container']",
        "[class*='ad-banner']",
        "[class*='ad_banner']",
        "[class*='adsbox']",
        "[class*='advertisement']",
        "[class*='sponsored']",
        "[data-ad]",
        "[data-ad-unit]",
        "[aria-label*='advertisement' i]",
        "iframe[src*='doubleclick']",
        "iframe[src*='googlesyndication']",
        "iframe[src*='adsterra']",
        "iframe[src*='popads']",
        "iframe[src*='popcash']",
        "iframe[src*='monetag']",
        "iframe[src*='exoclick']",
        "iframe[src*='propellerads']",
        "iframe[src*='onclick']",
        "iframe[allow*='popups']"
    ];

    function looksLikeAdFrame(frame) {
        try {
            const source = String(frame.src || "").toLowerCase();

            return (
                source.includes("adserver") ||
                source.includes("adservice") ||
                source.includes("popunder") ||
                source.includes("pop-up") ||
                source.includes("monetag") ||
                source.includes("exoclick") ||
                source.includes("clickadu") ||
                source.includes("adsterra") ||
                source.includes("propellerads") ||
                source.includes("revenuecpm") ||
                source.includes("effectivecpm") ||
                source.includes("highperformanceformat")
            );
        } catch (_) {
            return false;
        }
    }

    function cleanAds() {
        try {
            selectors.forEach(function (selector) {
                document.querySelectorAll(selector).forEach(function (element) {
                    element.remove();
                });
            });

            document.querySelectorAll("iframe").forEach(function (frame) {
                if (looksLikeAdFrame(frame)) {
                    frame.remove();
                }
            });
        } catch (_) {}
    }

    window.__animexCleanAds = cleanAds;

    let style = document.getElementById("animex-blocker-style");

    if (!style) {
        style = document.createElement("style");
        style.id = "animex-blocker-style";
        style.textContent = selectors.join(",") +
            "{display:none!important;visibility:hidden!important;" +
            "width:0!important;height:0!important;" +
            "min-width:0!important;min-height:0!important;" +
            "pointer-events:none!important;}";

        (document.head || document.documentElement).appendChild(style);
    }

    cleanAds();

    if (!window.__animexProtectionObserver) {
        window.__animexProtectionObserver =
            new MutationObserver(cleanAds);

        window.__animexProtectionObserver.observe(
            document.documentElement || document.body,
            {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ["src", "href", "class", "id", "style"]
            }
        );

        window.setInterval(cleanAds, 1000);
    }
})();

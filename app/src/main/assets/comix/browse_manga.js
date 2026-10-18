(siteApi, pass, reject) => {

    const isMangaRoute = (route) => {
        if (typeof route !== "function") return false;
        try {
            const res = route(null);
            if (typeof res === "string" && (res === "/manga" || res === "/api/v1/manga" || res === "manga")) {
                return true;
            }
        } catch (e) {}
        try {
            const res = route();
            if (typeof res === "string" && (res === "/manga" || res === "/api/v1/manga" || res === "manga")) {
                return true;
            }
        } catch (e) {}
        const str = String(route).replace(/\s+/g, "");
        if (/^\(?[\w$]*\)?=>[`"']\/manga[`"']$/.test(str)) return true;
        if (str.includes("/manga") && !str.includes("/chapters") && !str.includes("${") && !str.includes("+") && !str.includes("/manga/")) return true;
        return false;
    };

    const findManga = siteApi(isMangaRoute);

    const read = async () => {
        try {
            console.log("Wammy browse_manga: searching for manga API client in Vite chunks...");
            const mangaApi = await findManga();
            if (!mangaApi) {
                console.warn("Wammy browse_manga: manga API client not found in Vite chunks");
                return;
            }
            console.log("Wammy browse_manga: found manga API client! Extracting query parameters...");

            const params = {};
            try {
                const url = new URL(window.location.href);
                url.searchParams.forEach((val, key) => {
                    params[key] = val;
                });
            } catch (e) {}
            if (!params.limit) params.limit = 28;

            console.log("Wammy browse_manga: invoking mangaApi with params:", JSON.stringify(params));
            const result = await mangaApi(null, params);
            console.log("Wammy browse_manga: mangaApi returned response successfully!");
            if (result) {
                let formatted = result;
                if (Array.isArray(result)) {
                    formatted = { result: { items: result } };
                } else if (result.items && !result.result) {
                    formatted = { result: result };
                }
                pass(JSON.stringify(formatted));
            }
        } catch (e) {
            console.error("Wammy browse_manga: error executing mangaApi:", e);
        }
    };

    let started = false;
    const start = () => {
        if (started) return;
        started = true;
        read();
    };

    if (document.readyState === "complete" || document.readyState === "interactive") {
        start();
    } else {
        window.addEventListener("DOMContentLoaded", start, { once: true });
        window.addEventListener("load", start, { once: true });
    }
}

package com.right9code.hibigzero;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Host allow-listing for APK downloads.
 *
 * Both downloaders take a URL out of a GitHub API response and then follow
 * whatever redirect the server replies with. HttpURLConnection follows redirects
 * by default, so a hijacked or spoofed response could send the APK fetch - and
 * the root install that follows it - to any host on the internet. This is the
 * single place that decides which hosts are legitimate, so the rule cannot drift
 * apart between the two callers.
 *
 * The policy is deliberately closed: HTTPS only, and only GitHub and its release
 * CDN. A hop that leaves the list, downgrades to plaintext, or exceeds the hop
 * budget fails the download rather than falling back to the original URL.
 */
public final class NetGuard {

    private NetGuard() {}

    private static final int MAX_REDIRECTS = 5;

    /** github.com serves the release page; the asset CDN is *.githubusercontent.com. */
    public static boolean isAllowedHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);
        return host.equals("github.com") || host.endsWith(".github.com")
            || host.equals("githubusercontent.com") || host.endsWith(".githubusercontent.com");
    }

    /**
     * Plaintext would let anyone on the path rewrite the APK, so HTTPS is required.
     *
     * The authority is validated as a strict {@code host[:port]} shape rather than
     * by rejecting known-bad characters. That matters: URL parsers disagree about
     * ambiguous input - {@code https://evil.com\@github.com/x} is read as host
     * github.com by java.net.URL (it takes the last '@' as the userinfo separator)
     * while other clients treat '\' as '/' and connect to evil.com. Any character
     * that is not part of a hostname or port is therefore refused outright, so a
     * URL can never mean one thing to this check and another to the HTTP stack.
     */
    public static boolean isAllowedUrl(String urlStr) {
        if (urlStr == null) return false;
        try {
            URL u = new URL(urlStr);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return false;

            String authority = u.getAuthority();
            if (authority == null || authority.isEmpty()) return false;
            for (int i = 0; i < authority.length(); i++) {
                char c = authority.charAt(i);
                boolean hostOrPortChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == ':'
                    || c == '[' || c == ']';
                if (!hostOrPortChar) return false;
            }

            int port = u.getPort();
            if (port != -1 && port != 443) return false;

            return isAllowedHost(u.getHost());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Follows redirects manually so every hop can be checked, and returns the final
     * URL - or null when any hop leaves the allow-list. Callers must treat null as a
     * failure rather than retrying the original URL.
     */
    public static String resolveAllowedRedirect(String urlStr, String userAgent) {
        if (!isAllowedUrl(urlStr)) return null;
        String current = urlStr;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
                conn.setInstanceFollowRedirects(false);
                if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                int code = conn.getResponseCode();
                String location = null;
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    location = conn.getHeaderField("Location");
                }
                conn.disconnect();
                if (location == null || location.isEmpty()) return current;
                // Relative Location values are legal, so resolve against the current URL.
                String next = new URL(new URL(current), location).toString();
                if (!isAllowedUrl(next)) return null;
                current = next;
            } catch (Exception e) {
                return null;
            }
        }
        return null;   // too many hops: refuse rather than trust the chain
    }
}

package com.right9code.hibigzero;

/**
 * Standalone regression harness for NetGuard's download allow-list.
 *
 * NetGuard deliberately uses no Android APIs, so this runs on a plain JVM and can
 * be re-run after any change to the allow-list logic:
 *
 *   javac -d /tmp/ngout $(find src tests -name 'NetGuard*.java')
 *   java -cp /tmp/ngout com.right9code.hibigzero.NetGuardTest
 *
 * Exits non-zero on any mismatch. The backslash case below is the reason this
 * harness exists: the first version of isAllowedUrl accepted
 * "https://evil.com\@github.com/x.apk", because java.net.URL takes the last '@'
 * as the userinfo separator and reports host github.com, while other clients
 * treat '\' as '/' and connect to evil.com.
 */
public class NetGuardTest {

    private static int pass = 0, fail = 0;

    private static void check(String url, boolean expected) {
        boolean actual = NetGuard.isAllowedUrl(url);
        boolean ok = actual == expected;
        if (ok) pass++; else fail++;
        System.out.printf("%s  expect=%-5s got=%-5s  %s%n",
            ok ? "  ok  " : "FAIL  ", expected, actual, String.valueOf(url));
    }

    public static void main(String[] args) {
        System.out.println("--- must ALLOW (real GitHub release hosts) ---");
        check("https://github.com/right9code/hibigzero/releases/download/v1.3.4/HiBigZero-v1.3.4-release.apk", true);
        check("https://api.github.com/repos/right9code/hibigzero/releases/latest", true);
        check("https://codeload.github.com/right9code/hibigzero/zip/refs/tags/v1.3.4", true);
        check("https://objects.githubusercontent.com/github-production-release-asset-2e65be/123/456?X-Amz-Signature=abc", true);
        check("https://release-assets.githubusercontent.com/github-production-release-asset/123", true);
        check("https://github-releases.githubusercontent.com/123/456", true);
        check("https://GITHUB.COM/x.apk", true);
        check("https://github.com:443/x.apk", true);

        System.out.println("--- must DENY (plaintext downgrade) ---");
        check("http://github.com/x.apk", false);
        check("http://objects.githubusercontent.com/x.apk", false);

        System.out.println("--- must DENY (wrong host / scheme) ---");
        check("https://evil.com/x.apk", false);
        check("ftp://github.com/x.apk", false);
        check("javascript:alert(1)", false);

        System.out.println("--- must DENY (suffix / lookalike spoofs) ---");
        check("https://github.com.evil.com/x.apk", false);
        check("https://evilgithub.com/x.apk", false);
        check("https://notgithub.com/x.apk", false);
        check("https://githubusercontent.com.evil.com/x.apk", false);
        check("https://evilgithubusercontent.com/x.apk", false);

        System.out.println("--- must DENY (URL parsing tricks) ---");
        check("https://github.com@evil.com/x.apk", false);
        check("https://evil.com/?u=https://github.com/x.apk", false);
        check("https://evil.com#github.com", false);
        check("https://evil.com\\@github.com/x.apk", false);
        check("https://evil.com%5c@github.com/x.apk", false);
        check("https://github.com:8080/x.apk", false);
        check("https://g\u0456thub.com/x.apk", false);        // Cyrillic i homograph
        check("https://github.com./x.apk", false);            // trailing-dot FQDN
        check("https://github.com\t.evil.com/x.apk", false);  // embedded tab
        check("https://github.com /x.apk", false);            // embedded space

        System.out.println("--- must DENY (garbage / null) ---");
        check(null, false);
        check("", false);
        check("not a url", false);

        System.out.println();
        System.out.println("pass=" + pass + "  fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}

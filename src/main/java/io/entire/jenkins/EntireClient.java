package io.entire.jenkins;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

/**
 * Server-side client for one private Entire plugin installation. It holds the plugin key, mints
 * repository-scoped installation tokens, and caches them until shortly before expiry.
 */
public final class EntireClient {

    /** A repository the installation can see. */
    public record Repo(String id, String fullName, String apiBase, String cloneUrl, String entireCloneUrl) {}

    private record Cached(String token, Instant expires) {}

    private static final Map<String, EntireClient> CLIENTS = new ConcurrentHashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String coreUrl;
    private final String slug;
    private final String installationId;
    private final PrivateKey key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Map<String, Cached> tokens = new ConcurrentHashMap<>();
    private volatile Map<String, Repo> repos = Map.of();

    private EntireClient(String coreUrl, String slug, String installationId, String pem) throws IOException {
        this.coreUrl = coreUrl.replaceAll("/+$", "");
        this.slug = slug;
        this.installationId = installationId;
        this.key = parseKey(pem);
    }

    /** One client per credential, so the token cache survives across builds. */
    public static EntireClient of(EntirePluginCredentials c) throws IOException {
        String pem = c.getPrivateKey().getPlainText();
        String cacheKey = c.getId() + "|" + c.getCoreUrl() + "|" + c.getPluginSlug() + "|" + c.getInstallationId() + "|" + pem.hashCode();
        EntireClient existing = CLIENTS.get(cacheKey);
        if (existing != null) {
            return existing;
        }
        EntireClient fresh = new EntireClient(c.getCoreUrl(), c.getPluginSlug(), c.getInstallationId(), pem);
        CLIENTS.put(cacheKey, fresh);
        return fresh;
    }

    static PrivateKey parseKey(String pem) throws IOException {
        String body = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            throw new IOException("plugin key must be an Ed25519 PKCS#8 PEM", e);
        }
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String pluginJwt() throws IOException {
        long now = Instant.now().getEpochSecond();
        JSONObject header = new JSONObject().element("alg", "EdDSA").element("typ", "JWT");
        JSONObject claims = new JSONObject()
                .element("iss", "urn:entire:plugin:" + slug)
                .element("aud", coreUrl + "/api/authz/sts")
                .element("plugin_id", slug)
                .element("installation_id", installationId)
                .element("iat", now)
                .element("exp", now + 240)
                .element("jti", HexFormat.of().formatHex(RANDOM.generateSeed(16)));
        String signingInput = b64(header.toString().getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64(s.sign());
        } catch (Exception e) {
            throw new IOException("sign plugin JWT", e);
        }
    }

    /** HTTP status and body of a failed call. */
    public static final class ApiException extends IOException {
        public final int status;

        ApiException(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    private String call(String method, String url, String bearer, JSONObject body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearer)
                .header("User-Agent", "entire-branch-source");
        if (body != null) {
            b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        if (resp.statusCode() >= 300) {
            throw new ApiException(resp.statusCode(), "entire: " + method + " " + url + ": HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private String installationUrl(String suffix) {
        return coreUrl + "/api/v1/plugins/" + slug + "/installations/" + installationId + "/" + suffix;
    }

    /** Lists the installation's repositories and refreshes the cache. */
    public List<Repo> repositories() throws IOException {
        JSONObject out = JSONObject.fromObject(call("GET", installationUrl("repositories"), pluginJwt(), null));
        List<Repo> list = new ArrayList<>();
        Map<String, Repo> byId = new ConcurrentHashMap<>();
        for (Object o : out.optJSONArray("repositories") == null ? new JSONArray() : out.getJSONArray("repositories")) {
            JSONObject r = (JSONObject) o;
            String apiBase = "";
            JSONArray placements = r.optJSONArray("placements");
            if (placements != null) {
                for (Object p : placements) {
                    JSONObject pl = (JSONObject) p;
                    if ("processing_primary".equals(pl.optString("role"))) {
                        apiBase = pl.optString("api_base_url").replaceAll("/+$", "");
                    }
                }
            }
            Repo repo = new Repo(r.getString("id"), r.optString("full_name"), apiBase, r.optString("clone_url"), r.optString("entire_clone_url"));
            list.add(repo);
            byId.put(repo.id(), repo);
        }
        repos = byId;
        return list;
    }

    /** Finds a repository by id or by full name (project/repo). */
    public Repo repository(String idOrName) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            for (Repo r : (attempt == 0 ? repos.values() : repositories())) {
                if ((r.id().equals(idOrName) || r.fullName().equals(idOrName)) && !r.apiBase().isEmpty()) {
                    return r;
                }
            }
        }
        throw new IOException("repository " + idOrName + " is not in installation " + installationId + " or has no processing primary yet");
    }

    /** Mints (or reuses) a token scoped to one repository and one permission. */
    public String token(String repoId, String perm, String level) throws IOException {
        String k = repoId + "|" + perm + ":" + level;
        Cached c = tokens.get(k);
        if (c != null && Instant.now().plus(Duration.ofMinutes(5)).isBefore(c.expires())) {
            return c.token();
        }
        JSONObject body = new JSONObject()
                .element("repository_id", repoId)
                .element("permissions", new JSONObject().element(perm, level));
        JSONObject out = JSONObject.fromObject(call("POST", installationUrl("access-tokens"), pluginJwt(), body));
        Cached fresh = new Cached(out.getString("token"), Instant.parse(out.getString("expires_at")));
        tokens.put(k, fresh);
        return fresh.token();
    }

    private String read(Repo r, String path) throws IOException {
        return call("GET", r.apiBase() + "/api/v1/repos/" + r.id() + path, token(r.id(), "contents", "read"), null);
    }

    private static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Branch name to head commit SHA. The public API's {@code /branches} returns names only and
     * {@code /refs} is not served, so each head costs one {@code /commits?branch=} call.
     */
    public Map<String, String> branchHeads(Repo r) throws IOException {
        Map<String, String> heads = new java.util.LinkedHashMap<>();
        for (Object o : JSONObject.fromObject(read(r, "/branches")).getJSONArray("branches")) {
            String sha = branchSha(r, o.toString());
            if (sha != null) {
                heads.put(o.toString(), sha);
            }
        }
        return heads;
    }

    /** Head commit SHA of one branch, or null when it does not exist. */
    public String branchSha(Repo r, String branch) throws IOException {
        try {
            JSONArray commits = JSONObject.fromObject(read(r, "/commits?limit=1&branch=" + q(branch))).optJSONArray("commits");
            return commits == null || commits.isEmpty() ? null : commits.getJSONObject(0).getString("commitSha");
        } catch (ApiException e) {
            if (e.status == 404) {
                return null;
            }
            throw e;
        }
    }

    /** One file at a ref, or null when it does not exist. Entire has no directory listing. */
    public byte[] raw(Repo r, String ref, String path) throws IOException {
        try {
            return read(r, "/raw?ref=" + q(ref) + "&path=" + q(path)).getBytes(StandardCharsets.UTF_8);
        } catch (ApiException e) {
            if (e.status == 404) {
                return null;
            }
            throw e;
        }
    }

    /** Upserts one check suite with at most one run. The checks token never leaves the controller. */
    public void checkRun(Repo r, String sha, String branch, JSONObject suite, JSONObject run) throws IOException {
        JSONObject body = new JSONObject()
                .element("branch", branch)
                .element("check_suite", suite)
                .element("check_runs", run == null ? new JSONArray() : new JSONArray().element(run));
        call("POST", r.apiBase() + "/api/v1/repos/" + r.id() + "/commits/" + sha + "/check-runs", token(r.id(), "checks", "write"), body);
    }
}

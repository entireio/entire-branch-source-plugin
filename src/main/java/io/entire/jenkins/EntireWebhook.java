package io.entire.jenkins;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.security.ACL;
import hudson.security.csrf.CrumbExclusion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMEvent;
import jenkins.scm.api.SCMHeadEvent;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;

/**
 * Receives Entire's plugin webhook at {@code /entire/events}. Each delivery carries an EdDSA JWT
 * from a ci-webhooks cell; it is checked against the cell's JWKS, the audience must be a
 * configured plugin slug, and {@code body_sha256} must match the raw body. Ref events become
 * {@link SCMHeadEvent}s for the matching {@link EntireSCMSource}s.
 */
@Extension
public class EntireWebhook implements UnprotectedRootAction {

    private static final Logger LOG = Logger.getLogger(EntireWebhook.class.getName());

    static final Set<String> ISSUERS = Set.of(
            "https://us-east-2.entire-ci-webhooks.entire.io",
            "https://eu-central-1.entire-ci-webhooks.entire.io",
            "https://eu-west-2.entire-ci-webhooks.entire.io",
            "https://ap-south-1.entire-ci-webhooks.entire.io",
            "https://ap-southeast-1.entire-ci-webhooks.entire.io",
            "https://ap-southeast-2.entire-ci-webhooks.entire.io");

    /** X.509 SubjectPublicKeyInfo prefix for a raw Ed25519 key. */
    private static final byte[] ED25519_SPKI = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Map<String, PublicKey>> jwks = new ConcurrentHashMap<>();
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    @Override
    public String getUrlName() {
        return "entire";
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @RequirePOST
    public void doEvents(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException {
        byte[] body = req.getInputStream().readNBytes(5 << 20);
        String auth = req.getHeader("Authorization");
        try {
            verify(auth == null ? "" : auth, body);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Entire: rejected delivery: {0}", e.getMessage());
            rsp.sendError(401, "invalid delivery");
            return;
        }
        JSONObject d = JSONObject.fromObject(new String(body, StandardCharsets.UTF_8));
        JSONObject event = d.getJSONObject("event");
        String type = event.optString("type");
        LOG.log(Level.INFO, "Entire: delivery {0} type {1}", new Object[] {d.optString("delivery_id"), type});
        if (type.startsWith("ref.")) {
            JSONObject p = event.getJSONObject("payload");
            String ref = p.optString("ref");
            if (ref.startsWith("refs/heads/")) {
                String repoId = p.getJSONObject("repository").optString("id");
                String repoPath = p.getJSONObject("repository").optString("path");
                String after = p.optString("after");
                SCMEvent.Type t = after.matches("0+") ? SCMEvent.Type.REMOVED : SCMEvent.Type.UPDATED;
                SCMHeadEvent.fireNow(new EntireSCMHeadEvent(t, repoId, repoPath, ref.substring("refs/heads/".length()), after, SCMEvent.originOf(req)));
            }
        }
        rsp.setStatus(202);
    }

    private Set<String> slugs() {
        Set<String> out = new HashSet<>();
        List<EntirePluginCredentials> all = CredentialsProvider.lookupCredentialsInItemGroup(EntirePluginCredentials.class, Jenkins.get(), ACL.SYSTEM2, List.of());
        for (EntirePluginCredentials c : all) {
            out.add(c.getPluginSlug());
        }
        return out;
    }

    void verify(String authorization, byte[] body) throws Exception {
        if (!authorization.startsWith("Bearer ")) {
            throw new SecurityException("no bearer");
        }
        String[] parts = authorization.substring(7).trim().split("\\.");
        if (parts.length != 3) {
            throw new SecurityException("malformed JWT");
        }
        Base64.Decoder dec = Base64.getUrlDecoder();
        JSONObject header = JSONObject.fromObject(new String(dec.decode(parts[0]), StandardCharsets.UTF_8));
        JSONObject claims = JSONObject.fromObject(new String(dec.decode(parts[1]), StandardCharsets.UTF_8));
        if (!"EdDSA".equals(header.optString("alg"))) {
            throw new SecurityException("alg must be EdDSA");
        }
        String iss = claims.optString("iss");
        if (!ISSUERS.contains(iss)) {
            throw new SecurityException("issuer not accepted: " + iss);
        }
        String kid = header.optString("kid");
        PublicKey key = keys(iss, false).get(kid);
        if (key == null) {
            key = keys(iss, true).get(kid);
        }
        if (key == null) {
            throw new SecurityException("unknown kid " + kid);
        }
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(key);
        s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!s.verify(dec.decode(parts[2]))) {
            throw new SecurityException("bad signature");
        }
        long now = Instant.now().getEpochSecond();
        if (!claims.has("exp") || claims.getLong("exp") < now - 30) {
            throw new SecurityException("expired");
        }
        if (claims.has("iat") && claims.getLong("iat") > now + 60) {
            throw new SecurityException("issued in the future");
        }
        Object aud = claims.opt("aud");
        Set<String> slugs = slugs();
        boolean audOk = aud instanceof String a ? slugs.contains(a) : aud instanceof List<?> l && l.stream().anyMatch(slugs::contains);
        if (!audOk) {
            throw new SecurityException("audience " + aud + " is no configured plugin");
        }
        String sum = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(body));
        if (!sum.equals(claims.optString("body_sha256"))) {
            throw new SecurityException("body_sha256 mismatch");
        }
        String jti = claims.optString("jti");
        seen.values().removeIf(exp -> Instant.now().isAfter(exp));
        if (jti.isEmpty() || seen.putIfAbsent(jti, Instant.now().plus(Duration.ofMinutes(10))) != null) {
            throw new SecurityException("repeated or missing jti");
        }
    }

    private Map<String, PublicKey> keys(String iss, boolean refresh) throws Exception {
        Map<String, PublicKey> k = jwks.get(iss);
        if (k != null && !refresh) {
            return k;
        }
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(iss + "/.well-known/jwks.json")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, PublicKey> out = new ConcurrentHashMap<>();
        for (Object o : JSONObject.fromObject(resp.body()).getJSONArray("keys")) {
            JSONObject j = (JSONObject) o;
            if (!"OKP".equals(j.optString("kty")) || !"Ed25519".equals(j.optString("crv"))) {
                continue;
            }
            byte[] x = Base64.getUrlDecoder().decode(j.getString("x"));
            if (x.length != 32) {
                continue;
            }
            byte[] spki = new byte[ED25519_SPKI.length + 32];
            System.arraycopy(ED25519_SPKI, 0, spki, 0, ED25519_SPKI.length);
            System.arraycopy(x, 0, spki, ED25519_SPKI.length, 32);
            out.put(j.optString("kid"), KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki)));
        }
        jwks.put(iss, out);
        return out;
    }

    /** Entire signs its deliveries, so the webhook needs no crumb. */
    @Extension
    public static class CrumbExempt extends CrumbExclusion {
        @Override
        public boolean process(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) throws IOException, ServletException {
            String path = req.getPathInfo();
            if ("/entire/events".equals(path) || "/entire/events/".equals(path)) {
                chain.doFilter(req, resp);
                return true;
            }
            return false;
        }
    }
}

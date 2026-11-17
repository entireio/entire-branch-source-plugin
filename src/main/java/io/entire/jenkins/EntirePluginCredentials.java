package io.entire.jenkins;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.util.Secret;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * The identity of a private Entire plugin: its slug, the installation Jenkins builds for, and the
 * Ed25519 private key whose public half is registered with Entire. Jenkins mints short-lived
 * repository tokens from it; the key never leaves the controller.
 */
public class EntirePluginCredentials extends BaseStandardCredentials {

    private final String coreUrl;
    private final String pluginSlug;
    private final String installationId;
    private final Secret privateKey;

    @DataBoundConstructor
    public EntirePluginCredentials(CredentialsScope scope, String id, String description, String coreUrl, String pluginSlug, String installationId, Secret privateKey) {
        super(scope, id, description);
        this.coreUrl = coreUrl;
        this.pluginSlug = pluginSlug;
        this.installationId = installationId;
        this.privateKey = privateKey;
    }

    public String getCoreUrl() {
        return coreUrl;
    }

    public String getPluginSlug() {
        return pluginSlug;
    }

    public String getInstallationId() {
        return installationId;
    }

    public Secret getPrivateKey() {
        return privateKey;
    }

    @Extension
    @Symbol("entirePlugin")
    public static class DescriptorImpl extends BaseStandardCredentialsDescriptor {
        @Override
        public String getDisplayName() {
            return "Entire plugin key";
        }
    }
}

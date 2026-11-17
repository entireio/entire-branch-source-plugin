package io.entire.jenkins;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.scm.SCM;
import hudson.security.ACL;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitBranchSCMHead;
import jenkins.plugins.git.GitSCMBuilder;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMHeadObserver;
import jenkins.scm.api.SCMProbe;
import jenkins.scm.api.SCMProbeStat;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceCriteria;
import jenkins.scm.api.SCMSourceDescriptor;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * One Entire repository as a multibranch source. Branches come from the Entire API, the
 * Jenkinsfile is read through {@code /raw}, and checkouts use the {@code entire://} URL so git
 * runs {@code git-remote-entire} with the build's {@code ENTIRE_TOKEN}.
 */
public class EntireSCMSource extends SCMSource {

    private final String credentialsId;
    private final String repository;
    private String gitTool;

    @DataBoundConstructor
    public EntireSCMSource(String credentialsId, String repository) {
        this.credentialsId = credentialsId;
        this.repository = repository;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    /** Repository full name, {@code project/repo}, or its id. */
    public String getRepository() {
        return repository;
    }

    /**
     * Name of the git tool to check out with. Agents need {@code git-remote-entire} on git's
     * {@code PATH}; a git tool whose installer provides it is the usual way to get it there.
     */
    @CheckForNull
    public String getGitTool() {
        return gitTool;
    }

    @DataBoundSetter
    public void setGitTool(@CheckForNull String gitTool) {
        this.gitTool = gitTool == null || gitTool.isBlank() ? null : gitTool.trim();
    }

    EntirePluginCredentials credentials() throws IOException {
        Item owner = getOwner();
        List<EntirePluginCredentials> all = owner == null
                ? CredentialsProvider.lookupCredentialsInItemGroup(EntirePluginCredentials.class, Jenkins.get(), ACL.SYSTEM2, Collections.emptyList())
                : CredentialsProvider.lookupCredentialsInItem(EntirePluginCredentials.class, owner, ACL.SYSTEM2, Collections.emptyList());
        for (EntirePluginCredentials c : all) {
            if (c.getId().equals(credentialsId)) {
                return c;
            }
        }
        throw new IOException("no Entire plugin credentials with id " + credentialsId);
    }

    EntireClient client() throws IOException {
        return EntireClient.of(credentials());
    }

    EntireClient.Repo repo() throws IOException {
        return client().repository(repository);
    }

    @Override
    protected void retrieve(@CheckForNull SCMSourceCriteria criteria, @NonNull SCMHeadObserver observer, @CheckForNull SCMHeadEvent<?> event, @NonNull TaskListener listener) throws IOException, InterruptedException {
        EntireClient client = client();
        EntireClient.Repo r = client.repository(repository);
        java.util.Map<String, String> branches = client.branchHeads(r);
        listener.getLogger().printf("Entire: %d branches in %s%n", branches.size(), r.fullName());
        for (java.util.Map.Entry<String, String> e : branches.entrySet()) {
            String branch = e.getKey();
            GitBranchSCMHead head = new GitBranchSCMHead(branch);
            if (observer.getIncludes() != null && !observer.getIncludes().contains(head)) {
                continue;
            }
            String sha = e.getValue();
            AbstractGitSCMSource.SCMRevisionImpl rev = new AbstractGitSCMSource.SCMRevisionImpl(head, sha);
            if (criteria != null) {
                try (SCMProbe probe = createProbe(head, rev)) {
                    if (!criteria.isHead(probe, listener)) {
                        listener.getLogger().printf("Entire: %s does not meet the criteria%n", branch);
                        continue;
                    }
                }
            }
            observer.observe(head, rev);
            if (!observer.isObserving()) {
                return;
            }
        }
    }

    @Override
    protected SCMRevision retrieve(@NonNull SCMHead head, @NonNull TaskListener listener) throws IOException, InterruptedException {
        EntireClient client = client();
        String sha = client.branchSha(client.repository(repository), head.getName());
        return sha == null ? null : new AbstractGitSCMSource.SCMRevisionImpl(head, sha);
    }

    @NonNull
    @Override
    protected SCMProbe createProbe(@NonNull SCMHead head, @CheckForNull SCMRevision revision) throws IOException {
        final EntireClient client = client();
        final EntireClient.Repo r = client.repository(repository);
        final String ref = revision instanceof AbstractGitSCMSource.SCMRevisionImpl rev ? rev.getHash() : head.getName();
        return new SCMProbe() {
            @NonNull
            @Override
            public SCMProbeStat stat(@NonNull String path) throws IOException {
                return SCMProbeStat.fromType(client.raw(r, ref, path) == null ? SCMFile.Type.NONEXISTENT : SCMFile.Type.REGULAR_FILE);
            }

            @Override
            public String name() {
                return head.getName();
            }

            @Override
            public long lastModified() {
                return 0;
            }

            @Override
            public void close() {}
        };
    }

    @NonNull
    @Override
    public SCM build(@NonNull SCMHead head, @CheckForNull SCMRevision revision) {
        String remote;
        try {
            remote = repo().entireCloneUrl();
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve Entire repository " + repository, e);
        }
        return new GitSCMBuilder<>(head, revision, remote, null).withGitTool(gitTool).build();
    }

    @Extension
    @Symbol("entire")
    public static class DescriptorImpl extends SCMSourceDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Entire";
        }
    }
}

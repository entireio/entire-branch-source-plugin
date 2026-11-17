package io.entire.jenkins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.scm.SCM;
import java.util.Map;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitBranchSCMHead;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;

/** A push to a branch of one Entire repository. */
public class EntireSCMHeadEvent extends SCMHeadEvent<String> {

    private final String repoPath;
    private final String branch;
    private final String sha;

    public EntireSCMHeadEvent(Type type, String repoId, String repoPath, String branch, String sha, String origin) {
        super(type, repoId, origin);
        this.repoPath = repoPath;
        this.branch = branch;
        this.sha = sha;
    }

    private boolean matches(EntireSCMSource s) {
        String want = s.getRepository();
        return want.equals(getPayload()) || want.equals(repoPath) || repoPath.endsWith("/" + want);
    }

    @Override
    public boolean isMatch(@NonNull SCMNavigator navigator) {
        return false;
    }

    @NonNull
    @Override
    public String getSourceName() {
        return repoPath;
    }

    @NonNull
    @Override
    public Map<SCMHead, SCMRevision> heads(@NonNull SCMSource source) {
        if (!(source instanceof EntireSCMSource s) || !matches(s)) {
            return Map.of();
        }
        GitBranchSCMHead head = new GitBranchSCMHead(branch);
        return getType() == Type.REMOVED ? java.util.Collections.singletonMap(head, (SCMRevision) null) : Map.of(head, new AbstractGitSCMSource.SCMRevisionImpl(head, sha));
    }

    @Override
    public boolean isMatch(@NonNull SCM scm) {
        return false;
    }
}

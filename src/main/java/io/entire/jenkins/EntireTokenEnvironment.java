package io.entire.jenkins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.EnvironmentContributor;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.io.IOException;
import jenkins.scm.api.SCMSource;

/**
 * Gives builds of Entire sources a repository-scoped {@code contents:read} token as
 * {@code ENTIRE_TOKEN}. The git plugin passes the build environment to CLI git, and
 * {@code git-remote-entire} reads the token from it for {@code entire://} remotes. The
 * {@code checks:write} token stays on the controller.
 */
@Extension
public class EntireTokenEnvironment extends EnvironmentContributor {

    @Override
    public void buildEnvironmentFor(@NonNull Run r, @NonNull EnvVars envs, @NonNull TaskListener listener) throws IOException, InterruptedException {
        SCMSource src = SCMSource.SourceByItem.findSource(r.getParent());
        if (!(src instanceof EntireSCMSource s)) {
            return;
        }
        EntireClient client = s.client();
        EntireClient.Repo repo = client.repository(s.getRepository());
        envs.put("ENTIRE_TOKEN", client.token(repo.id(), "contents", "read"));
        envs.put("ENTIRE_REPOSITORY", repo.fullName());
    }
}

package io.entire.jenkins;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.checks.api.ChecksConclusion;
import io.jenkins.plugins.checks.api.ChecksDetails;
import io.jenkins.plugins.checks.api.ChecksPublisher;
import io.jenkins.plugins.checks.api.ChecksPublisherFactory;
import io.jenkins.plugins.checks.api.ChecksStatus;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import net.sf.json.JSONObject;

/**
 * Publishes Jenkins Checks API results to Entire. Every build is one check suite {@code jenkins};
 * each named check (the build status, JUnit, warnings and so on) is one run in it. The build
 * status check also sets the suite's status.
 */
@Extension
public class EntireChecksPublisherFactory extends ChecksPublisherFactory {

    private static final Logger LOG = Logger.getLogger(EntireChecksPublisherFactory.class.getName());

    @Override
    protected Optional<ChecksPublisher> createPublisher(@NonNull Run<?, ?> run, @NonNull TaskListener listener) {
        SCMSource src = SCMSource.SourceByItem.findSource(run.getParent());
        if (!(src instanceof EntireSCMSource s)) {
            return Optional.empty();
        }
        SCMRevision rev = SCMRevisionAction.getRevision(s, run);
        if (!(rev instanceof AbstractGitSCMSource.SCMRevisionImpl git)) {
            return Optional.empty();
        }
        return Optional.of(new Publisher(s, run, git.getHead(), git.getHash(), listener));
    }

    @Override
    protected Optional<ChecksPublisher> createPublisher(@NonNull Job<?, ?> job, @NonNull TaskListener listener) {
        return Optional.empty();
    }

    static String[] state(ChecksStatus status, ChecksConclusion conclusion) {
        if (status != ChecksStatus.COMPLETED) {
            return new String[] {status == ChecksStatus.IN_PROGRESS ? "in_progress" : "queued", null};
        }
        String c = switch (conclusion) {
            case SUCCESS -> "success";
            case SKIPPED -> "skipped";
            case CANCELED -> "cancelled";
            case NEUTRAL -> "neutral";
            case ACTION_REQUIRED -> "action_required";
            case TIME_OUT -> "timed_out";
            default -> "failure";
        };
        return new String[] {"completed", c};
    }

    static final class Publisher extends ChecksPublisher {
        private final EntireSCMSource source;
        private final Run<?, ?> run;
        private final SCMHead head;
        private final String sha;
        private final TaskListener listener;

        Publisher(EntireSCMSource source, Run<?, ?> run, SCMHead head, String sha, TaskListener listener) {
            this.source = source;
            this.run = run;
            this.head = head;
            this.sha = sha;
            this.listener = listener;
        }

        @Override
        public void publish(ChecksDetails details) {
            String now = Instant.now().toString();
            String root = jenkins.model.Jenkins.get().getRootUrl();
            String url = details.getDetailsURL().orElse(root == null ? null : root + run.getUrl());
            if (url != null && !url.startsWith("https://")) {
                url = null; // Entire accepts https details URLs only
            }
            String name = details.getName().orElse(EntireStatusChecksProperties.NAME);
            String[] st = state(details.getStatus(), details.getConclusion());

            JSONObject suite = new JSONObject()
                    .element("key", "jenkins")
                    .element("name", "Jenkins")
                    .element("external_id", run.getExternalizableId())
                    .element("external_updated_at", now);
            if (url != null) {
                suite.element("details_url", url);
            }
            if (EntireStatusChecksProperties.NAME.equals(name)) {
                suite.element("status", st[0]);
                if (st[1] != null) {
                    suite.element("conclusion", st[1]);
                }
            } else {
                suite.element("status", "in_progress");
            }
            JSONObject checkRun = new JSONObject()
                    .element("key", name)
                    .element("name", name)
                    .element("external_id", run.getExternalizableId() + "/" + name)
                    .element("status", st[0])
                    .element("external_updated_at", now);
            if (st[1] != null) {
                checkRun.element("conclusion", st[1]);
            }
            if (url != null) {
                checkRun.element("details_url", url);
            }
            try {
                EntireClient client = source.client();
                client.checkRun(client.repository(source.getRepository()), sha, head.getName(), suite, checkRun);
                listener.getLogger().printf("Entire: check %s %s%s on %s@%s%n", name, st[0], st[1] == null ? "" : " " + st[1], head.getName(), sha.substring(0, 12));
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Entire: could not publish check " + name, e);
                listener.getLogger().println("Entire: could not publish check " + name + ": " + e.getMessage());
            }
        }
    }
}

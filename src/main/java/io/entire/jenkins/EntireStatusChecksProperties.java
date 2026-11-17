package io.entire.jenkins;

import hudson.Extension;
import hudson.model.Job;
import io.jenkins.plugins.checks.status.AbstractStatusChecksProperties;
import jenkins.scm.api.SCMSource;

/** Turns on the Checks API build status check for jobs built from an Entire source. */
@Extension
public class EntireStatusChecksProperties extends AbstractStatusChecksProperties {

    static final String NAME = "build";

    @Override
    public boolean isApplicable(Job<?, ?> job) {
        return SCMSource.SourceByItem.findSource(job) instanceof EntireSCMSource;
    }

    @Override
    public String getName(Job<?, ?> job) {
        return NAME;
    }

    @Override
    public boolean isSkipped(Job<?, ?> job) {
        return false;
    }

    @Override
    public boolean isUnstableBuildNeutral(Job<?, ?> job) {
        return false;
    }

    @Override
    public boolean isSuppressLogs(Job<?, ?> job) {
        return true;
    }
}

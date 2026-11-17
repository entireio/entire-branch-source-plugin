package io.entire.jenkins;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Item;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.scm.api.SCMFile;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;

/**
 * Lightweight checkout: reads single files through {@code /raw} so the controller can load a
 * Jenkinsfile without cloning. Entire has no directory listing, so directories have no children.
 */
public class EntireSCMFileSystem extends SCMFileSystem {

    private final EntireClient client;
    private final EntireClient.Repo repo;
    private final String ref;

    EntireSCMFileSystem(EntireClient client, EntireClient.Repo repo, String ref, SCMRevision rev) {
        super(rev);
        this.client = client;
        this.repo = repo;
        this.ref = ref;
    }

    @Override
    public long lastModified() {
        return 0;
    }

    @NonNull
    @Override
    public SCMFile getRoot() {
        return new File(null, "");
    }

    private final class File extends SCMFile {
        private byte[] content;
        private boolean fetched;

        File(File parent, String name) {
            super(parent, name);
        }

        private byte[] fetch() throws IOException {
            if (!fetched) {
                content = client.raw(repo, ref, getPath());
                fetched = true;
            }
            return content;
        }

        @NonNull
        @Override
        protected SCMFile newChild(@NonNull String name, boolean assumeIsDirectory) {
            return new File(this, name);
        }

        @NonNull
        @Override
        public Iterable<SCMFile> children() {
            return List.of();
        }

        @Override
        public long lastModified() {
            return 0;
        }

        @NonNull
        @Override
        protected Type type() throws IOException {
            if (isRoot()) {
                return Type.DIRECTORY;
            }
            return fetch() == null ? Type.NONEXISTENT : Type.REGULAR_FILE;
        }

        @NonNull
        @Override
        public InputStream content() throws IOException {
            byte[] b = fetch();
            if (b == null) {
                throw new FileNotFoundException(getPath());
            }
            return new ByteArrayInputStream(b);
        }
    }

    @Extension
    public static class BuilderImpl extends SCMFileSystem.Builder {
        @Override
        public boolean supports(SCM source) {
            return false;
        }

        @Override
        public boolean supports(SCMSource source) {
            return source instanceof EntireSCMSource;
        }

        @Override
        protected boolean supportsDescriptor(@SuppressWarnings("rawtypes") SCMDescriptor descriptor) {
            return false;
        }

        @Override
        protected boolean supportsDescriptor(SCMSourceDescriptor descriptor) {
            return descriptor instanceof EntireSCMSource.DescriptorImpl;
        }

        @Override
        public SCMFileSystem build(@NonNull Item owner, @NonNull SCM scm, @CheckForNull SCMRevision rev) {
            return null;
        }

        @Override
        public SCMFileSystem build(@NonNull SCMSource source, @NonNull SCMHead head, @CheckForNull SCMRevision rev) throws IOException {
            EntireSCMSource s = (EntireSCMSource) source;
            EntireClient client = s.client();
            String ref = rev instanceof AbstractGitSCMSource.SCMRevisionImpl r ? r.getHash() : head.getName();
            return new EntireSCMFileSystem(client, client.repository(s.getRepository()), ref, rev);
        }
    }
}

FROM jenkins/jenkins:2.568.1-jdk21
COPY git-remote-entire /usr/local/bin/git-remote-entire
RUN jenkins-plugin-cli --plugins workflow-aggregator git workflow-multibranch checks-api junit
ENV JAVA_OPTS="-Djenkins.install.runSetupWizard=false"

#!/usr/bin/env bash
# mvn_retry — run a Maven command, retrying a transient failure.
#
# WHY THIS EXISTS. On 2026-09-02 the botmaker-studio v1.0.32 `package` job died on the FIRST plugin
# descriptor it asked for:
#
#   Could not transfer artifact org.apache.maven.plugins:maven-resources-plugin:pom:3.4.0
#   from/to central (https://repo.maven.apache.org/maven2): status code: 429, reason phrase:
#   Too Many Requests (429)
#
# Maven Central rate-limits GitHub-hosted runners, whose egress IPs are shared by every job on the fleet.
# Nothing about this build provoked it and nothing about this build can avoid it; the only useful response
# is to wait and ask again.
#
# WHY NOT A MAVEN FLAG. `-Dmaven.wagon.http.retryHandler.count` retries an IO failure — a reset connection,
# a truncated stream. A 429 is a SUCCESSFUL HTTP response carrying a refusal, so the resolver reads it as a
# definitive answer, records the artifact as absent for the rest of the session, and every later module in
# the same command fails against the cached verdict rather than the network. That is why the log above says
# `(absent)`. The retry therefore has to be around the whole `mvn` invocation, which is what this is.
#
# USAGE, from a workflow step:
#
#   source .github/mvn-retry.sh
#   mvn_retry mvn -B -f .deps/botmaker-shared/pom.xml install -DskipTests
#
# It is a FILE rather than a copy pasted into each step for the reason tools/changelog-section.sh is a
# file: this repository has more than one reader of the rule (the `build` job and the `package` job), and
# two copies of a retry policy drift into one job being patient and the other not.

mvn_retry() {
  local attempt
  for attempt in 1 2 3; do
    "$@" && return 0
    echo "::warning::Maven attempt ${attempt}/3 failed (transfer error or Central rate limit) — retrying in 20s"
    sleep 20
  done
  echo "::error::Maven failed three times: $*" >&2
  return 1
}

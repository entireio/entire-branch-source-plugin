# Entire Branch Source

A Jenkins plugin that builds Entire repositories. Jenkins discovers branches, receives Entire's webhooks, and reports results to Entire as check runs.

Status: prototype. It ran end to end against Entire production on 2026-11-17. It is not published to the Jenkins update center.

## How it works

The integration is a private Entire plugin. Its Ed25519 key lives in Jenkins as a credential and nowhere else.

| Jenkins needs | This plugin does |
| --- | --- |
| Branches | Lists the repository's branches and head commits through the installation token |
| Jenkinsfile | Reads it through `/raw`, so the controller never clones |
| Checkout | Uses the `entire://` clone URL. The git plugin runs `git-remote-entire`, which reads a repository-scoped `contents:read` token from `ENTIRE_TOKEN` |
| Webhooks | `POST /entire/events` verifies Entire's delivery JWT (EdDSA, issuer JWKS, audience, `body_sha256`, `jti`) and fires a branch event |
| Status | A Checks API publisher. Each build is one check suite `jenkins`; the build status and every other Checks API result (JUnit, warnings) is one run in it. The `checks:write` token stays on the controller |

## Set up

1. Register a private plugin in your Entire project's settings. Use permissions `contents:read` and `checks:write`, an Ed25519 public key, and webhook URL `https://<jenkins>/entire/events`. Install it on the project and pick the repositories.
2. Install this plugin, plus `git`, `workflow-multibranch` and `checks-api`.
3. Give agents `git-remote-entire`. Either put it on every agent's `PATH`, or add a git tool with an installer that downloads a pinned release from `https://install.entire.com/v<version>/`, checks it against `checksums.txt`, and writes a `git` wrapper that puts it on `PATH`. Set that tool as the source's **Git tool**. `withEnv` in a Jenkinsfile does not reach the git plugin's checkout.
4. Add an **Entire plugin key** credential: auth URL, plugin slug, installation ID, and the private key as PKCS#8 PEM.
5. Create a multibranch pipeline with an **Entire** branch source: the credential and the repository as `project/repo`.

Only `/entire/events` needs to be reachable from Entire's egress IPs, which are listed at `GET https://entire.io/api/v1/meta`.

## Limits

- One repository per job. There is no organization folder (`SCMNavigator`) yet.
- Branches only. Trails are not mapped to change requests yet.
- `ENTIRE_TOKEN` is in the build environment, so build steps can read the repository. That is the clone credential every CI server hands its builds.
- Check `details_url` must be https, so set the Jenkins root URL to an https address.
- Entire's public API returns branch names without SHAs, so a scan costs one `/commits` call per branch.

## Build

`mvn package` produces `target/entire-branch-source.hpi`.

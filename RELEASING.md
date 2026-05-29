# Releasing karcarthy

Releases are automated by [`.github/workflows/release.yml`](.github/workflows/release.yml),
which runs on any `v*` tag and:

1. builds the jar (`clojure -T:build jar`),
2. creates a **GitHub Release** with the jar attached (always — uses the built-in
   `GITHUB_TOKEN`, no extra setup), and
3. **publishes to Clojars** *if* the `CLOJARS_USERNAME` / `CLOJARS_PASSWORD`
   repository secrets are set (otherwise that step is skipped).

## Cut a release

1. Bump `version` in [`build.clj`](build.clj), the `:artifact` path in the
   `:deploy` alias in [`deps.edn`](deps.edn), and add a dated section to
   [`CHANGELOG.md`](CHANGELOG.md). Commit to `main`.
2. Trigger the release workflow, any of:
   - **Tag push:** `git tag v0.2.0 && git push origin v0.2.0`
   - **GitHub → Releases → Draft a new release** (creates the tag).
   - **`release/**` branch:** push e.g. `release/v0.2.0`; the workflow creates the
     tag + Release **server-side** via the API (useful when tag pushes are
     unavailable).
   - **Actions → release → Run workflow** (`workflow_dispatch`) with a version.

## One-time setup for registry publishing

**Clojars** (idiomatic for Clojure; feeds [cljdoc](https://cljdoc.org)):
create a deploy token at <https://clojars.org/tokens>, then add two repository
secrets (Settings → Secrets and variables → Actions):

- `CLOJARS_USERNAME` — your Clojars username
- `CLOJARS_PASSWORD` — the deploy **token**

After that, every `v*` tag publishes `io.github.soohoonc/karcarthy` to Clojars,
consumable as:

```clojure
io.github.soohoonc/karcarthy {:mvn/version "0.2.0"}
```

**Maven Central** (optional, for the widest JVM reach): register the
`io.github.soohoonc` namespace on the [Sonatype Central Portal](https://central.sonatype.com/),
add GPG signing and Central credentials, and extend the deploy step. Clojars is
sufficient for `deps.edn`/Leiningen consumers.

## Without any registry

karcarthy is always usable directly from git — no publish step required:

```clojure
io.github.soohoonc/karcarthy {:git/url "https://github.com/soohoonc/karcarthy"
                              :git/sha "<commit sha>"}
```

## Summary

<!-- What does this change, and why? -->

## Checklist

- [ ] `clojure -M:test` passes
- [ ] `clojure -T:build jar` passes
- [ ] `cd docs && npm ci && npm run lint && npm run types:check && npm run build` passes when docs change
- [ ] `cd docs && npm audit --audit-level=low` reports no advisories
- [ ] New behavior has tests (new test namespaces registered in `test/karcarthy/test_runner.clj`)
- [ ] Dependencies resolve from Maven Central only (no Clojars)
- [ ] Docs / CHANGELOG updated if the change is user-facing

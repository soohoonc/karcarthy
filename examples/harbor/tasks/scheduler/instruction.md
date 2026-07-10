A scheduler deployment is delivering some jobs twice and retrying failed jobs
immediately in a tight loop. Investigate the repository under `/app`, identify
the persistence bugs, and fix them without changing the public `Store` method
signatures. Preserve priority ordering and exhausted-attempt behavior. Run the
available tests and verify the final implementation.

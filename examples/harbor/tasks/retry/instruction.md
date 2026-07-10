Fix `/app/main.py`. `retry_delays(attempts, base=1, cap=30)` must return an
exponential backoff schedule starting at `base`, doubling after each attempt,
and never exceeding `cap`. Return an empty list when `attempts` is zero or
negative. Preserve the public function signature.

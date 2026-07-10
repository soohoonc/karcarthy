# Architect demo

This is the shortest live demonstration of karcarthy's central idea:

> The agent architecture is the program.

The parent Agent must author and call two task-specific specialists through the
automatic `agent` Tool. It submits both calls together so karcarthy can run them
concurrently. The terminal trace prints:

1. the parent Run;
2. both complete Clojure Agent forms submitted by the model;
3. the kernel's read, expand, check, and evaluate phases;
4. both generated child Runs and their return to the parent; and
5. the parent's final answer.

Run it with:

```bash
clojure -M:examples architect \
  "Review a migration from synchronous writes to a queue."
```

## Record it

Use a clean terminal at roughly 100 columns by 30 rows with a large monospace
font. Start the recording after credentials are already in the environment; do
not type or display an API key.

Record one real run, then remove idle model latency while preserving the event
order. The short cut should take 15–25 seconds:

```text
command
  -> two model-authored (agent ...) forms
  -> read / expand / check / evaluate
  -> concurrent generated Agent Runs
  -> both return to parent
  -> final answer
```

Export two files from the same recording:

- a silent looping GIF for the repository README;
- a 1080p MP4 with brief captions for social posts and the documentation site.

Keep the Clojure source legible. The GIF should show the mechanism, not every
word of the final answer. The video can pause on the generated form and explain
that it is executable source rather than a workflow serialization.

Given an edited `agent-architecture.mp4`, `ffmpeg` can produce a compact GIF:

```bash
ffmpeg -i agent-architecture.mp4 \
  -vf "fps=12,scale=1200:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer" \
  -loop 0 agent-architecture.gif
```

After recording, place the final assets in a stable repository location and
embed the GIF directly below the motto in the root README. Link the GIF to the
full MP4 or documentation page.

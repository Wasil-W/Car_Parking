# Recovered conversation history

Six condensed digests of the sessions that built this app, plus the handoff
prompt written to restart work after the loss.

**Why these are in the repo rather than left on a disk somewhere.** The chat
history was lost when the desktop app was reinstalled. Nothing about the code
was lost — but the *reasoning* nearly was, and these digests plus
[`../TIMELINE.md`](../TIMELINE.md) are what made it recoverable. They sat
untracked in the working tree for weeks, which is the same exposure again in a
smaller form.

Each digest has a most-edited-files ranking and a dated timeline of prompts and
assistant reasoning, oldest to newest:

```
digest-1c491631 → digest-40a7abc7 → digest-83384767
              → digest-0465ea0f → digest-875258db → digest-3fd0b40f
```

`digest-3fd0b40f` is the most recent and ends mid-task.

The full transcripts are far larger and are **not** in the repo; they live under
`~/.claude/projects/C--Users-wasil-Dev-Car-Parking/*.jsonl`, matched by the
first eight characters of the filename. Grep them, never read one whole — they
run from 5 to 71 MB.

**These are a record, not a specification.** Where a digest and the code
disagree, the code wins, and where a digest and [`TIMELINE.md`](../TIMELINE.md)
disagree, the timeline wins. They contain decisions that were later reversed —
that is most of their value.

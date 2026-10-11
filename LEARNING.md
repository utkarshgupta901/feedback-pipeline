# Learning guide — step by step

How to use each step:

1. Read the **Concepts** section.
2. Implement the TODOs. Make the step's tests green (`mvn -q test -Dtest='StepN*'`).
3. Paste your code to Claude for review (it has a reference solution to compare against).
4. Answer the **Interviewer follow-ups** out loud, in 1–2 minutes each, without looking.

Don't skip step 4. In a senior/staff round the code gets you to "hire"; the follow-ups decide the level.

---

## Step 0 — The first 5 minutes of the interview

Ask before writing code. Each answer changes the design, and asking them is itself scored.

| Question | What this project assumes |
|---|---|
| What counts as a duplicate — same `(source,id)` or same text? | Same tenant + same normalized text (case-insensitive). |
| Which duplicate wins? | Earliest timestamp; ties → first seen. Deterministic, so reruns pick the same one. |
| Batch or stream? | Batch (list in, report out). Discuss streaming as a follow-up. |
| Is `classify` pure / idempotent? Can I call it twice for the same text? | Yes — safe to repeat. This is what makes crash-recovery possible. |
| Which errors are retryable? | Everything except `NonRetryableException` (think HTTP 400 vs 503/timeout). |
| What does "fair" mean — equal turns, or weighted by plan? | Equal turns (round-robin). Weighted is a follow-up. |
| Where does output go? | An append-only journal file (stand-in for a DB table). |
| Is a dead-lettered record "processed"? | Yes, terminal. Not retried on rerun; a human/replay tool handles it. |

Then state the design in 30 seconds using the diagram in README.md, and start coding the
pieces in the order of this guide. Interviewers like seeing small, tested building blocks.

---

## Step 1 — Clean + de-duplicate per tenant

**Files:** `TextNormalizer.clean`, `TextNormalizer.key`, `Ingest.prepare` · **Tests:** `Step1IngestTest`

### Concepts

- **Unicode normalization.** `"Ｈｅｌｌｏ"` (full-width) and `"Hello"` look the same to a human but are
  different bytes. `Normalizer.normalize(s, Form.NFKC)` folds compatibility characters
  (full-width letters, ligatures, non-breaking space → space). NFC only fixes accent composition;
  NFKC is the right choice for *matching*, but it's lossy, so don't use it for display text.
- **Invisible characters.** `\p{Cf}` = "format" chars like zero-width space `​` and BOM `﻿`.
  Copy-paste from Slack/Word is full of them. Remove them. `\p{Cc}` = control chars (`\n`, `\t`, `\0`):
  turn them into spaces, then collapse whitespace.
- **Precompile regexes** (`static final Pattern`). `String.replaceAll` recompiles every call.
- **`toLowerCase(Locale.ROOT)`** — with the default locale on a Turkish machine, `"I".toLowerCase()`
  is `"ı"` (dotless i), so the same text gets different keys on different servers. Always pass `Locale.ROOT`
  for machine-facing strings.
- **Hash as key.** Store `tenant + ":" + sha256(text)` instead of the full text: fixed size (64 hex chars),
  safe to log, cheap to compare. The tenant prefix is what makes dedupe *per tenant*.
- **Deterministic choice of survivor.** "Keep first seen" depends on arrival order, which changes
  between runs. "Keep earliest timestamp, tie → first seen" doesn't. That matters for Step 4.
- `Map.merge(key, value, remappingFn)` is the idiomatic "insert or combine" in one line.

### Interviewer follow-ups

1. *"Near-duplicates — 'app crashes' vs 'app crashed!!'?"* → Exact hash won't catch it. SimHash/MinHash
   + LSH for fuzzy dedupe, or embeddings + cosine similarity. Trade-off: false positives drop real feedback.
2. *"100 M records, can't keep all keys in memory?"* → Bloom filter in front (false positives possible —
   acceptable?), or a persistent key store (RocksDB / Redis SET with TTL / DB unique index). Partition by tenant.
3. *"Same `(source,id)` arrives twice with edited text?"* → That's an *update*, not a duplicate. You'd key
   on `(tenant, source, id)` and version by timestamp. Say which you chose and why.

---

## Step 2 — Retry, backoff + jitter, dead-letter, at most N in flight

**Files:** `RetryPolicy`, `RetryingClassifier`, `BoundedClassifier` · **Tests:** `Step2RetryTest`

### Concepts

- **Exponential backoff:** wait `base × 2^(attempt-1)`, capped at `max`. Gives the failing service room to recover.
- **Jitter:** if 1,000 clients fail at the same moment and all wait exactly 400 ms, they all retry at the same
  moment again (thundering herd). **Full jitter** = `random(0, cap)` spreads them out. Read:
  [AWS — Exponential Backoff and Jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/).
  Know the three flavours by name: full, equal (`cap/2 + random(0, cap/2)`), decorrelated.
- **Overflow:** `100L << 70` is garbage. Clamp the exponent (e.g. `min(attempt-1, 30)`).
- **Retryable vs not.** Timeouts/503/429 → retry. 400/validation → retrying just burns budget; dead-letter
  immediately.
- **Dead-letter record keeps context:** key, attempt count, last error. Someone has to debug it later.
- **Injectable `Sleeper` and `RandomGenerator`.** Code that calls `Thread.sleep` and `new Random()` directly
  can't be tested fast or deterministically. Mention this — it's an LLD signal.
- **`InterruptedException` is not a failure.** It means "we're shutting down". Don't retry, don't
  dead-letter: restore the flag (`Thread.currentThread().interrupt()`) and rethrow. Catch order matters
  because `catch (Exception e)` would swallow it.
- **`Semaphore(N)`** limits concurrency. Pattern:
  ```java
  permits.acquire();          // OUTSIDE the try — if acquire throws, we must not release
  try { return delegate.classify(text); }
  finally { permits.release(); }
  ```
  `new Semaphore(n, true)` = fair (FIFO waiters); slightly slower, but no waiter starves.
- **Decorator pattern.** `BoundedClassifier implements Classifier` and wraps another `Classifier`.
  Concurrency limiting is separate from retry logic, and each is testable alone.
- **Why the permit isn't held during backoff:** sleeping happens in `RetryingClassifier`, *outside* the
  bounded call. If you put the semaphore around the whole retry loop, a record sleeping for 2 s
  would hold one of the N slots doing nothing.

### Interviewer follow-ups

1. *"The classifier is down for 10 minutes. What happens?"* → Every record burns K attempts and goes to the DLQ.
   Fix: **circuit breaker** (after X% failures, stop calling for a cool-down, fail fast or pause the queue),
   and a **retry budget** (retries ≤ 10% of calls).
2. *"Server returns 429 with `Retry-After`."* → Honour it over your own backoff.
3. *"`classify` hangs forever."* → Need a per-call **timeout** (HttpClient timeout, or `future.get(t)` + cancel).
   Otherwise a hung call holds a permit forever and N slowly drops to 0.
4. *"How do you pick N?"* → Downstream's limit, or adaptive: AIMD like TCP (add 1 on success, halve on 429).
5. *"Why not `ExecutorService` with N threads instead of a Semaphore?"* → Also valid. The semaphore
   bounds the *call*, not the threads, so workers can sleep in backoff without wasting a slot. Know both.

---

## Step 3 — Fairness across tenants

**File:** `FairQueue` · **Tests:** `Step3FairQueueTest`

### Concepts

- **The problem:** a single FIFO queue with 1 M records from tenant BIG followed by 5 from tenant small →
  small waits hours. Head-of-line blocking.
- **Round-robin:** one queue per tenant + a "ring" of tenants that have work. `take()` pops the tenant at the
  front of the ring, takes one item, and puts the tenant at the back if it still has items.
  Every active tenant gets a turn every R takes (R = number of active tenants). O(1) per op.
- **Drop empty tenants from the map**, otherwise memory grows with every tenant ever seen.
- **`ReentrantLock` + `Condition`** (or `synchronized` + `wait/notifyAll`):
  ```java
  lock.lock();
  try {
      while (ring.isEmpty()) {          // WHILE, not IF: spurious wakeups + another taker may win
          if (closed) return null;
          notEmpty.await();             // releases the lock while waiting
      }
      ...
  } finally { lock.unlock(); }
  ```
  `signal()` on offer (one item → wake one taker); `signalAll()` on close (everyone must wake and exit).
- **Close / poison pill:** workers loop `while ((x = q.take()) != null)`. After `close()`, takers drain
  what's left, then get `null` and exit. This is how the pipeline knows it's done.
- Why not `PriorityBlockingQueue` with a per-tenant counter? Works, but O(log n), and the
  "priority" needs updating as counts change. Round-robin is simpler to reason about in 45 minutes.

### Interviewer follow-ups

1. *"Enterprise tenants pay more — they should get more throughput."* → **Weighted round-robin** or
   **Deficit Round Robin** (each tenant gets a quantum per round proportional to weight; unused credit carries over).
2. *"One tenant's records are all slow (huge texts) — round-robin by count isn't fair by time."* → DRR with
   cost = text length / expected latency, or cap **in-flight per tenant** (e.g. ≤ N/2 slots for one tenant).
3. *"Producer is faster than consumers — memory blows up."* → Bounded queue: `offer` blocks or rejects when
   full (backpressure), per-tenant caps.
4. *"Do this across 20 machines."* → Kafka: partition key ≠ tenant (hot partitions); instead per-tenant
   rate limits/quotas at consumers, or SQS FIFO **message group id = tenant** (SQS interleaves groups for you).

---

## Step 4 — Safe to rerun after a crash

**File:** `Journal` · **Tests:** `Step4JournalTest`

### Concepts

- **"Exactly-once" is really "effectively-once":** at-least-once *execution* + an idempotent *commit*.
  You cannot make an external call and a local write atomic. So pick a **commit point** — here,
  `journal.record(outcome)` — and make everything before it safe to repeat.
- **Walk the crash windows** (interviewers love this table):

  | Crash happens… | On rerun | OK? |
  |---|---|---|
  | before `classify` | record not in journal → processed | ✅ |
  | during / after `classify`, before journal write | classified **again** | ✅ only because `classify` is idempotent — say this out loud |
  | in the middle of the journal write | torn last line → ignored + truncated → processed again | ✅ |
  | after journal write (fsync returned) | skipped | ✅ |

- **Append-only log + fsync.** `channel.write` puts bytes in the OS page cache; a power loss can lose them.
  `channel.force(false)` = fsync data. Only after it returns is the record "processed".
  fsync per record is slow (~ms) → in production you'd **group-commit** (batch several records per fsync).
- **Torn writes.** A crash mid-write leaves `S\tacme:2\tpra` with no `\n`. On recovery: parse only up to the
  last newline, then **truncate** the tail. If you don't truncate, the next append is glued onto the
  garbage and you lose a good record.
- **Escaping.** Error messages contain tabs and newlines. Escape `\` first, then `\t \n \r`, or the line
  format breaks. (`esc`/`unesc` are provided — read them.)
- **Update in-memory state after the durable write**, never before. Otherwise `isDone` says yes for
  something that isn't on disk.
- **Dead letters are terminal too** — otherwise every rerun hammers the classifier with known-bad records.

### Interviewer follow-ups

1. *"Use a real database instead."* → Table `outcomes(tenant, key, status, label, attempts, error)` with
   `UNIQUE(tenant, key)`; `INSERT … ON CONFLICT DO NOTHING`. The unique index *is* the idempotency.
2. *"Multiple machines processing the same batch."* → Claim with a **lease**: `UPDATE … SET status='IN_PROGRESS',
   owner=me, lease_until=now()+5m WHERE status='PENDING' … FOR UPDATE SKIP LOCKED`. Expired lease → someone else
   retries. Then the classify call must be idempotent (or pass an idempotency key to it).
3. *"The classifier charges per call — you can't call it twice."* → Write an `IN_PROGRESS` intent before
   calling and send an **idempotency key** (our record key) to the classifier so *it* dedupes.
4. *"After classify you also publish to Kafka."* → **Transactional outbox**: write outcome + outbox row in one
   DB transaction; a relay publishes the outbox.

---

## Step 5 — Wire it together

**File:** `Pipeline.run` · **Tests:** `Step5PipelineTest`

### Concepts

- **Workers (W) vs concurrency limit (N).** N = max in-flight `classify` calls (semaphore). W = threads
  pulling from the queue. W > N is fine and useful: while some workers sleep in backoff, others keep all
  N slots busy. The test `neverMoreThanNCallsInFlight` checks both "≤ N" and "reaches N".
- **Virtual threads (Java 21).** `Executors.newVirtualThreadPerTaskExecutor()` — cheap threads that unmount
  while blocked (sleep, I/O, lock waits). In try-with-resources, `close()` waits for all tasks.
- **Fairness end-to-end:** the deterministic test runs N=W=1 and checks exact interleaving BIG, s1, s2, BIG, …
- **Crash propagation:** a worker dying with an `Error` surfaces as `ExecutionException` from `future.get()`.
  Stop the rest (`shutdownNow()` interrupts them — which is why Step 2's interrupt handling matters) and throw.
- **Report counts** come from `AtomicInteger`s — many workers increment them.

### Interviewer follow-ups

1. *"Workers sleeping in backoff is wasteful. Improve it."* → Don't sleep in the worker: on failure, schedule
   the record back into the `FairQueue` after the delay (`ScheduledExecutorService` / `DelayQueue`). Retries then
   also go through fairness. Cost: "done" detection needs an in-flight counter instead of `close()`.
2. *"Make it streaming."* → Queue stays open; producers keep offering; graceful shutdown = stop intake,
   `close()`, drain, wait with a deadline.
3. *"What do you monitor?"* → queue depth per tenant, oldest-item age per tenant (fairness SLO), classify
   p50/p99 + error rate, retries/sec, DLQ rate, permits in use.
4. *"Where would you use Spring?"* → `@Retryable`/Resilience4j for retry + circuit breaker, `@Async` executors,
   JPA for the outcome table — but say you'd still own the fairness queue yourself.

---

## 60-minute machine-coding plan

| Minutes | Do |
|---|---|
| 0–5 | Clarifying questions (Step 0), draw the pipeline diagram |
| 5–10 | Models + interfaces (`Classifier`, `Outcome`, `Sleeper`) |
| 10–20 | Clean + dedupe, 2–3 tests |
| 20–32 | Retry + backoff + Semaphore decorator, tests with fake classifier |
| 32–42 | FairQueue |
| 42–50 | Idempotency store (in-memory `Set` first, mention file/DB) |
| 50–55 | Pipeline wiring + one end-to-end test |
| 55–60 | Walk crash windows + what you'd do next |

If time is short, an in-memory `ConcurrentHashMap.newKeySet()` for "done" plus a clear explanation
of the durable version beats a half-written file journal.

## Java APIs you should be able to write from memory

`Semaphore` · `ReentrantLock` / `Condition.await/signal` · `ExecutorService` + `Future.get` ·
`Executors.newVirtualThreadPerTaskExecutor()` · `AtomicInteger` · `ConcurrentHashMap.merge / computeIfAbsent` ·
`ArrayDeque` · `FileChannel.write / force / truncate` · `java.text.Normalizer` · `MessageDigest` + `HexFormat` ·
records, sealed interfaces, pattern-matching `switch`.

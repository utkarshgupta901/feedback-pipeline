# Feedback ingestion pipeline — Java interview practice

Plain Java 21 + JUnit 5. No Spring, no frameworks: this is the shape of a machine-coding round.

The problem:

1. Ingest feedback records `(source, id, tenant, text, timestamp)`. Clean the text, drop duplicates per tenant.
2. Each record goes to a slow, flaky `classify(text)`. At most **N** calls at once. Retry with backoff + jitter. After **K** failed attempts → dead-letter.
3. Fair across tenants — one huge customer can't starve the others.
4. Safe to rerun after a crash; nothing processed twice.

---

## 1. One-time setup (macOS, Apple Silicon)

Check what you already have:

```bash
java -version     # need 21 or newer
mvn -v            # any Maven 3.9.x
```

If either is missing:

```bash
brew install openjdk@21 maven

# let macOS tools (and IntelliJ) find the JDK
sudo ln -sfn "$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk" \
  /Library/Java/JavaVirtualMachines/openjdk-21.jdk

# add to ~/.zshrc, then open a new terminal
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
export PATH="$JAVA_HOME/bin:$PATH"
```

(Already use SDKMAN? `sdk install java 21-tem && sdk install maven` works too.)

Verify the project builds — the first run downloads JUnit:

```bash
cd ~/interview-prep/feedback-pipeline
mvn -q test-compile      # should print nothing = success
```

### IntelliJ

File → Open → pick `pom.xml` → "Open as Project". Project Structure → SDK = 21.
Run any test with the green gutter arrow next to the class or method.

---

## 2. Daily loop

```bash
mvn -q test -Dtest='Step1*'     # one step at a time
mvn -q test -Dtest='Step2*'
mvn test                        # everything
```

Every unimplemented method throws `UnsupportedOperationException("TODO step Nx")`,
so a red test tells you exactly which method to write next.

Read **[LEARNING.md](LEARNING.md)** before each step.

---

## 3. Map of the code

| Step | You implement | Tests |
|---|---|---|
| 1 — clean + dedupe | `ingest/TextNormalizer`, `ingest/Ingest` | `Step1IngestTest` (10) |
| 2 — retry, backoff, DLQ, max N | `retry/RetryPolicy`, `retry/RetryingClassifier`, `retry/BoundedClassifier` | `Step2RetryTest` (11) |
| 3 — tenant fairness | `fairness/FairQueue` | `Step3FairQueueTest` (8) |
| 4 — crash-safe rerun | `store/Journal` | `Step4JournalTest` (6) |
| 5 — wire it together | `pipeline/Pipeline` | `Step5PipelineTest` (6) |

Already written for you (read them, don't change them): `model/*`, `retry/Classifier`,
`retry/Sleeper`, `retry/NonRetryableException`, `pipeline/PipelineConfig`, `pipeline/Report`,
and the test helpers in `src/test/.../support` (`FakeClassifier` is the flaky classify() double).

```
input ──► Ingest.prepare ──► skip if Journal.isDone(key) ──► FairQueue (round-robin by tenant)
          (clean + dedupe)                                        │
                                                                  ▼
                                   W workers: take() ─► RetryingClassifier ─► BoundedClassifier(N) ─► classify()
                                                │
                                                ▼
                                   Journal.record(Success | DeadLetter)   ◄── commit point (fsync)
```

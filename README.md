# Javanatic Harness

> [中文](README.zh-CN.md)

Plugin-based agent harness on the JVM — **Java 25 LTS / JPMS / Maven**. Ports the engineering ideas of [DeepSeek Harness (dsh)](docs/dsh-reference.md) to the Java ecosystem: **ideas carry over, shapes do not**.

> **Status**: the kernel, the full core trunk (session/tools/todo/plan/agent/agent-loop/system-prompt), the capability trio (llm + fs + shell), the real llm/deepseek provider, and JSONL persistence (R1 closed loop) are implemented and tested — **a real model can already drive the full vertical slice** (model tool_use → real tool execution → journaled events → R1 hash verifiable). Iterations 7–16 and the 12.6 hardening backfill (openai-compat, governance, data-driven AppBoot composition, scope/preset, long-run capability with compaction/budget/resume, todo_write + plan mode, sandbox on-host process confinement (darwin/linux), shell-docker environment-level isolation, runnable dist/jlink artifact + complete CLI, JSONL durability / typed LLM failures / fs realpath fence / platform warnings, REPL interaction surface (command registry + streaming render + typed failure rendering), production-simulation suite (replay-driven, keyless, deterministic — multi-step tasks + compaction + mid-flight kill/resume + budget stop + full R1 comparison), release engineering → 0.1.0 published (Maven Central + GitHub Release + jlink archives)) are done — composition is data, the R1–R4 invariants are in place, and real tasks run through the CLI; the dependency graph is compiler-enforced from day one.
>
> Naming: JPMS root name / packages `io.javanatic.harness.*`; Maven coordinates `io.github.retreatisadvance:harness-*` (groupId = Central namespace, intentionally different from the package names).

## Design cornerstones (five pillars + four invariants)

1. **Everything is a plugin** — stable plugin ids + static composition; swapping a provider swaps the product shape
2. **A session is an event log** — `LoggedEvent(seq, event)` envelope; model history is a derived projection (event sourcing)
3. **Capability seam, three roles** — Definition / Provider / Consumer, isolated at JPMS compile time
4. **Configuration is composition** — profile / bundle / patch layering, restricted interpolation (no arbitrary code)
5. **Explicitness and discipline** — exhaustive sealed switches, ScopedValue, fail loud, teardown ordering

Four governance invariants run through the whole design:

| # | Invariant | One-liner | Current mechanism |
|---|---|---|---|
| R1 | Reconstructability | A model turn's complete request can be rebuilt byte-for-byte from persisted facts | `llm/request` double hash anchor + JSONL replay closed-loop test |
| R2 | Execution consistency | Model-initiated side effects have exactly one controlled path | ToolExecutor five-stage pipeline + ArchUnit single-dispatch-point assertion |
| R3 | Effect disposal | Plugin failure / scope close cleans up every registered effect | Scope effect stack (LIFO) + atomic plugin mount rollback |
| R4 | Governance completeness | A production configuration can prove permissions, audit, and stop conditions are mounted | Constructor enforcement (landed) + `--verify` / policy profiles (it7) |

Full design docs: [docs/design/README.md](docs/design/README.md) (13 docs, with a navigation index and the R1–R4 master table). Design docs are written in Chinese (language policy: Chinese body + English titles).

## Get it

**Maven Central** — `io.github.retreatisadvance:harness-*` (0.1.0):

```xml
<dependency>
  <groupId>io.github.retreatisadvance</groupId>
  <artifactId>harness-kernel-core</artifactId>
  <version>0.1.0</version>
</dependency>
```

Prebuilt archives are attached to the [v0.1.0 release](https://github.com/retreatisadvance/javanatic-harness/releases/tag/v0.1.0): `javanatic-harness-0.1.0-<platform>.tar.gz` / `.zip` (runtime baked in — unpack and run `bin/jh`). To build from source instead, see below.

## Requirements

- **JDK 25** (LTS) + Maven 3.8+
- This repo uses [jenv](https://www.jenv.be/) for its local JDK: the committed `.java-version` (`25.0`) switches JDKs on `cd`; Maven resolves to the same JDK through the jenv shim, no manual `JAVA_HOME`:

```sh
jenv local 25.0        # only when adding a JDK or changing the version
mvn -B package         # build directly
```

Without jenv (e.g. CI), be explicit:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS
```

## Build & verify

```sh
mvn -B package              # full build (46 reactor modules, 513 tests; environment-gated tests self-skip)
mvn -B -pl :harness-kernel-core -am package   # one module plus its dependencies
```

`mvn -B package` also produces the jlink runtime image (it13) plus `javanatic-harness-<version>-<platform>.tar.gz` / `.zip` archives (it16) under `dist/jh/target/`: unpack and run, no hand-built module path.

```sh
dist/jh/target/jlink-image/bin/jh --help     # all flags and examples (exit 0)
dist/jh/target/jlink-image/bin/jh --verify   # composition + governance assertions (no key, exit 0/1)

dist/jh/target/jlink-image/bin/jh            # bare start enters the REPL (it14): non-/ lines become turns
                                             # sent to the model and streamed back; /help lists commands,
                                             # /exit (or EOF/Ctrl-D) quits, /cancel cancels the in-flight turn (it22);
                                             # a turn left open is journaled aborted; after the model asks a
                                             # question (a "← 提问:" line) the next line is the answer
                                             # Ctrl-C (it18) cancels the in-flight turn (journaled aborted) and stays in the REPL;
                                             # idle Ctrl-C quits like /exit; a second Ctrl-C before the cancel converges → exit 130

DEEPSEEK_API_KEY=sk-... dist/jh/target/jlink-image/bin/jh --workspace=<existing-dir> "task text"
# every run prints its session id (headless-<timestamp>-<short-random>); --resume=<id> continues the same session
# --sessions[=<N>] lists sessions (id/state/cwd/last activity/event count; keyless read-only, default 20;
#   mutually exclusive with task text / --resume / --verify)
# --resume=<id> without task text → REPL on that existing session
# --resume onto a session held by another writer (process/JVM) is refused fail-loud: exit 3, stdout empty
# task-result contract (it17; it22 adds the waiting-for-answer case):
#   stdout = final answer on success / the question on exit 5 / empty on failure
#   (success with no text is valid, exit 0); exit codes 0 done · 1 verify violation ·
#   2 usage/missing key · 3 task failed (incl. --resume writer-lock conflict) · 4 cancelled · 5 waiting for a human answer;
#   diagnostics (failure text, the model's last words) go to stderr —
#   `out=$(bin/jh "task")` reads the answer, exit code judges success
#   one-question-one-answer (it22): the answer to exit 5 is the next user message
#   (bin/jh --resume=<id> "answer text")
# --approval=ask wait bound (it22): a non-interactive terminal denies after 300s by default
#   (fail-closed — never hangs forever); --approval-timeout=<seconds> only with --approval=ask (0 = unbounded)
# Ctrl-C during a task (it18): cooperative cancel → turn/end aborted("user"), exit 4, stdout empty;
#   a second Ctrl-C before the cancel converges force-quits (130). Cancellation governs cooperative
#   operations only — a tool batch that ignores it and returns still closes Completed (exit 0)
# any OpenAI-compatible vendor:
#   … bin/jh "task" --api-key-env=MOONSHOT_KEY --base-url=https://api.moonshot.cn/v1 --model=kimi-k2 --provider=kimi
# container-level isolation (requires local docker and the image present; no auto-pull):
#   … bin/jh "task" --docker --image=ubuntu:24.04
```

The keyless vertical slice (replay model + fs tools + full journaling) is `examples/agent-spine`; the real-model e2e is its `RealModelAgentE2ETest`.

## Repository layout

```
docs/design/        13 design docs (00-overview … 12-api-stability) + docs/plan/ per-iteration acceptance
docs/dsh-reference.md   the reference-frame notes (dsh repo path conventions)
kernel/             the Cordis equivalent: core (unified Scope/Events/Plugin) + brand + config (YAML + ConfigService)
core/               agent trunk: session/tools/todo/plan/agent/agent-loop/system-prompt/preset (all implemented)
sandbox/            on-host process confinement: Definition + seatbelt/bwrap providers + policy resolution
                    (darwin/linux tested; windows designed-first)
llm/                seam + replay (the keyless test foundation) + openai-compat (generic adapter) + deepseek (real provider)
fs/ shell/          the capability trio (all implemented; shell has two mutually exclusive providers —
                    local bash and docker containers, see it12.5)
session/            persistence seam (JsonValue tree + codec SPI) + JSONL backend (R1 closed loop)
interaction/        approval three modes (auto/ask/deny, it7) + command surface (registry/slash parsing/event pairs, it14)
dist/               jlink runtime image orchestration: produces bin/jh (unpack and run, no hand-built
                    module path, it13)
bundle/ examples/   base composition (data-driven AppBoot/ConfigService assembly) + runnable examples
                    (agent-spine / headless)
```

## Roadmap (vertical slices)

| Slice | Modules | Design doc |
|---|---|---|
| 1 ✅ | `kernel.core` (unified Scope kernel) | [01-kernel.md](docs/design/01-kernel.md) |
| 2 ✅ | `core.session` (LoggedEvent envelope + Surface) | [03-session-event-sourcing.md](docs/design/03-session-event-sourcing.md) |
| 3 ✅ | `llm.seam` + `llm.replay` (keyless test foundation) | [10-testing.md](docs/design/10-testing.md) |
| 4 ✅ | `core.tools` + `fs.*` (R2 single execution path) | [05-capability-seam.md](docs/design/05-capability-seam.md) |
| 5 ✅ | `core.agent` + `core.agent-loop` + `examples/agent-spine` (slice closed loop + R2 architecture tests) | [04-agent-loop.md](docs/design/04-agent-loop.md) |
| 6 ✅ | `shell.*` + `llm.deepseek` + JSONL persistence + R1 replay hash closed loop + real-model full-stack e2e | [05](docs/design/05-capability-seam.md) / [03](docs/design/03-session-event-sourcing.md) |
| 7 ✅ | `llm.openai-compat` refactor + fs root + approval three modes + `--verify`/policy + `examples.headless` | [07-profile-bundle.md](docs/design/07-profile-bundle.md) |
| 8 ✅ | Data-driven AppBoot composition: kernel/config + bundle/base + CompositionManifest + headless migration | [07-profile-bundle.md](docs/design/07-profile-bundle.md) |
| 9 ✅ | scope/preset: ScopedToolRegistry + setup window + preset composition + home profile discovery (last breaking API iteration) | [06-scope.md](docs/design/06-scope.md) |
| 10 ✅ | Long-run capability: compaction producer + budget profile + --resume + request-context (first half of the production-simulation gate) | [03](docs/design/03-session-event-sourcing.md) |
| 11 ✅ | Getting work done: todo_write (whole-table snapshot) + plan mode (pure-fold + exit_plan_mode direct flip + dynamic prompt section); extension event codecs via ServiceLoader | — |
| 12 ✅ | Safe to let it run: sandbox (platform chain, darwin=seatbelt tested, linux/windows designed-first) + restriction (shell wrap + fs fence + plan read-only + PRODUCTION assertions) | [05](docs/design/05-capability-seam.md) |
| 12.5 ✅ | Environment-level isolation: `shell-docker`, a second `ShellExecutor` provider — the mount surface is the writable surface (the whole container root read-only); the three sandbox tiers are synonymously stronger in the container backend; provider swap, not seam change | [05](docs/design/05-capability-seam.md) |
| 12.6 ✅ | Hardening backfill: JSONL durability (fsync barrier + torn-tail repair), typed LLM failures (`LlmCallException` + `Kind`), LocalFs realpath fence (symlink escape sealed), `--verify` platform warnings; LoopGuard comments + 02 drift corrections | [03](docs/design/03-session-event-sourcing.md) / [05](docs/design/05-capability-seam.md) |
| 12.7 ✅ | Platform chain landed: Linux on-host confinement (bwrap backend) — darwin=seatbelt / linux=bwrap / win32=empty chain; CI dual-job real verification (ubuntu installs bubblewrap, macos adds seatbelt) | [05](docs/design/05-capability-seam.md) |
| 13 ✅ | Runnable artifact: dist (jlink) + complete CLI (`--help` / `--workspace=` / `--approval=`; run ids + CI smoke) | — |
| 14 ✅ | Interaction surface: REPL (landed in `interaction/commands`) + streaming render (chunk journaling + typed failure rendering by `FailureKind`) | — |
| 15 ✅ | Production simulation in CI: replay-driven (keyless, deterministic) + PRODUCTION policy + multi-step tasks + compaction + mid-flight kill/resume + budget stop + full R1 comparison | [03](docs/design/03-session-event-sourcing.md) |
| 16 ✅ | Release engineering → **0.1.0** published: Maven Central + GitHub Release (jlink archives), facade freeze, bilingual README | — |
| 17–25 (planned) | **0.2.0**: reliable single-agent CLI, cancellation/recovery/resource limits, workspace and session usability, external Java integration, Linux archives + Landlock, Windows confinement + pwsh | [Overall plan](docs/design/README.md#phased-evolution-plan) |
| 0.3 series (planned) | Reusable capabilities: skills + MCP Tools first; web access and LSP follow | [Overall plan](docs/design/README.md#phased-evolution-plan) |
| 0.4 (planned) | Managed background tasks first, then controlled multi-agent delegation | [Overall plan](docs/design/README.md#phased-evolution-plan) |

**Planning principle**: maintainer-led scenarios, real-task acceptance, and community feedback for calibration—not a prerequisite to begin. These future stages have not started; scope, dependencies, and acceptance gates live in the overall plan, with each iteration requiring its own scope confirmation and review checkpoints.

**0.1.0 platform support**: macOS and Linux (including on-host sandboxing). macOS ships seatbelt, works out of the box; Linux uses bwrap — **the host must install bubblewrap**, then it works out of the box; hosts without userns privileges fall back fail-closed (Landlock is the 0.2.0 fallback). **Windows is not in the 0.1.0 support surface** — no on-host sandbox backend, and `shell-bash-local` assumes bash exists (pwsh provider pending); both land with windows-acl in 0.2.0.

R1–R4 tests travel with each slice, never backfilled at the end ([10-testing.md](docs/design/10-testing.md)).

## API stability

0.1.0 freezes its public surface — JPMS exports, seam contracts, the event schema, plugin config keys, and the CLI. The full list is [docs/design/12-api-stability.md](docs/design/12-api-stability.md): **0.1.x patch releases do not break it; breaking changes go to 0.2.0 with release notes and a migration path.**

## License

**Apache-2.0** (confirmed 2026-09-08). The architecture is ported from an analysis of dsh (see [docs/dsh-reference.md](docs/dsh-reference.md)); this repository's code is an original implementation. Goal: open source for community use — a single JDK 25 LTS version (the finalized-ScopedValue narrative takes priority), with the first release targeting both international and Chinese-speaking communities.

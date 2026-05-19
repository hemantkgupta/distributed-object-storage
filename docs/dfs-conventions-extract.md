# DFS Conventions Extract — for the Object Storage Repo

> Source: `/Users/hemantkgupta/code-all/distributed-file-system/` (DFS), reconciled on 2026-05-20.
> Audience: anyone evolving `/Users/hemantkgupta/code-all/distributed-object-storage/` who wants to inherit the parts of DFS that proved to work and skip the parts that are DFS-specific.
>
> This is a *patterns* extract, not a copy-paste manifest. Every convention below was lifted from concrete DFS files and is annotated with where it lives in DFS and how it should land in the object-storage repo.

The DFS repo is a 15-module Gradle multi-project that pairs each architectural concern from the CSE wiki with one Java module and one prose module page. The conventions worth carrying over are the *shape* of that pairing — the build skeleton, the test discipline, the module-page template, the ADR template, and the architecture-doc layout. The contents (CRUSH, leases, MDS, custodians) are DFS-specific and should not migrate.

---

## 1. Gradle convention

DFS uses a flat Gradle multi-project with **no per-module `build.gradle` files**. All inter-module wiring lives in the root `build.gradle`. This is the single most consequential convention to copy — it makes the dependency graph visible in one screen.

### 1.1 `settings.gradle` — phase-grouped includes

DFS's `settings.gradle` is 27 lines. Modules are grouped by *phase* with header comments. Phases are the author-order narrative (foundation → storage backend → control plane → ops); within Gradle they have no semantic effect, but the grouping makes the file double as a reading order.

```
rootProject.name = 'distributed-file-system'

// Foundational
include 'dfs-common'

// Phase 1 — Foundation
include 'dfs-crush'
include 'dfs-placement'
include 'dfs-lease'
include 'dfs-node'

// Phase 2 — Storage backend
include 'dfs-allocator'
include 'dfs-storage'
include 'dfs-erasure'

// Phase 3 — Control plane
include 'dfs-mds'
include 'dfs-monitor'
include 'dfs-custodian'
include 'dfs-qos'

// Phase 4 — Ops, simulator, security
include 'dfs-simulator'
include 'dfs-metrics'
include 'dfs-security'
```

Patterns to inherit:

- **Foundational module first**, called out separately. DFS uses `dfs-common`; the equivalent here would be `dos-common` or `dos-types`. It hosts shared value objects (`PgId`, IDs, error types) that every other module imports.
- **One blank line + `// Phase N — <name>` comment** between groups. Cheap, makes the file scannable.
- **No version block, no plugin block, no buildscript block** in `settings.gradle`. All of that goes to `build.gradle`.

### 1.2 Root `build.gradle` — three-section layout

DFS's root `build.gradle` is 148 lines split into three sections. Memorise this shape.

**Section A — `allprojects { … }`.** Sets `group`, `version`, and `repositories`. That's it. No dependencies, no plugins. Eight lines.

```
allprojects {
    group = 'com.hkg.distributedfilesystem'
    version = '0.1.0-SNAPSHOT'

    repositories {
        mavenCentral()
    }
}
```

**Section B — `subprojects { … }`.** The default per-module configuration:

```
subprojects {
    apply plugin: 'java-library'

    java {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependencies {
        testImplementation platform('org.junit:junit-bom:5.10.2')
        testImplementation 'org.junit.jupiter:junit-jupiter'
        testImplementation 'org.assertj:assertj-core:3.25.3'
        testRuntimeOnly  'org.junit.platform:junit-platform-launcher'
    }

    test {
        useJUnitPlatform()
    }

    compileJava.options.encoding     = 'UTF-8'
    compileTestJava.options.encoding = 'UTF-8'
}
```

Patterns:

- **`java-library` plugin, not `application` or `java`.** The library plugin separates `api` from `implementation` configurations — the thing that lets a module expose a type to its consumers (`api project(':dfs-common')`) while keeping internal helpers private (`implementation project(':dfs-crush')`). The `api`/`implementation` distinction is what makes the dependency graph below meaningful; without it, every transitive dependency leaks.
- **Java 17 baseline** declared once. No toolchain block; the user pins via `jenv local`.
- **JUnit BOM via `platform(...)` import.** Lets you declare `junit-jupiter` without a version. Bump the BOM (`5.10.2`) and everything aligns.
- **AssertJ pinned by a direct version**, not via the BOM. AssertJ doesn't ship a BOM.
- **Encoding pinned to UTF-8** for both compile and compile-test. Avoids the platform-default ambiguity.
- **`useJUnitPlatform()`** must be inside the `test {}` block per project; it's the bridge between Gradle's `test` task and JUnit 5's launcher.

**Section C — `project(':...') { dependencies { … } }` blocks.** One block per module, no other config. This is the architectural rulebook expressed as code.

DFS's full Phase 1 wiring:

```
// ---- Phase 1: Foundation ----
project(':dfs-crush') {
    dependencies {
        api project(':dfs-common')
    }
}

project(':dfs-placement') {
    dependencies {
        api project(':dfs-common')
        implementation project(':dfs-crush')
    }
}

project(':dfs-lease') {
    dependencies {
        api project(':dfs-common')
    }
}

project(':dfs-node') {
    dependencies {
        api project(':dfs-common')
        implementation project(':dfs-crush')
        implementation project(':dfs-placement')
        implementation project(':dfs-lease')
    }
}
```

The `api`/`implementation` distinction is load-bearing:

- `api project(':dfs-common')` — the module re-exports common's types in its own public API. Consumers of this module transitively see common.
- `implementation project(':dfs-crush')` — the module uses crush internally. Consumers of this module do *not* see crush.

This is why `dfs-qos` can't accidentally see `dfs-custodian`: there's no `project(':dfs-qos')` block that depends on custodian. The build refuses to compile a stray import.

ADR-0004 in DFS articulates the rule: "the dependency graph (`build.gradle` `project(':...')` blocks) is the single source of truth for architectural rules. If `dfs-qos` accidentally imports `dfs-custodian`, the build fails."

The simulator block is the trapdoor — one module that depends on everything:

```
project(':dfs-simulator') {
    dependencies {
        api project(':dfs-common')
        implementation project(':dfs-crush')
        implementation project(':dfs-placement')
        // ...all 13 others...
    }
}
```

Patterns to inherit for the object-storage repo:

- **One Section-C block per module, in phase order, with `// ---- Phase N: … ----` separator comments.** Don't sort alphabetically; sort by phase so the build file reads top-to-bottom in the same order as `settings.gradle`.
- **Default to `implementation`; promote to `api` only when types cross the module boundary in public signatures.** The common module is the only consistent `api` everyone exports.
- **One "simulator" or "integration" module that depends on all others.** This is where cross-cutting tests live, and it's the only place where the full graph is allowed to coexist.
- **No per-module `build.gradle`.** Anything module-specific (e.g. main class, plugin) should be the rare exception, and even then prefer extending the Section-B `subprojects` block conditionally before falling back to a per-module file.

### 1.3 `gradle.properties` — minimal

DFS's `gradle.properties` is 5 lines:

```
org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
# JDK 17 baseline. The user pins Java with `jenv local <version>` —
# do not hardcode Homebrew Cellar paths here.
```

Patterns:

- **`-Xmx2g`** for daemon heap. Adequate for a 15-module project; bump if needed.
- **`parallel=true` + `caching=true`** — both compose well with the strict dependency graph because Gradle can parallelise leaves and cache identical inputs.
- **Comment about `jenv`**, not a `org.gradle.java.home` line. The user pins JDK per directory via `jenv local`; hardcoding Homebrew paths in `gradle.properties` is brittle (versions change) and exclusive (only works on the author's machine).

### 1.4 Version pinning summary

| What | Where | Strategy |
|---|---|---|
| Gradle | `gradle/wrapper/gradle-wrapper.properties` | Wrapper, committed |
| Java | `subprojects.java { sourceCompatibility = 17 }` | Source-level pin |
| JDK install | (out of repo) | `jenv local <version>` |
| JUnit 5 | `platform('org.junit:junit-bom:5.10.2')` | BOM |
| AssertJ | `'org.assertj:assertj-core:3.25.3'` | Direct |
| Project version | `allprojects.version = '0.1.0-SNAPSHOT'` | Single point |

No `dependencyManagement` plugin, no `libs.versions.toml`, no version catalog. The project is small enough that the BOM + direct-version mix is readable.

---

## 2. Test convention

DFS standardises on JUnit 5 (Jupiter) + AssertJ. Tests live alongside their module in `src/test/java/com/hkg/dfs/<concern>/`.

### 2.1 What the dependencies wire up

From the root `build.gradle` `subprojects` block, every module automatically gets:

- `junit-jupiter` API + engine (via the BOM).
- `assertj-core` for fluent assertions.
- `junit-platform-launcher` at test runtime — Gradle's `useJUnitPlatform()` bridge needs this to discover and launch the engine.

No Mockito, no Hamcrest, no Spring Boot test starter. The point is that pure unit tests don't need them; if a module ever does, it should justify the addition in its module page §7.

### 2.2 Naming

- Test class: `<ClassUnderTest>Test` in the same package as the class under test. So `Custodian` → `CustodianTest`, in `com.hkg.dfs.custodian`.
- Test methods: `camelCaseDescriptiveSentence()`, no `should` prefix, no `test` prefix. Examples from DFS:
  - `dispatchCriticalSubmitsToCriticalClass`
  - `scrubItemsGoToScrubClass`
  - `criticalPreemptsClient`
  - `scannerThenDispatchEndToEnd`
- Each method name reads as the property being demonstrated, in the active voice. The corresponding row in §5 of the module page (Key tests) reuses the same name in the left column.

### 2.3 Shape

Verbatim test from `dfs-custodian/src/test/java/com/hkg/dfs/custodian/CustodianTest.java`:

```java
package com.hkg.dfs.custodian;

import com.hkg.dfs.common.PgId;
import com.hkg.dfs.monitor.DurabilityEvent;
import com.hkg.dfs.qos.DmClockScheduler;
import com.hkg.dfs.qos.QosClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CustodianTest {

    private DmClockScheduler scheduler;
    private Custodian custodian;

    @BeforeEach
    void setUp() {
        scheduler = new DmClockScheduler();
        scheduler.addClass(new QosClass(Custodian.CRITICAL_QOS, 100, 10, 1000));
        scheduler.addClass(new QosClass(Custodian.ROUTINE_QOS,   10,  5, 1000));
        scheduler.addClass(new QosClass(Custodian.SCRUB_QOS,      0,  1, 1000));
        scheduler.addClass(new QosClass(Custodian.REBALANCE_QOS,  0,  1, 1000));
        custodian = new Custodian(scheduler);
    }

    @Test
    void dispatchCriticalSubmitsToCriticalClass() {
        custodian.dispatch(new WorkItem(PgId.of(0), PriorityClass.CRITICAL_REPAIR, "x"));
        assertThat(scheduler.queueDepth(Custodian.CRITICAL_QOS)).isEqualTo(1);
    }

    @Test
    void scannerThenDispatchEndToEnd() {
        ClusterState state = new ClusterState(
                Set.of(PgId.of(0)),
                List.of(new DurabilityEvent(PgId.of(0), 0, 2)),
                Set.of(), Set.of(), Set.of()
        );
        for (WorkItem w : new RepairScanner().scan(state)) custodian.dispatch(w);
        scheduler.dispatch();
        assertThat(custodian.dispatched(Custodian.CRITICAL_QOS)).isEqualTo(1);
    }
}
```

Patterns:

- **Package-private classes and methods.** No `public class CustodianTest`. JUnit 5 doesn't require `public` and dropping it signals "this is a unit test, not an API artifact".
- **`@BeforeEach setUp()` wires real collaborators, not mocks.** DFS tests instantiate real `DmClockScheduler`, real `RepairScanner`, etc. The module boundary is fine-grained enough that the "real" collaborator is cheap. If a test needs to fake something, prefer hand-rolled fakes over Mockito.
- **AssertJ `assertThat(...)` exclusively.** Static import `org.assertj.core.api.Assertions.assertThat`. Don't mix with JUnit's `Assertions`.
- **One assertion concept per test.** A test may have multiple `assertThat` lines if they all probe the same property, but it should never assert two unrelated facts. `scrubItemsGoToScrubClass` asserts on `queueDepth(SCRUB_QOS) == 2` only.
- **Object construction is inline.** Records make this readable: `new WorkItem(PgId.of(0), PriorityClass.CRITICAL_REPAIR, "x")` says everything you need. No builder pattern.
- **Static factory methods on value objects** (`PgId.of(0)`) instead of public constructors. Reads as English; lets the type cache or validate inputs.
- **End-to-end tests live in the consumer module**, not the producer. `scannerThenDispatchEndToEnd` is in `CustodianTest` because Custodian is what composes scanner + scheduler. The simulator module collects the cross-module end-to-end tests.

### 2.4 What's *not* in the tests

- No `@Disabled` ever. If a test can't run, delete it or put it in a draft branch.
- No `Thread.sleep`. Tests are deterministic; concurrency is exercised via direct invocation.
- No `@SpringBootTest`, no application context loading. These are unit tests for plain-old Java types.
- No system-property gating, no environment-variable gating.

Goal: `./gradlew test` is the only command anyone needs to know.

---

## 3. Module README format — DFS uses centralised module pages

DFS made a deliberate choice that the object-storage repo should consider adopting: **module-level README files do not exist**. Each Gradle subproject is a directory with `src/` and a `build/` artifact, nothing else at the top level. Module documentation lives centrally in `docs/modules/<module>.md`.

Why centralise:

- **One place to read.** `docs/modules/` is browsable as a table of contents. A reader can scan all 15 module pages without `cd`-ing through subdirectories.
- **One place to update.** Cross-cutting refactors (renaming a concept, moving a class) only touch `docs/modules/`, not 15 per-module READMEs.
- **README ambiguity avoided.** GitHub will render a per-module README in its directory view, which competes with the central docs. Removing per-module READMEs removes that competition.
- **The module page can reference siblings.** A central page can link `[`dfs-qos`](./dfs-qos.md)` without `../../dfs-qos/README.md` path gymnastics.

The DFS per-module page has a rigid seven-section structure. Below is the full §1–§7 skeleton from `docs/modules/dfs-custodian.md`, with real content excerpts.

### 3.1 §1 — Role

A two-or-three-sentence statement of what the module *is*. Often expressed as the public function it offers.

```
## 1. Role

The stateless background control loop. Two collaborators:

- **`RepairScanner.scan(state)`** — pure function turning a cluster-state snapshot into a sorted list of `WorkItem`s.
- **`Custodian.dispatch(item)`** — maps a `PriorityClass` to a dmClock class name and submits to the scheduler.

The pattern is the wiki's Custodian: scrub, repair, rebalance, GC, and tier transitions all run through one stateless control loop that's separate from the foreground placement plane.
```

Pattern: lead with the verbs the module exposes. The reader should be able to skim §1 and know what the module *does* before any architecture is brought in.

### 3.2 §2 — Wiki anchor

A single line pointing at the primary wiki concept page. This is what closes the loop with the knowledge base.

```
## 2. Wiki anchor

[`wiki/patterns/custodian-background-control-plane`](https://github.com/hemantkgupta/CSE-Raw/blob/main/wiki/patterns/custodian-background-control-plane.md).
```

If there's no wiki page yet, write `*no primary wiki anchor — candidate for a new concept/pattern page*`. The code-companion sync rule depends on this section existing.

### 3.3 §3 — Public API surface

A compilable-looking Java snippet showing exactly what consumers see. Not full source — just the types, method signatures, and short comments. Records inline.

```
## 3. Public API surface

```java
package com.hkg.dfs.custodian;

public final class Custodian {
    public static final String CRITICAL_QOS  = "critical-repair";
    public static final String ROUTINE_QOS   = "routine-repair";
    public static final String SCRUB_QOS     = "scrub";
    public static final String REBALANCE_QOS = "rebalance";

    public Custodian(DmClockScheduler scheduler);
    public void dispatch(WorkItem item);
    public int dispatched(String className);   // test hook
}

public final class RepairScanner {
    public List<WorkItem> scan(ClusterState state);   // returns sorted by priority
}

public record WorkItem(PgId pg, PriorityClass priority, String description) {}

public enum PriorityClass {
    CRITICAL_REPAIR, ROUTINE_REPAIR, DEEP_SCRUB, SHALLOW_SCRUB, REBALANCE, TIER_TRANSITION
}
```

Source: `dfs-custodian/src/main/java/com/hkg/dfs/custodian/`.
```

Pattern: this snippet is the contract. If it diverges from `src/main/java/`, the page is stale. Code-companion §1 calls this out as load-bearing.

### 3.4 §4 — Internal structure

Bullet-point or short-paragraph walk through the non-obvious design choices. Often calls out collections, sort orders, idempotency keys.

```
## 4. Internal structure

- **`RepairScanner.scan(state)`** — walks each of the five lists in `ClusterState` and produces a `WorkItem` per entry, with the appropriate `PriorityClass`. Then sorts by `priority.ordinal()` ascending (so CRITICAL_REPAIR comes first).
- **`Custodian.classMap`** — `Map<PriorityClass, String>` mapping each priority class to a dmClock class name. Both `DEEP_SCRUB` and `SHALLOW_SCRUB` map to `SCRUB_QOS`; both `REBALANCE` and `TIER_TRANSITION` map to `REBALANCE_QOS` — coarser dmClock granularity than the scanner's priority taxonomy.

The dispatcher:

```java
public void dispatch(WorkItem item) {
    String cls = classMap.get(item.priority());
    scheduler.submit(cls, () -> dispatched.merge(cls, 1, Integer::sum));
}
```
```

Pattern: §4 is the only section allowed to inline implementation snippets. They should be short (5–15 lines) and pulled directly from source.

### 3.5 §5 — Key tests

A `| Test | Demonstrates |` table whose left column is the JUnit method name verbatim, right column is the property under test. Reads like the module's behavioural specification.

```
## 5. Key tests

18 tests across `RepairScannerTest` (9) and `CustodianTest` (9).

| Test | Demonstrates |
|---|---|
| `durabilityEventBecomesCriticalRepair` | Each `DurabilityEvent` becomes a `CRITICAL_REPAIR` work item. |
| `criticalRepairSortsAheadOfScrub` | Sorted output places CRITICAL_REPAIR before DEEP_SCRUB. |
| `deepScrubBeforeShallowScrub` | Ordinal-based sort: DEEP_SCRUB ahead of SHALLOW_SCRUB. |
| `dispatchCriticalSubmitsToCriticalClass` | After `dispatch(criticalItem)`, the scheduler's `critical-repair` queue has one op. |
| `scrubItemsGoToScrubClass` | Both DEEP_SCRUB and SHALLOW_SCRUB priorities map to the `scrub` QoS class. |
| `tierTransitionUsesRebalanceClass` | Both REBALANCE and TIER_TRANSITION map to the `rebalance` QoS class. |
| `scannerThenDispatchEndToEnd` | Scanner output, when dispatched through the Custodian and run by the scheduler, increments the dispatched counter. |
```

Pattern: the lead line gives the count (`18 tests across …`). If a test is added or deleted, this line must be updated — it's a tripwire against staleness.

### 3.6 §6 — Where it fits

The local dependency context: who depends on this module, who this module depends on, and what dependency rule the module's position in the graph encodes.

```
## 6. Where it fits

**Upstream consumers:** `dfs-simulator` (drives end-to-end failure-and-recovery scenarios).

**Downstream dependencies:** `dfs-common`, `dfs-monitor` (`DurabilityEvent`), `dfs-qos` (the scheduler it submits to).

**The dependency rule:** the Custodian is the *only* module besides the simulator that depends on both the monitor and the QoS scheduler. The scheduler doesn't know the Custodian exists; the monitor emits events without knowing who consumes them.
```

Pattern: the third paragraph ("dependency rule") is what makes this page architecturally informative rather than just a class listing. It states the constraint that the build graph enforces.

### 3.7 §7 — Stubs and departures from production

Honest enumeration of what is *not* a real implementation. Code-companion's Gaps table references these.

```
## 7. Stubs and departures from production

- **`Runnable` is a counter increment, not real repair.** A real Custodian dispatcher reads the failed PG's chunks, finds surviving replicas, allocates new destinations, and orchestrates the actual data move.
- **`ClusterState` is pull-snapshot, not subscription.** Real Custodians subscribe to event streams from the monitor and react incrementally.
- **No priority elevation policy.** The wiki's "auto-elevate recovery when PG below floor" logic isn't here.
- **No work-item deduplication.** If the same PG appears in `belowFloor` across two scans, two CRITICAL_REPAIR items are emitted.
- **No backpressure on the scheduler.** A real Custodian throttles submissions when the scheduler's queue depth exceeds a threshold.
```

Pattern: each bullet is `**Short claim.** Concrete explanation of what production does instead.` This section is where the teaching repo earns trust — readers can find out what's faked before discovering it the hard way.

### 3.8 Patterns to inherit for object-storage

- **No per-module README.** Put module docs in `docs/modules/<module>.md`.
- **Seven sections in exactly this order**: Role, Wiki anchor, Public API surface, Internal structure, Key tests, Where it fits, Stubs and departures.
- **§1 leads with verbs.** §3 leads with package + class signatures. §5 leads with the test count.
- **Every page reconciled-on date in the preamble**: `> Last reconciled with the repo on YYYY-MM-DD.` This is the only frontmatter.

---

## 4. ADR format

DFS keeps ADRs in `docs/decisions/`, with a `README.md` index alongside. As of 2026-05-20 there are six:

```
docs/decisions/
├── README.md
├── 0001-pure-java-no-jni.md
├── 0002-in-memory-substrates.md
├── 0003-xor-parity-stub-not-galois.md
├── 0004-15-modules-by-concern.md
├── 0005-aes-gcm-real-crypto.md
└── 0006-record-types-for-value-objects.md
```

### 4.1 Naming pattern

`NNNN-kebab-case-decision-summary.md`. Four-digit zero-padded sequence number; the slug summarises the decision in 3–6 hyphenated words and reads as a *conclusion*, not a question. "15-modules-by-concern", not "how-many-modules".

### 4.2 Structure template

Each ADR has these sections in this order:

1. `# ADR-NNNN: <Title that is the decision in plain English>` — H1 title that restates the slug as a sentence
2. `**Status**: Accepted | Proposed | Superseded by ADR-MMMM`
3. `**Date**: YYYY-MM-DD`
4. `**Deciders**: <who>` (usually "Engineering team" in this repo)
5. `## Context` — what forced the decision; what alternatives are reachable from here
6. `## Decision` — what was chosen, stated affirmatively (with concrete identifiers, e.g. "Use 15 Gradle subprojects")
7. `## Alternatives considered` — each named alternative as a sub-paragraph led by its name in bold. State *what* it would have been and *why it was rejected*.
8. `## Consequences` — split into `**Positive:**` bullet list and `**Negative:**` bullet list
9. `## Implementation pointers` — concrete file paths or commands that put the decision into effect
10. `## Related` — link to architecture.md, sibling ADRs, and the wiki

### 4.3 Example — `0004-15-modules-by-concern.md`

```
# ADR-0004: 15 Modules Split by Architectural Concern, Not by Phase

**Status**: Accepted
**Date**: 2026-05-20
**Deciders**: Engineering team

## Context

The repo could have organised its code along several axes:

- **By phase** (the build order: foundation, storage backend, control plane, ops).
- **By plane** (control plane / data plane).
- **By concern** (placement, consistency, durability, QoS, etc.). One module per concern; lets each be tested in isolation.

## Decision

Use 15 Gradle subprojects, one per architectural concern. […]

The phase grouping is the order of authoring (and matches the blog narrative). The module name is the concern. The dependency graph encodes the architectural rules: `dfs-qos` knows nothing about `dfs-custodian`; `dfs-monitor` knows nothing about `dfs-mds`; `dfs-simulator` is the only module that depends on everything.

## Alternatives considered

**Single monolith module.** Faster to author. Loses the strict-isolation property […].

**Fewer, coarser modules (e.g. 5 — common, foundation, data, control, ops).** Cleaner top-level structure. Loses the "one concern per module" guarantee […].

**Module per plane (control / data).** Matches the wiki's primary cleavage. But within a plane there are still 4+ distinct concerns; you'd need sub-packaging anyway. […]

**Module per class (one Gradle subproject per Java class).** Maximally granular. Operationally absurd; Gradle has overhead per subproject.

## Consequences

**Positive:**
- Each module's tests run in isolation.
- The dependency graph […] is the single source of truth for architectural rules.
- Module pages in `docs/modules/` are 1:1 with Gradle subprojects.

**Negative:**
- 15 subprojects is more Gradle overhead than 1. […]
- Some natural collaborators end up cross-module dependencies.
- The `dfs-common` module ends up depended on by 14 others.

## Implementation pointers

- `settings.gradle` — the list of 15 includes plus inline comments grouping them by phase.
- `build.gradle` — top-level `project(':...')` blocks defining the per-module dependency graph.

## Related

- [`architecture.md`](../architecture.md) — the module dependency graph
- [`modules/README.md`](../modules/README.md) — the per-module index
- [ADR-0006](./0006-record-types-for-value-objects.md) — closely related: per-module style conventions
```

### 4.4 Patterns to inherit

- **Four-digit numeric prefix.** Lets ADRs sort lexicographically and gives room to grow to 9999.
- **Status is single-word**, not a sentence. "Accepted", "Proposed", "Superseded by ADR-0042".
- **Decision section uses imperative phrasing.** "Use X." not "We will probably use X."
- **Alternatives considered is explicit, not implicit.** Each alternative gets a bold lead and a why-rejected paragraph. Readers can audit the decision space.
- **Consequences split positive/negative.** Forces the author to name a downside; no decision is truly free.
- **Implementation pointers, not "should be implemented somehow".** Concrete file paths so a reader can verify the ADR landed.

---

## 5. Architecture doc patterns

DFS's `docs/architecture.md` is 161 lines and does five distinct jobs. The format generalises.

### 5.1 The preamble

A reconciled-on date and a one-line scope statement:

```
# Architecture — Repository Layout vs Wiki Concepts

> Last reconciled with the repo on 2026-05-20.
>
> How the 15 Java modules map onto the architectural decisions in the CSE wiki, and the dependency graph that lets each one be tested in isolation.
```

### 5.2 The two-axis mapping

DFS opens with an explicit declaration of how the codebase is organised:

```
## The two-axis mapping

This repo organises code along two axes:

- **Phase** — the order it was built (foundation → storage backend → control plane → ops). Phases are about *learning order*, not deployment.
- **Concern** — what architectural layer the module implements (placement, consistency, durability, QoS, etc.). Concerns are about *what the code actually does*.

The folder layout reflects concerns. The README, blog, and build flow follow phases.
```

Pattern: when a repo has tension between two valid organising principles, name them at the top. Saves the reader from inventing one.

### 5.3 ASCII box diagrams

DFS uses fenced ASCII boxes, not mermaid, for the high-level plane diagram. Mermaid is used elsewhere; the architecture doc deliberately picks ASCII for two reasons: it renders identically in any markdown viewer, and box-and-arrow plane diagrams are awkward in mermaid's graph syntax.

The DFS plane diagram:

```
                       ┌─────────────────────────────────┐
                       │       CONTROL PLANE              │
                       │  (sharded metadata, placement,   │
                       │   monitoring, background ops)    │
                       │                                  │
                       │  dfs-mds            dfs-monitor  │
                       │  dfs-qos            dfs-custodian│
                       │  dfs-placement      dfs-lease    │
                       │  dfs-crush                       │
                       └────────────────┬─────────────────┘
                                        │
                                        │ cluster map, leases,
                                        │ placement decisions
                                        ▼
                       ┌─────────────────────────────────┐
                       │        DATA PLANE                │
                       │  (raw-block storage, EC,         │
                       │   per-OSD QoS, integrity)        │
                       │                                  │
                       │  dfs-storage   dfs-allocator     │
                       │  dfs-erasure                     │
                       └─────────────────────────────────┘
```

The DFS module dependency graph (also ASCII):

```
              dfs-common
                 ▲
                 │ depended on by everything
                 │
   ┌─────────────┼─────────────┬───────────┬────────────┐
   │             │             │           │            │
dfs-crush  dfs-placement  dfs-lease  dfs-allocator  dfs-metrics
   │             │             │           │            │
   └─────┬───────┴─────┬───────┘           │            │
         │             │                   ▼            │
         │             │             dfs-storage        │
         │             │                   ▲            │
         │             │                   │            │
         ▼             ▼             dfs-erasure        │
       dfs-node                            ▲            │
```

Style rules the DFS doc uses:

- **Box characters from the Unicode box-drawing set**: `┌ ┐ └ ┘ ─ │ ▲ ▼ ◄`. UTF-8 in `gradle.properties` ensures these survive.
- **Arrows on the same line as the line they decorate.** Vertical arrows are `▲` or `▼`; horizontal are `◄──` or `──►`.
- **Annotations as text labels on the connecting line**, e.g. `cluster map, leases, placement decisions`.
- **Module names are kebab-case, padded to align.** The visual symmetry makes the graph readable at a glance.

### 5.4 The mapping table

Following the diagrams, DFS has a flat table from wiki concept → module → primary classes:

```
| Wiki concept | Module | Primary classes |
|---|---|---|
| [`concepts/crush-placement-algorithm`](…) | `dfs-crush` | `Crush`, `StrawSelector`, `CrushMap` |
| [`patterns/hybrid-deterministic-lookup-placement`](…) | `dfs-placement` | `Placement`, `BlockLayer`, `PgLocation` |
| [`concepts/chunk-lease`](…) | `dfs-lease` | `LeaseService`, `ChunkLease` |
```

Pattern: this table is the wiki-to-code Rosetta Stone. If you can't put a row here, either the wiki page doesn't exist (so file it) or the module doesn't implement it (so name the gap).

### 5.5 The "how the planes communicate" table

A second table calling out the only data structures crossing module boundaries:

```
| Boundary primitive | Owned by | Read by |
|---|---|---|
| `PgLocation { Generation, List<OsdId> }` | `dfs-placement.BlockLayer` | `dfs-node` (resolution); future client APIs |
| `ChunkLease { ChunkId, OsdId, expiresAt }` | `dfs-lease.LeaseService` | `dfs-monitor.grantLease`, `dfs-node` |
```

Pattern: when there are 10–20 modules, the *interfaces between them* matter more than the modules themselves. Enumerate them explicitly.

### 5.6 The departures-from-production callout

DFS ends with a numbered list titled "Where this departs from production". Each entry is a paragraph: what real production does, what this repo does instead, why.

This is the architecture-doc complement to per-module §7. The architecture doc names *systemic* departures (in-memory storage, single-node monitor); module pages name *local* departures.

### 5.7 Length

DFS's architecture.md is ~160 lines. Patterns to inherit:

- Two ASCII diagrams (one structural, one dependency)
- Two tables (concept→module, boundary primitives)
- Two narrative sections (two-axis mapping at the top, departures from production at the bottom)
- Closing `## Related` block linking sibling docs

Target 150–200 lines. Beyond that, push detail down into module pages.

---

## 6. Code-companion format

DFS keeps `docs/code-companion.md` (121 lines) as the explicit bridge between the long-form blog post, the wiki, and the code. It's the second-most-load-bearing doc after `architecture.md`.

### 6.1 Sync rule (top-of-file)

```
> **Wiki concept exists ⇒ a `docs/modules/<m>.md` page exists ⇒ matching code exists.**
> If any of those three is missing, the [Gaps](#gaps) section names it.
```

Pattern: state the contract before the table. The contract is *triangular* — wiki ↔ module page ↔ code — and the code-companion is the enforcer.

### 6.2 The Blog Part → Code Map

The body of the file walks the blog part-by-part. Each blog section gets four bullet-line fields:

```
### Part 4 — Hybrid Placement: CRUSH + Lookup

- **Wiki concept(s):** [`concepts/crush-placement-algorithm`](…), [`patterns/hybrid-deterministic-lookup-placement`](…), [`tradeoffs/crush-vs-lookup-placement`](…).
- **Module(s):** `dfs-crush` for the deterministic hop; `dfs-placement` for the PG lookup table; the two are composed by `dfs-node`.
- **Key classes:** `dfs-crush/.../Crush.java`, `dfs-crush/.../StrawSelector.java`, `dfs-crush/.../CrushMap.java`; `dfs-placement/.../Placement.java`, `dfs-placement/.../BlockLayer.java`.
- **Module page:** [`modules/dfs-crush.md`](…), [`modules/dfs-placement.md`](…).
```

The four fields:

1. **Wiki concept(s)** — every page in the wiki that the blog section depends on. Plural is normal.
2. **Module(s)** — every Gradle subproject whose code is the implementation. Plural is normal.
3. **Key classes** — the specific class files. Relative paths from repo root.
4. **Module page** — the prose doc that explains the module. Should already exist; if not, the row is a Gaps entry.

A fifth optional field appears when the blog claims more than the code does:

- **Caveat:** *brief statement of what's missing*. See [Gaps](#gaps) for the full list.

### 6.3 The Gaps table

Bottom of the file:

```
## Gaps (blog claims the code does not implement)

| Blog reference | Status in code | Where it shows up |
|---|---|---|
| **ClayCodes** (Part 6) | Not implemented. `dfs-erasure` ships `Replication`, `ReedSolomon` (stub), and `LRC` (cost-only). | [`modules/dfs-erasure.md`](…) §7. |
| **True Galois-field Reed-Solomon** (Part 6) | Replaced by an XOR-with-rotation stub. | ADR [`0003`](…); `dfs-erasure/.../ReedSolomon.java`. |
| **Paxos-replicated monitor** (Part 10) | `dfs-monitor` is single-node, in-process. | [`modules/dfs-monitor.md`](…) §7. |
| **End-to-end wired data path** (Part 1 / Part 5 / Part 7) | `dfs-node.NodeApi.put` composes CRUSH + Block Layer + LeaseService + ExtentService but does **not** persist bytes through `dfs-storage.Osd`. | [`modules/dfs-node.md`](…) §7. |
```

Pattern: every row points at the §7 of the affected module page (or an ADR). The Gaps table is a hub; the leaves are the per-module stubs sections.

### 6.4 Patterns to inherit

- **One section per blog part.** Even if the blog part is purely motivational ("Capacity Math at 10 EB"), include the section and write `**Module(s):** none. The numbers are absorbed into module pages.` Don't skip it; the symmetry is the point.
- **Four (or five) standard fields per part.** Identical structure across every section makes the doc easy to scan and easy to keep consistent.
- **Gaps table at the bottom.** Honest about what's missing. If the gap is in any way reachable from the blog, it must have a row.
- **Mention the sync rule explicitly.** It's a contract; contracts have to be stated to be enforceable.

---

## 7. DFS-specific things NOT to copy

The DFS repo is a *distributed file system* simulator. Several of its building blocks make sense for files but not for object storage. The object-storage repo should *not* inherit the following:

### 7.1 CRUSH for placement

`dfs-crush` implements the Ceph-style CRUSH algorithm — pseudo-random hierarchical bucket selection with weighted "straw" sampling. CRUSH solves two file-system problems: (a) clients must compute placement client-side without contacting a master, and (b) failure domains must be respected for chunks of files.

Object storage typically uses **consistent hashing or partition tables** because objects are independent. Don't import CRUSH wholesale. If you need pseudo-random placement, use a much simpler ring; if you need failure-domain awareness, encode it explicitly per partition rather than embedding hierarchy in the placement function.

### 7.2 Chunk leases and extent sealing

`dfs-lease` implements GFS-style chunk leases (primary writer per chunk, others read replicas) and HDFS-style extent sealing (an extent is open-then-append-then-sealed; sealed extents are immutable). These solve the file-system concurrency model: many writers appending to one file, eventual closure semantics.

Object storage's PUT is atomic-per-object: one writer, one object, write completes or it doesn't. **No leases, no extents.** If you need transactional multi-part uploads, use a multipart-upload state machine (S3 model), not extents.

### 7.3 MDS with capability vectors and dynamic subtree partitioning

`dfs-mds` is a CephFS-style metadata server. Capability vectors are POSIX file-system semantics (open-with-permissions, recall on conflicting access). Dynamic subtree partitioning balances directory load across MDS shards.

Object storage has no directory tree (object keys are flat strings) and no POSIX semantics (no `open`, no permission caching, no inode locking). **Drop the entire MDS module.** The object-storage equivalent of metadata is a bucket → object key index, which is a sharded KV store with simpler semantics — closer to a Cassandra-style partitioned table than a CephFS MDS.

### 7.4 Monitor with durability events for replica counts

`dfs-monitor` emits `DurabilityEvent { PgId, currentReplicas, requiredReplicas }`. The "below floor" trigger fires when `currentReplicas < requiredReplicas`. This presumes a replica-count model.

Object storage with erasure coding has *shard* counts, not replicas. The durability event needs to be reshaped: `DurabilityEvent { ObjectId, currentShards, requiredShards, failureDomain }`. The Custodian pattern still applies; the data structure changes.

### 7.5 The Custodian as currently shaped

`dfs-custodian` is a good *pattern* to inherit (a stateless control loop that turns durability events into prioritised work items dispatched through a QoS scheduler). But its specific work-item types (`DEEP_SCRUB`, `SHALLOW_SCRUB`, `TIER_TRANSITION`) are biased toward block-storage scrubbing. For object storage, the equivalents would be `REPAIR_SHARD`, `REREPLICATE`, `LIFECYCLE_TRANSITION` (S3 lifecycle), `GARBAGE_COLLECT` (orphan objects after multipart-upload abort).

### 7.6 Bitmap allocator and BlueStore

`dfs-allocator` is an L0/L1 bitmap cascade for selecting free extents on a raw block device. `dfs-storage` is a BlueStore-style user-space OSD. Both presume the storage abstraction is *raw blocks* on bare disks.

Object storage typically runs over a backend that already handles allocation — could be an LSM-tree KV (RocksDB), an S3-compatible vendor backend, or simply a filesystem. **Don't reimplement bitmap allocation.** Pick a storage substrate appropriate to the object-storage role (LSM for small-object density, log-structured append for large objects) and skip the allocator entirely.

### 7.7 Reed-Solomon and LRC stubs in `dfs-erasure`

`dfs-erasure` has XOR-parity-stub Reed-Solomon and cost-only LRC. The shape (k data + m parity, decoded by collecting any k) is right, but the math is fake. Don't carry the XOR-stub forward; if the object-storage repo needs erasure coding, either depend on a real library (e.g. Backblaze's `JavaReedSolomon`) or write a faithful GF(2^8) implementation as a separate module.

### 7.8 The dependency-graph specifics

DFS's specific phase-by-phase wiring (`dfs-custodian` depends on `dfs-monitor` and `dfs-qos`; `dfs-node` depends on crush + placement + lease) is shaped by file-system concerns. Don't copy the graph. **Copy the discipline that produced the graph**: one module per concern, foundational module at the root, simulator at the leaves, no per-module `build.gradle`.

### 7.9 The 15-module count

DFS has 15 modules because file systems decompose into 15 distinct concerns at the level of granularity DFS chose. Object storage will have a different number — probably fewer (no MDS, no leases, no allocator, no separate crush). Don't aim for 15. Aim for one module per concern that has a primary wiki anchor.

---

## 8. Patterns to apply — summary table

| Convention | DFS source | Apply to object-storage as |
|---|---|---|
| Flat Gradle multi-project, no per-module `build.gradle` | `settings.gradle`, root `build.gradle` | Same shape; phase-grouped includes in `settings.gradle`; `subprojects` block in root `build.gradle`; one `project(':...')` block per module wiring dependencies. |
| Foundational `common` module everyone depends on | `dfs-common` | Create `dos-common` (or `dos-types`) holding `ObjectId`, `BucketId`, `ShardId`, error types. Everyone `api`-imports it. |
| `java-library` plugin to get `api` vs `implementation` | `subprojects { apply plugin: 'java-library' }` | Same. The `api`/`implementation` distinction is what makes module isolation enforceable. |
| Java 17 baseline, `jenv local` for JDK install | `gradle.properties` comment + `subprojects.java { … VERSION_17 }` | Same. Document jenv usage in a top-level README or CLAUDE.md. |
| JUnit 5 + AssertJ, no Mockito | `subprojects.dependencies` block | Same. Hand-roll fakes if you need them. |
| `<ClassName>Test` naming, package-private, AssertJ-only assertions | `CustodianTest`, `RepairScannerTest` | Same. Methods named as active-voice property declarations. |
| Real collaborators in `@BeforeEach`, not mocks | `CustodianTest.setUp()` | Same. Module boundaries are small enough that real instances are cheap. |
| Centralised module docs in `docs/modules/<m>.md`, no per-module READMEs | `docs/modules/dfs-custodian.md` etc. | Same. Seven sections: Role, Wiki anchor, Public API surface, Internal structure, Key tests, Where it fits, Stubs and departures. |
| Test-count tripwire in §5 lead line | "18 tests across `RepairScannerTest` (9) and `CustodianTest` (9)" | Same. Forces the doc to update when tests change. |
| ADRs in `docs/decisions/NNNN-kebab-slug.md` | `0001-…` through `0006-…` | Same. Four-digit numeric prefix, conclusion-style slug. |
| ADR sections: Context, Decision, Alternatives considered, Consequences, Implementation pointers, Related | `0004-15-modules-by-concern.md` | Same. Alternatives section enumerates each rejected option with a bold lead. Consequences split positive/negative. |
| `architecture.md` with two-axis mapping, ASCII plane diagram, ASCII dep graph, concept→module table, boundary-primitive table | `docs/architecture.md` | Same shape. Target 150–200 lines. ASCII not mermaid for plane and dependency diagrams. |
| `code-companion.md` linking blog → wiki → module → code, with explicit Gaps table | `docs/code-companion.md` | Same. The sync rule (wiki ⇒ module page ⇒ code) is the contract; Gaps table names every violation. |
| Per-module §7 "Stubs and departures from production" | every `docs/modules/<m>.md` §7 | Same. Code-companion Gaps rows point here. |
| Reconciled-on date in every doc preamble | `> Last reconciled with the repo on 2026-05-20.` | Same. Update whenever the file is touched. |
| Wiki concept → module → primary classes table | `architecture.md` concept→module table | Same shape, populated with object-storage concepts (consistent hashing, partitioned KV index, multipart upload state machine, lifecycle policies, erasure coding, etc.). |
| Boundary primitive table | `architecture.md` boundary table | Same shape; document the few value objects that cross module boundaries in object storage. |
| `gradle.properties` minimal (jvmargs, parallel, caching, JDK comment) | `gradle.properties` | Same five lines. |
| ASCII box-drawing characters for diagrams | `architecture.md` | Same. UTF-8 encoding is already pinned in compile options. |

---

## 9. Quick-start sequence for the object-storage repo

If applying the above from scratch:

1. **Decide the module list.** One module per concern; each concern has a primary wiki anchor (or names the wiki gap explicitly).
2. **Write `settings.gradle`** with phase-grouped includes.
3. **Write the root `build.gradle`** in three sections: `allprojects`, `subprojects`, then the per-module `project(':...')` dependency blocks.
4. **Create empty `src/main/java/...` and `src/test/java/...` per module.** Leave `build.gradle` *out* of each module directory.
5. **Author `docs/architecture.md`** with the two-axis mapping, ASCII plane diagram, ASCII dependency graph, concept→module table.
6. **Author `docs/modules/<m>.md` per module**, even if §3 is empty. Write §1, §2, §6, §7 from the design; §3, §4, §5 follow the code.
7. **Author ADRs** for the choices that need defending — module count, language version, in-memory substrates, real-vs-stub decisions for crypto/erasure/storage.
8. **Author `docs/code-companion.md`** once a blog post exists, with one section per blog part and the Gaps table at the bottom.
9. **Keep the reconciled-on dates current.** When a doc is touched, bump the date.

This is the minimum scaffolding to inherit DFS's discipline without inheriting its file-system specifics.

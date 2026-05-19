# Module Restructure Plan — distributed-object-storage

**Audience.** Another engineer (or agent) executing the migration from the current single-module Gradle build to a ~12-module library-style layout that mirrors the conventions of `/Users/hemantkgupta/code-all/distributed-file-system/`.

**Reference.** The sibling DFS repo uses a flat library-only structure: every module is `apply plugin: 'java-library'`, every per-module dependency block lives in the root `build.gradle` via `project(':...') { dependencies { ... } }`, no module has its own `build.gradle`, and there is no Spring runtime. We adapt that pattern but add **one** Spring Boot application module (`obj-gateway-app`) that bundles beans from the library modules into a runnable jar. Every other module stays a plain `java-library` and is free of `@SpringBootApplication`. Component-scan reaches into them via package convention.

---

## 1. Final Module List (12 modules)

All modules live under the root and use the package root `com.systemdesign.objectstorage.<module-slug>`. Cross-module dependencies are listed as Gradle configurations (`api` vs `implementation`) — `api` only when a transitive consumer needs the type on its own classpath (records exposed in public method signatures), `implementation` otherwise.

### 1.1 `obj-common`
- **Purpose.** Foundation types shared by every other module. No Spring, no JPA.
- **Existing sources moved in.**
  - `storage/ShardId.java`
  - `storage/StorageNodeHealth.java`
  - `placement/FailureDomain.java`
  - `placement/PlacementPlan.java`
  - `model/RepairPriority.java`
  - `model/RepairOutcome.java`
  - `model/ScrubOutcome.java`
- **Public API.** All of the above are records/enums and are exposed `api`.
- **Depends on.** Nothing.

### 1.2 `obj-erasure`
- **Purpose.** Self-contained GF(2^8) Reed-Solomon encode/decode. Mirrors `dfs-erasure` exactly.
- **Existing sources moved in.**
  - `erasure/ErasureCodingService.java`
  - test: `erasure/ErasureCodingServiceTest.java`
- **Public API.** `ErasureCodingService` (`encode`, `decode`). The class is currently `@Service` — it stays annotated; downstream component scan from `obj-gateway-app` picks it up. (Spring annotations on a `java-library` classpath are inert until a Spring context is started by the application module, so the library jar remains framework-agnostic at the link layer.)
- **Depends on.** Nothing.

### 1.3 `obj-routing`
- **Purpose.** Consistent hashing primitives.
- **Existing sources moved in.**
  - `routing/ConsistentHashRing.java`
  - test: `routing/ConsistentHashRingTest.java`
- **Public API.** `ConsistentHashRing` (constructor + `getNode`, `addNode`, `removeNode`).
- **Depends on.** Nothing.

### 1.4 `obj-placement`
- **Purpose.** Failure-domain-aware placement on top of the ring.
- **Existing sources moved in.**
  - `placement/PlacementPolicy.java`
  - `placement/TopologyAwarePlacementPolicy.java`
- **Public API.** `PlacementPolicy` interface, `TopologyAwarePlacementPolicy` impl. `FailureDomain` / `PlacementPlan` live in `obj-common`.
- **Depends on.** `api project(':obj-common')`, `implementation project(':obj-routing')`.

### 1.5 `obj-storage`
- **Purpose.** Storage-node abstraction and the local-filesystem implementation. Mirrors `dfs-storage`.
- **Existing sources moved in.**
  - `storage/StorageNode.java`
  - `storage/LocalFilesystemStorageNode.java`
- **Public API.** `StorageNode` interface; `LocalFilesystemStorageNode` impl.
- **Depends on.** `api project(':obj-common')` (`ShardId`, `StorageNodeHealth` show up in method signatures).

### 1.6 `obj-metadata`
- **Purpose.** Persistence layer: JPA entities, Flyway migrations, repositories for the canonical metadata catalog. Mirrors the "MDS" role in DFS (`dfs-mds`) but with a Postgres backing instead of in-memory.
- **Existing sources moved in.**
  - `model/ObjectMetadata.java`
  - `repository/MetadataRepository.java`
  - `src/main/resources/db/migration/V1__init.sql`
  - `src/main/resources/db/migration/V2__multipart_upload.sql`
- **Public API.** `ObjectMetadata` entity, `MetadataRepository` (`JpaRepository` proxy). Flyway picks up the migration resources from this module's classpath automatically — Flyway scans `classpath:db/migration` across all modules, so leaving them here works.
- **Depends on.** `api project(':obj-common')`, plus `spring-boot-starter-data-jpa` and `org.postgresql:postgresql` as `api` (so the gateway app inherits them transitively).

### 1.7 `obj-multipart`
- **Purpose.** S3-compatible multipart upload subsystem.
- **Existing sources moved in.**
  - `multipart/MultipartUpload.java`
  - `multipart/UploadPart.java`
  - `multipart/MultipartUploadRepository.java`
  - `multipart/UploadPartRepository.java`
  - `multipart/MultipartUploadController.java`
- **Public API.** The two entities and two repositories. The controller is a Spring `@RestController`, picked up by component scan from the app module.
- **Depends on.** `api project(':obj-metadata')` (multipart shares the Postgres datasource), `implementation project(':obj-erasure')`, `implementation project(':obj-storage')`, `implementation project(':obj-routing')`, `implementation project(':obj-placement')`, plus `spring-boot-starter-web` as `implementation` (controller needs it).

### 1.8 `obj-gateway`
- **Purpose.** PUT / GET / DELETE business logic — the read/write data path.
- **Existing sources moved in.**
  - `controller/StorageGatewayController.java`
  - `service/PutObjectService.java`
  - `service/GetObjectService.java`
  - `service/DeleteObjectService.java`
  - `service/NodeStorageService.java`
- **Public API.** Services exposed for the app context; controller exposed for component scan.
- **Depends on.** `implementation` on `obj-common`, `obj-erasure`, `obj-routing`, `obj-placement`, `obj-storage`, `obj-metadata`. Plus `spring-boot-starter-web` as `implementation`.

### 1.9 `obj-scrubber`
- **Purpose.** Background per-node shard verification daemon. Mirrors the scrubbing slice of `dfs-custodian`.
- **Existing sources moved in.**
  - `scrubber/ShardScrubber.java`
  - `scrubber/BackgroundScrubber.java`
  - `scrubber/ScrubBudget.java`
  - `scrubber/ScrubSummary.java`
  - `model/ScrubResult.java`
- **Public API.** `ShardScrubber`, `BackgroundScrubber`, `ScrubBudget`, `ScrubSummary`, `ScrubResult`.
- **Depends on.** `api project(':obj-common')` (`ScrubOutcome` lives there), `implementation project(':obj-storage')`, `implementation project(':obj-metadata')`.

### 1.10 `obj-repair`
- **Purpose.** Repair orchestration, task queue, lease store. Mirrors `dfs-custodian` + `dfs-lease`.
- **Existing sources moved in.**
  - `repair/RepairOrchestrator.java`
  - `repair/ErasureRepairOrchestrator.java`
  - `repair/RepairTaskQueue.java`
  - `repair/InMemoryRepairTaskQueue.java`
  - `repair/RepairLeaseStore.java`
  - `repair/InMemoryRepairLeaseStore.java`
  - `model/RepairTask.java`
  - `model/RepairResult.java`
  - `model/RepairBudget.java`
  - `model/RepairLease.java`
- **Public API.** All interfaces and result types are `api`; in-memory impls are `api` too so the gateway app can wire them as `@Bean`s without an `implementation` leak.
- **Depends on.** `api project(':obj-common')` (`RepairPriority`, `RepairOutcome`), `implementation project(':obj-erasure')`, `implementation project(':obj-storage')`, `implementation project(':obj-placement')`, `implementation project(':obj-metadata')`.

### 1.11 `obj-gc`
- **Purpose.** Garbage collection for orphaned shards, expired multipart uploads, and lifecycle deletions.
- **Existing sources moved in.**
  - `gc/GarbageCollector.java`
  - `gc/OrphanAndLifecycleGC.java`
  - `gc/GCPolicy.java`
  - `model/GCResult.java`
- **Public API.** `GarbageCollector`, `GCPolicy`, `GCResult`. Impl is also `api` so the app module can declare it as a bean.
- **Depends on.** `api project(':obj-common')`, `implementation project(':obj-storage')`, `implementation project(':obj-metadata')`, `implementation project(':obj-multipart')`.

### 1.12 `obj-gateway-app`
- **Purpose.** The **only** runnable module. Owns `DistributedObjectStorageApplication`, `application.yml`, the Spring Boot plugin, the bootJar, and the executable bundling.
- **Existing sources moved in.**
  - `DistributedObjectStorageApplication.java`
  - `src/main/resources/application.yml`
- **Public API.** None — it's the executable.
- **Depends on.** `implementation` on every other module: `obj-common`, `obj-erasure`, `obj-routing`, `obj-placement`, `obj-storage`, `obj-metadata`, `obj-multipart`, `obj-gateway`, `obj-scrubber`, `obj-repair`, `obj-gc`. Plus `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `org.flywaydb:flyway-core`, `org.postgresql:postgresql` directly.

**Module count.** 12 — matches the brief.

---

## 2. Per-Module `build.gradle` Skeletons

The DFS reference puts **all** per-module dependency wiring in the root `build.gradle` and gives modules no per-module `build.gradle` at all. We deviate from that exactly **once**: `obj-gateway-app` needs its own `build.gradle` because it applies the Spring Boot plugin, which is incompatible with the root `subprojects { apply plugin: 'java-library' }` block (Boot's plugin disables the plain library jar in favour of the executable bootJar). All other modules will have **no** `build.gradle` file at all; their dependencies are declared in the root via `project(':obj-foo') { dependencies { ... } }`.

### 2.1 `obj-gateway-app/build.gradle` (the only per-module file)

```groovy
plugins {
    id 'org.springframework.boot' version '3.2.4'
    id 'io.spring.dependency-management' version '1.1.4'
}

// Override the root's java-library plugin: bootJar wants the plain jar disabled.
jar { enabled = false }
bootJar { enabled = true; archiveClassifier = '' }

dependencies {
    implementation project(':obj-common')
    implementation project(':obj-erasure')
    implementation project(':obj-routing')
    implementation project(':obj-placement')
    implementation project(':obj-storage')
    implementation project(':obj-metadata')
    implementation project(':obj-multipart')
    implementation project(':obj-gateway')
    implementation project(':obj-scrubber')
    implementation project(':obj-repair')
    implementation project(':obj-gc')

    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.flywaydb:flyway-core'
    runtimeOnly  'org.postgresql:postgresql'

    compileOnly         'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.testcontainers:postgresql'
}
```

### 2.2 Every other module — no `build.gradle` file

`obj-common`, `obj-erasure`, `obj-routing`, `obj-placement`, `obj-storage`, `obj-metadata`, `obj-multipart`, `obj-gateway`, `obj-scrubber`, `obj-repair`, `obj-gc` get only `src/main/java/...` and `src/test/java/...` directories. Their dependencies come from the root build.

---

## 3. Root `settings.gradle`

```groovy
rootProject.name = 'distributed-object-storage'

// Foundation
include 'obj-common'

// Erasure + routing primitives
include 'obj-erasure'
include 'obj-routing'

// Placement + storage backend
include 'obj-placement'
include 'obj-storage'

// Persistence + S3-shaped APIs
include 'obj-metadata'
include 'obj-multipart'

// Data path
include 'obj-gateway'

// Background control plane
include 'obj-scrubber'
include 'obj-repair'
include 'obj-gc'

// Executable Spring Boot application
include 'obj-gateway-app'
```

---

## 4. Root `build.gradle`

```groovy
plugins {
    // Boot plugin is declared here only so dependency-management's BOM applies
    // to subprojects, but it is not *applied* at the root. Sub-modules opt in.
    id 'org.springframework.boot'              version '3.2.4' apply false
    id 'io.spring.dependency-management'       version '1.1.4' apply false
}

allprojects {
    group   = 'com.systemdesign'
    version = '0.1.0-SNAPSHOT'

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply plugin: 'java-library'
    apply plugin: 'io.spring.dependency-management'

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(17)
        }
    }

    dependencyManagement {
        imports {
            mavenBom 'org.springframework.boot:spring-boot-dependencies:3.2.4'
        }
    }

    dependencies {
        compileOnly         'org.projectlombok:lombok'
        annotationProcessor 'org.projectlombok:lombok'

        testImplementation platform('org.junit:junit-bom:5.10.2')
        testImplementation 'org.junit.jupiter:junit-jupiter'
        testImplementation 'org.assertj:assertj-core:3.25.3'
        testImplementation 'org.mockito:mockito-core'
        testImplementation 'org.mockito:mockito-junit-jupiter'
        testImplementation 'org.testcontainers:junit-jupiter'
        testRuntimeOnly    'org.junit.platform:junit-platform-launcher'
    }

    test {
        useJUnitPlatform()
    }

    compileJava.options.encoding     = 'UTF-8'
    compileTestJava.options.encoding = 'UTF-8'
}

// =====================================================================
// Per-module dependency wiring (mirrors the DFS root build.gradle style)
// =====================================================================

project(':obj-erasure')  { /* leaf */ }
project(':obj-routing')  { /* leaf */ }

project(':obj-placement') {
    dependencies {
        api            project(':obj-common')
        implementation project(':obj-routing')
    }
}

project(':obj-storage') {
    dependencies {
        api project(':obj-common')
    }
}

project(':obj-metadata') {
    dependencies {
        api            project(':obj-common')
        api            'org.springframework.boot:spring-boot-starter-data-jpa'
        api            'org.postgresql:postgresql'
        implementation 'org.flywaydb:flyway-core'
    }
}

project(':obj-multipart') {
    dependencies {
        api            project(':obj-metadata')
        implementation project(':obj-erasure')
        implementation project(':obj-storage')
        implementation project(':obj-routing')
        implementation project(':obj-placement')
        implementation 'org.springframework.boot:spring-boot-starter-web'
    }
}

project(':obj-gateway') {
    dependencies {
        implementation project(':obj-common')
        implementation project(':obj-erasure')
        implementation project(':obj-routing')
        implementation project(':obj-placement')
        implementation project(':obj-storage')
        implementation project(':obj-metadata')
        implementation 'org.springframework.boot:spring-boot-starter-web'
    }
}

project(':obj-scrubber') {
    dependencies {
        api            project(':obj-common')
        implementation project(':obj-storage')
        implementation project(':obj-metadata')
        implementation 'org.springframework.boot:spring-boot-starter'
    }
}

project(':obj-repair') {
    dependencies {
        api            project(':obj-common')
        implementation project(':obj-erasure')
        implementation project(':obj-storage')
        implementation project(':obj-placement')
        implementation project(':obj-metadata')
        implementation 'org.springframework.boot:spring-boot-starter'
    }
}

project(':obj-gc') {
    dependencies {
        api            project(':obj-common')
        implementation project(':obj-storage')
        implementation project(':obj-metadata')
        implementation project(':obj-multipart')
        implementation 'org.springframework.boot:spring-boot-starter'
    }
}

// obj-gateway-app intentionally has its own build.gradle (Spring Boot plugin).
```

---

## 5. Move Map

All commands run from `/Users/hemantkgupta/code-all/distributed-object-storage/`. Java packages **do not change** — every file already lives in `com.systemdesign.objectstorage.<subpkg>`, and we keep that as-is. Only the disk path under `src/main/java/...` changes.

```bash
# obj-common
mkdir -p obj-common/src/main/java/com/systemdesign/objectstorage/{storage,placement,model}
git mv src/main/java/com/systemdesign/objectstorage/storage/ShardId.java                  obj-common/src/main/java/com/systemdesign/objectstorage/storage/
git mv src/main/java/com/systemdesign/objectstorage/storage/StorageNodeHealth.java        obj-common/src/main/java/com/systemdesign/objectstorage/storage/
git mv src/main/java/com/systemdesign/objectstorage/placement/FailureDomain.java          obj-common/src/main/java/com/systemdesign/objectstorage/placement/
git mv src/main/java/com/systemdesign/objectstorage/placement/PlacementPlan.java          obj-common/src/main/java/com/systemdesign/objectstorage/placement/
git mv src/main/java/com/systemdesign/objectstorage/model/RepairPriority.java             obj-common/src/main/java/com/systemdesign/objectstorage/model/
git mv src/main/java/com/systemdesign/objectstorage/model/RepairOutcome.java              obj-common/src/main/java/com/systemdesign/objectstorage/model/
git mv src/main/java/com/systemdesign/objectstorage/model/ScrubOutcome.java               obj-common/src/main/java/com/systemdesign/objectstorage/model/

# obj-erasure
mkdir -p obj-erasure/src/main/java/com/systemdesign/objectstorage/erasure
mkdir -p obj-erasure/src/test/java/com/systemdesign/objectstorage/erasure
git mv src/main/java/com/systemdesign/objectstorage/erasure/ErasureCodingService.java     obj-erasure/src/main/java/com/systemdesign/objectstorage/erasure/
git mv src/test/java/com/systemdesign/objectstorage/erasure/ErasureCodingServiceTest.java obj-erasure/src/test/java/com/systemdesign/objectstorage/erasure/

# obj-routing
mkdir -p obj-routing/src/main/java/com/systemdesign/objectstorage/routing
mkdir -p obj-routing/src/test/java/com/systemdesign/objectstorage/routing
git mv src/main/java/com/systemdesign/objectstorage/routing/ConsistentHashRing.java       obj-routing/src/main/java/com/systemdesign/objectstorage/routing/
git mv src/test/java/com/systemdesign/objectstorage/routing/ConsistentHashRingTest.java   obj-routing/src/test/java/com/systemdesign/objectstorage/routing/

# obj-placement
mkdir -p obj-placement/src/main/java/com/systemdesign/objectstorage/placement
git mv src/main/java/com/systemdesign/objectstorage/placement/PlacementPolicy.java            obj-placement/src/main/java/com/systemdesign/objectstorage/placement/
git mv src/main/java/com/systemdesign/objectstorage/placement/TopologyAwarePlacementPolicy.java obj-placement/src/main/java/com/systemdesign/objectstorage/placement/

# obj-storage
mkdir -p obj-storage/src/main/java/com/systemdesign/objectstorage/storage
git mv src/main/java/com/systemdesign/objectstorage/storage/StorageNode.java                 obj-storage/src/main/java/com/systemdesign/objectstorage/storage/
git mv src/main/java/com/systemdesign/objectstorage/storage/LocalFilesystemStorageNode.java  obj-storage/src/main/java/com/systemdesign/objectstorage/storage/

# obj-metadata
mkdir -p obj-metadata/src/main/java/com/systemdesign/objectstorage/{model,repository}
mkdir -p obj-metadata/src/main/resources/db/migration
git mv src/main/java/com/systemdesign/objectstorage/model/ObjectMetadata.java        obj-metadata/src/main/java/com/systemdesign/objectstorage/model/
git mv src/main/java/com/systemdesign/objectstorage/repository/MetadataRepository.java obj-metadata/src/main/java/com/systemdesign/objectstorage/repository/
git mv src/main/resources/db/migration/V1__init.sql              obj-metadata/src/main/resources/db/migration/
git mv src/main/resources/db/migration/V2__multipart_upload.sql  obj-metadata/src/main/resources/db/migration/

# obj-multipart
mkdir -p obj-multipart/src/main/java/com/systemdesign/objectstorage/multipart
git mv src/main/java/com/systemdesign/objectstorage/multipart/*.java                    obj-multipart/src/main/java/com/systemdesign/objectstorage/multipart/

# obj-gateway
mkdir -p obj-gateway/src/main/java/com/systemdesign/objectstorage/{controller,service}
git mv src/main/java/com/systemdesign/objectstorage/controller/StorageGatewayController.java obj-gateway/src/main/java/com/systemdesign/objectstorage/controller/
git mv src/main/java/com/systemdesign/objectstorage/service/PutObjectService.java        obj-gateway/src/main/java/com/systemdesign/objectstorage/service/
git mv src/main/java/com/systemdesign/objectstorage/service/GetObjectService.java        obj-gateway/src/main/java/com/systemdesign/objectstorage/service/
git mv src/main/java/com/systemdesign/objectstorage/service/DeleteObjectService.java     obj-gateway/src/main/java/com/systemdesign/objectstorage/service/
git mv src/main/java/com/systemdesign/objectstorage/service/NodeStorageService.java      obj-gateway/src/main/java/com/systemdesign/objectstorage/service/

# obj-scrubber
mkdir -p obj-scrubber/src/main/java/com/systemdesign/objectstorage/{scrubber,model}
git mv src/main/java/com/systemdesign/objectstorage/scrubber/*.java obj-scrubber/src/main/java/com/systemdesign/objectstorage/scrubber/
git mv src/main/java/com/systemdesign/objectstorage/model/ScrubResult.java obj-scrubber/src/main/java/com/systemdesign/objectstorage/model/

# obj-repair
mkdir -p obj-repair/src/main/java/com/systemdesign/objectstorage/{repair,model}
git mv src/main/java/com/systemdesign/objectstorage/repair/*.java obj-repair/src/main/java/com/systemdesign/objectstorage/repair/
git mv src/main/java/com/systemdesign/objectstorage/model/RepairTask.java   obj-repair/src/main/java/com/systemdesign/objectstorage/model/
git mv src/main/java/com/systemdesign/objectstorage/model/RepairResult.java obj-repair/src/main/java/com/systemdesign/objectstorage/model/
git mv src/main/java/com/systemdesign/objectstorage/model/RepairBudget.java obj-repair/src/main/java/com/systemdesign/objectstorage/model/
git mv src/main/java/com/systemdesign/objectstorage/model/RepairLease.java  obj-repair/src/main/java/com/systemdesign/objectstorage/model/

# obj-gc
mkdir -p obj-gc/src/main/java/com/systemdesign/objectstorage/{gc,model}
git mv src/main/java/com/systemdesign/objectstorage/gc/*.java       obj-gc/src/main/java/com/systemdesign/objectstorage/gc/
git mv src/main/java/com/systemdesign/objectstorage/model/GCResult.java obj-gc/src/main/java/com/systemdesign/objectstorage/model/

# obj-gateway-app
mkdir -p obj-gateway-app/src/main/java/com/systemdesign/objectstorage
mkdir -p obj-gateway-app/src/main/resources
git mv src/main/java/com/systemdesign/objectstorage/DistributedObjectStorageApplication.java obj-gateway-app/src/main/java/com/systemdesign/objectstorage/
git mv src/main/resources/application.yml obj-gateway-app/src/main/resources/

# Delete the now-empty original src/ tree
git rm -r src
```

**Package renames.** None. Every file's `package com.systemdesign.objectstorage.<subpkg>;` declaration is unchanged.

---

## 6. Application Bootstrapping

`DistributedObjectStorageApplication.java` moves to `obj-gateway-app/src/main/java/com/systemdesign/objectstorage/`. Its package stays `com.systemdesign.objectstorage` — the shared parent package of every library module.

The class needs three additions:

```java
@SpringBootApplication
@EntityScan(basePackages = "com.systemdesign.objectstorage")
@EnableJpaRepositories(basePackages = "com.systemdesign.objectstorage")
public class DistributedObjectStorageApplication { … }
```

`@EntityScan` and `@EnableJpaRepositories` are necessary because Spring Data JPA's auto-configuration only scans the **application module's** classpath by default.

`application.yml` is owned by `obj-gateway-app/src/main/resources/`. Library modules ship **no** YAML. Flyway's `classpath:db/migration` setting finds the SQL files in `obj-metadata`'s resources because Boot's bootJar flattens every dependency module's `src/main/resources/` into the same fat-jar classpath root.

The bootJar produced by `./gradlew :obj-gateway-app:bootJar` is the deployable artifact; `./gradlew :obj-gateway-app:bootRun` is the dev-loop command.

---

## 7. Risks & Migration Order

Execute in this order. Run `./gradlew build` after every checkpoint — green builds are the safety net.

1. **Checkpoint 0 — preserve baseline.** Run `./gradlew build` on `main` and record passing tests. Tag the commit `pre-modularize`.
2. **Checkpoint 1 — root scaffolding.** Edit `settings.gradle` and root `build.gradle` to the contents in sections 3–4 above, but **without** any `include` lines yet.
3. **Checkpoint 2 — extract `obj-common`.** Add `include 'obj-common'`, move the leaf types listed in 1.1.
4. **Checkpoint 3 — extract leaves with no Spring beans.** `obj-erasure`, `obj-routing`.
5. **Checkpoint 4 — extract storage & placement.** `obj-storage`, then `obj-placement`.
6. **Checkpoint 5 — extract `obj-metadata` (highest-risk step).** Add `@EntityScan` / `@EnableJpaRepositories`. Verify Flyway scripts still resolve.
7. **Checkpoint 6 — extract `obj-multipart`.**
8. **Checkpoint 7 — extract `obj-gateway`.**
9. **Checkpoint 8 — create `obj-gateway-app`.** Move `DistributedObjectStorageApplication` and `application.yml`. Add `obj-gateway-app/build.gradle`. Delete `src/` at the repo root.
10. **Checkpoint 9 — extract `obj-scrubber`, `obj-repair`, `obj-gc`.**
11. **Checkpoint 10 — cleanup.** Run `./gradlew dependencies` on the app module. Run `./gradlew :obj-gateway-app:bootJar` and smoke-test.

**Other risks worth flagging:**

- **Component scan blind spots.** Grep before each checkpoint: `grep -rh "^package " <module>/src/main/java | sort -u` should only print packages starting with `com.systemdesign.objectstorage`.
- **Lombok in library modules.** The root `subprojects` block applies Lombok everywhere; the `annotationProcessor` declaration must be in the subprojects block.
- **Test-only dependencies.** `spring-boot-starter-test` brings JUnit, Mockito, AssertJ, and Spring Test in one go for the app module. Library modules use just `junit-jupiter` + `mockito-core` + `assertj-core` — keep them light.
- **Circular dependency trap.** `obj-multipart` and `obj-gateway` both touch `MetadataRepository`. After split, both depend on `obj-metadata`, but neither depends on the other — verify with `./gradlew :obj-multipart:dependencies`.
- **DFS doesn't have Spring, so we can't lift its root build verbatim.** The deviations (dependency-management BOM in `subprojects`, dedicated `build.gradle` for `obj-gateway-app`) are necessary deliberate adaptations.

---

### Critical Files for Implementation

- `/Users/hemantkgupta/code-all/distributed-object-storage/settings.gradle`
- `/Users/hemantkgupta/code-all/distributed-object-storage/build.gradle`
- `/Users/hemantkgupta/code-all/distributed-object-storage/obj-gateway-app/build.gradle` (new — only per-module build file)
- `/Users/hemantkgupta/code-all/distributed-object-storage/obj-gateway-app/src/main/java/com/systemdesign/objectstorage/DistributedObjectStorageApplication.java` (needs `@EntityScan` + `@EnableJpaRepositories`)
- `/Users/hemantkgupta/code-all/distributed-object-storage/obj-metadata/src/main/resources/db/migration/V1__init.sql` (canary for "do Flyway scripts still ship in the fat-jar classpath after the split")

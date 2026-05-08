# Jenkins Job DSL Onboarding Example

## The Three-Repo Architecture

This Jenkins setup use three separate Git repositories. Each has a single,
well-defined responsibility. This separation means that changing one thing (e.g. how a
pipeline works) does not require touching the others.

```
jenkins-dsl-example/
├── jenkinsjobdsl/          ← Repo 1: Job definitions (what jobs exist in Jenkins)
├── pipeline-library/       ← Repo 2: Shared pipeline code (how those jobs run)
└── my-app/                 ← Repo 3: Application source (the thing being built)
```

### Repo 1 — `jenkinsjobdsl/` (Job DSL definitions)

**Role:** The single source of truth for *which* Jenkins jobs exist and how they are
configured. Nobody creates jobs by hand in the Jenkins UI — every job is declared here
as code, so the setup is reproducible and version-controlled.

Key files:
- `AllSeedJobGenerator.groovy` — master list; tells Jenkins which seed jobs to create.
- `hello-world/SeedJob.groovy` — one seed job per tool; creates the actual pipeline jobs.
- `hello-world/jobs_config.yaml` — lists the repos the seed job should scan.

### Repo 2 — `pipeline-library/` (Jenkins Shared Library)

**Role:** Contains reusable Groovy pipeline code that many application repos can share.
Instead of copy-pasting pipeline stages into every `Jenkinsfile`, each app just calls a
single function from this library. Bug fixes and improvements to the pipeline propagate
to every app automatically.

Key files:
- `vars/HelloWorldPipeline.groovy` — the reusable pipeline function.
- `workflows/hello-world/Jenkinsfile` — a standalone reference Jenkinsfile (no library).

### Repo 3 — `my-app/` (Application repo)

**Role:** The actual source code being built. This repo only needs to know *what* pipeline
to run, not *how* it works — that knowledge lives in the shared library.

Key files:
- `Jenkins/Jenkinsfile.hello` — tiny entry point; calls the shared library pipeline.

---

## How a Build Actually Happens — Step-by-Step Flow

```
AllSeedJobGenerator.groovy
       │
       │  1. Jenkins runs this script as a freestyle job.
       │     It reads the ListSeedJobs list and creates one
       │     "JobDSL_SeedJob_<Name>" job per entry.
       ▼
JobDSL_SeedJob_HelloWorld_Multibranch  (a Jenkins job, created by step 1)
       │
       │  2. This seed job clones the jenkinsjobdsl repo
       │     and runs hello-world/SeedJob.groovy using the
       │     Jenkins Job DSL plugin.
       ▼
hello-world/SeedJob.groovy
       │
       │  3. The seed job reads jobs_config.yaml to get a list
       │     of application repos. For each repo it calls
       │     multibranchPipelineJob(...), which creates a new
       │     Jenkins job that monitors that repo.
       ▼
HelloWorld/MyApp  (a multibranch pipeline job, created by step 3)
       │
       │  4. The multibranch job scans the my-app repo for
       │     branches and pull requests. For every branch that
       │     contains the file at scriptPath (Jenkinsfile.hello),
       │     Jenkins creates a child build and runs it.
       ▼
my-app/Jenkins/Jenkinsfile.hello
       │
       │  5. Jenkins executes this file on the agent. It loads
       │     the shared library (pipeline-library repo) and
       │     calls HelloWorldPipeline { ... } with configuration.
       ▼
pipeline-library/vars/HelloWorldPipeline.groovy
       │
       │  6. The shared library function receives the config,
       │     applies defaults, and runs the full declarative
       │     pipeline (agent, stages, post actions, etc.).
       ▼
       Build runs on the agent, results appear in Jenkins UI.
```

---

## How It All Connects — Numbered Walkthrough

1. **A DevOps engineer adds a new entry to `AllSeedJobGenerator.groovy`.**
   This is the only place you need to touch to introduce a brand-new tool's seed job.

2. **The AllSeedJobGenerator freestyle job is triggered** (manually or on a schedule).
   It executes `AllSeedJobGenerator.groovy` via the Job DSL plugin and creates/updates
   the `JobDSL_SeedJob_HelloWorld_Multibranch` freestyle job in Jenkins.

3. **The seed job (`JobDSL_SeedJob_HelloWorld_Multibranch`) is triggered.**
   It clones the `jenkinsjobdsl` repo into its workspace, then runs
   `hello-world/SeedJob.groovy` using the Job DSL plugin.

4. **`SeedJob.groovy` reads `jobs_config.yaml`** from the same workspace.
   The YAML lists every application repo that should be scanned. The seed job calls
   `multibranchPipelineJob(...)` for each entry, which creates (or updates) the
   corresponding Jenkins job — e.g. `HelloWorld/MyApp`.

5. **The multibranch pipeline job scans the application repo** (e.g. `my-app`).
   For every branch or pull request that contains the `Jenkinsfile` at the path given by
   `scriptPath`, Jenkins creates a child job and queues a build.

6. **Jenkins checks out the branch and executes the `Jenkinsfile`.**
   In our case that is `my-app/Jenkins/Jenkinsfile.hello`. This file loads the shared
   library with `@Library('pipeline-library@main') _` and then calls
   `HelloWorldPipeline { agent_label = 'built-in' }`.

7. **`HelloWorldPipeline.groovy` (in the shared library) runs the actual pipeline.**
   It receives the configuration closure, merges it with defaults, and executes the
   declarative pipeline stages on the requested agent.

8. **Results appear in the Jenkins UI** under `HelloWorld/MyApp/<branch-name>`.
   Build status is also posted back to the pull request in Bitbucket (or GitHub).

---

## Adding a New Tool / Pipeline

Follow these steps in order:

### Step 1 — Add an entry to `AllSeedJobGenerator.groovy`

```groovy
ListSeedJobs = [
    [ Name: "HelloWorld_Multibranch", Scripts: "hello-world/SeedJob.groovy", enabled: true ],
    [ Name: "MyNewTool_Multibranch",  Scripts: "my-new-tool/SeedJob.groovy", enabled: true ],  // ← new
]
```

### Step 2 — Create `jenkinsjobdsl/my-new-tool/jobs_config.yaml`

List every application repo that should be built with this tool.

### Step 3 — Create `jenkinsjobdsl/my-new-tool/SeedJob.groovy`

Copy `hello-world/SeedJob.groovy`, change the folder/view names, and adjust any
tool-specific traits (e.g. different branch filter, different Jenkinsfile path).

### Step 4 — Create the shared library var (if needed)

Add `pipeline-library/vars/MyNewToolPipeline.groovy` with the pipeline logic.

### Step 5 — Add a `Jenkinsfile` to the application repo

Point it at the new shared library var. Example:

```groovy
@Library('pipeline-library@main') _
MyNewToolPipeline {
    agent_label = 'linux'
}
```

### Step 6 — Add the repo to `jobs_config.yaml`

The next time the seed job runs, Jenkins will pick up the new repo automatically.

---

## Key Plugin Dependencies

| Plugin | Why it's needed |
|--------|----------------|
| Job DSL | Executes `.groovy` DSL scripts to create/update jobs |
| Pipeline: Multibranch | Powers `multibranchPipelineJob` — scans branches/PRs |
| Bitbucket Branch Source | The `bitbucket { ... }` block in `SeedJob.groovy` |
| Pipeline: Shared Groovy Libraries | Enables `@Library(...)` in Jenkinsfiles |
| SnakeYAML | Parses `jobs_config.yaml` inside Groovy DSL scripts |

---

## Glossary

| Term | Meaning |
|------|---------|
| **Seed job** | A Jenkins job whose only purpose is to create other Jenkins jobs via DSL |
| **Job DSL** | A Groovy-based DSL for defining Jenkins jobs as code |
| **Multibranch pipeline** | A Jenkins job type that auto-discovers branches/PRs in a repo |
| **Shared library** | A Groovy library in a separate Git repo, loaded into Jenkinsfiles with `@Library` |
| **`vars/`** | Directory in a shared library whose `.groovy` files become globally callable functions |
| **`scriptPath`** | The path (relative to the repo root) of the `Jenkinsfile` Jenkins should run |
| **`SEED_JOB`** | A built-in variable available in Job DSL scripts; represents the running seed job |

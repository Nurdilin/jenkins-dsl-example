// =============================================================================
// hello-world/SeedJob.groovy
// =============================================================================
//
// PURPOSE:
//   This is the Job DSL script executed by the "JobDSL_SeedJob_HelloWorld_Multibranch"
//   freestyle job (which was created by AllSeedJobGenerator.groovy).
//
//   Its job is to read jobs_config.yaml and create (or update) one
//   multibranchPipelineJob in Jenkins for every repo listed there.
//
// HOW IT RUNS:
//   1. The seed job clones the jenkinsjobdsl repo into its workspace.
//   2. Jenkins then runs this Groovy script via the Job DSL plugin.
//   3. The script reads the YAML file (which was cloned alongside it),
//      loops over each repo entry, and calls multibranchPipelineJob(...).
//   4. Jenkins creates/updates those jobs in the UI immediately.
//
// REAL VS EXAMPLE:
//   In the real setup there are additional imports for internal helper classes
//   (common settings, notification configs, etc.). Those are omitted here for
//   clarity. The structure below reflects the real pattern faithfully.
//
// =============================================================================

import org.yaml.snakeyaml.Yaml
// ↑ SnakeYAML is bundled with Jenkins — no extra plugin needed.
//   It parses YAML into a standard Groovy Map/List structure.

// -----------------------------------------------------------------------------
// SEED_JOB — the built-in variable that represents the current seed job itself.
//
// WHY DO WE NEED IT?
//   When the Job DSL plugin runs this script, it doesn't automatically know
//   where the script's supporting files (like jobs_config.yaml) are located.
//   SEED_JOB.getWorkspace() returns the path to the seed job's workspace on disk —
//   the directory where the jenkinsjobdsl repo was cloned. This lets us read
//   files from the repo as if they were local files.
// -----------------------------------------------------------------------------
def workDir = SEED_JOB.getWorkspace()

// Load and parse the YAML config file that sits next to this script.
// The result is a Groovy Map mirroring the YAML structure.
def configs = new Yaml().load(("${workDir}/hello-world/jobs_config.yaml" as File).text)

// -----------------------------------------------------------------------------
// Create a folder in the Jenkins UI to keep HelloWorld jobs together.
//
// WHY A FOLDER?
//   Without folders, every multibranch job would appear at the top level of
//   Jenkins alongside hundreds of other jobs. Folders provide namespacing:
//   our jobs live at HelloWorld/<name>, clearly separated from other tools.
// -----------------------------------------------------------------------------
folder('HelloWorld') {
    description('Hello World example pipeline jobs. Created and managed by hello-world/SeedJob.groovy.')
}

// -----------------------------------------------------------------------------
// Main loop — create one multibranchPipelineJob per repo in the YAML.
// -----------------------------------------------------------------------------
configs.jobs.each { job ->

    // -------------------------------------------------------------------------
    // multibranchPipelineJob — the heart of the CI setup.
    //
    // WHAT IS A MULTIBRANCH PIPELINE JOB?
    //   A normal pipeline job builds a single, fixed branch. A multibranch
    //   pipeline job *automatically discovers* every branch and pull request in
    //   the configured repository. For each one that contains the Jenkinsfile
    //   (at scriptPath), Jenkins creates a child build and runs it.
    //
    // WHY USE IT?
    //   - Pull requests get automatically built and checked without any manual
    //     job setup per PR.
    //   - New branches are picked up automatically within minutes.
    //   - Deleted/merged branches have their Jenkins jobs cleaned up automatically.
    // -------------------------------------------------------------------------
    multibranchPipelineJob("HelloWorld/${job.name}") {
        displayName(job.name)

        branchSources {
            branchSource {
                source {
                    // ---------------------------------------------------------
                    // Bitbucket SCM source configuration.
                    //
                    // This tells Jenkins WHERE to look for branches/PRs.
                    // For GitHub, replace 'bitbucket { ... }' with 'github { ... }'
                    // and adjust the trait names accordingly.
                    // ---------------------------------------------------------
                    bitbucket {
                        // id: A stable UUID for this job. Jenkins uses it to
                        // track the job if it gets renamed. Must match the uuid
                        // in jobs_config.yaml. Do NOT change this after creation.
                        id(job.uuid)

                        // repoOwner: The Bitbucket project key or GitHub org name.
                        repoOwner(job.repo.owner)

                        // repository: The repo slug (URL-safe name of the repo).
                        repository(job.repo.name)

                        // credentialsId: The Jenkins credential Jenkins uses to
                        // authenticate against Bitbucket when scanning branches.
                        credentialsId(job.repo.credentialsId)

                        // serverUrl: The base URL of your SCM server.
                        // For cloud Bitbucket this is "https://bitbucket.org".
                        serverUrl(configs.repoUrl)

                        traits {
                            // -------------------------------------------------
                            // Post build status back to Bitbucket.
                            //
                            // WHY: This makes a pass/fail badge appear on every
                            // pull request in Bitbucket, so reviewers can see
                            // the build result without leaving the PR page.
                            // -------------------------------------------------
                            bitbucketBuildStatusNotifications {
                                // useReadableNotificationIds: uses human-readable
                                // stage names (e.g. "Build > Test") rather than
                                // opaque UUIDs in the Bitbucket status API.
                                useReadableNotificationIds(true)
                            }

                            // -------------------------------------------------
                            // Discover pull requests.
                            //
                            // strategyId(2) = "The current pull request revision
                            // merged with the target branch". This is the most
                            // useful mode because it tests what the code will
                            // look like AFTER merging, not just the branch tip.
                            //
                            // Other values:
                            //   1 = PR head only (faster, less safe)
                            //   3 = both (runs the job twice, costly)
                            // -------------------------------------------------
                            bitbucketPullRequestDiscovery {
                                strategyId(2)
                            }

                            // -------------------------------------------------
                            // Shallow clone options.
                            //
                            // WHY SHALLOW?
                            //   Most pipelines only need the latest commit —
                            //   they don't need the full git history. A shallow
                            //   clone (depth=1) is significantly faster to check
                            //   out, especially on large repositories.
                            //
                            // WHEN TO SET shallow(false):
                            //   - Secret scanning tools that diff against HEAD~1
                            //   - Tools that need git tags (e.g. version bumping)
                            //   - Any pipeline that runs `git log` or `git blame`
                            // -------------------------------------------------
                            cloneOption {
                                extension {
                                    shallow(true)   // don't fetch full history
                                    noTags(true)    // skip fetching git tags
                                    depth(1)        // only fetch the latest commit
                                    honorRefspec(true) // respect the refspec exactly
                                    reference(null) // no local reference repo
                                    timeout(null)   // use the Jenkins default timeout
                                }
                            }

                            // -------------------------------------------------
                            // Branch/tag filter.
                            //
                            // includes('*')  — build all branches
                            // excludes('')   — exclude nothing
                            // tagIncludes('') — don't build any tags
                            // tagExcludes('*') — exclude all tags
                            //
                            // To limit to specific branches, change includes to
                            // e.g. 'main develop feature/*' (space-separated).
                            // -------------------------------------------------
                            headWildcardFilterWithPRFromOrigin {
                                includes('*')
                                excludes('')
                                tagIncludes('')
                                tagExcludes('*')
                            }
                        }
                    }
                }
            }
        }

        // -----------------------------------------------------------------------
        // scriptPath — which file in the repo Jenkins should treat as the pipeline.
        //
        // WHY IS THIS IMPORTANT?
        //   Jenkins doesn't just look for a file called "Jenkinsfile" by default.
        //   scriptPath lets you put the Jenkinsfile anywhere and name it anything.
        //   This is useful when a repo has multiple Jenkinsfiles for different
        //   purposes (e.g. Jenkinsfile.hello, Jenkinsfile.release, Jenkinsfile.scan).
        //
        //   The value comes from jobs_config.yaml so each repo can point at a
        //   different Jenkinsfile without changing this DSL script.
        // -----------------------------------------------------------------------
        factory {
            workflowBranchProjectFactory {
                scriptPath(job.jenkinsFile)
            }
        }

        // -----------------------------------------------------------------------
        // Orphaned item strategy — what to do with jobs for deleted branches.
        //
        // WHY:
        //   When a branch is deleted in Git, its Jenkins child job becomes
        //   "orphaned". By default Jenkins keeps them forever, wasting disk space.
        //   numToKeep(5) means: keep the last 5 builds from any deleted branch,
        //   then discard. This balances auditability with disk usage.
        // -----------------------------------------------------------------------
        orphanedItemStrategy {
            discardOldItems {
                numToKeep(5)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// listView — creates a Jenkins UI view that groups related jobs.
//
// WHY:
//   A Jenkins instance can have hundreds of jobs. Without views, finding the
//   HelloWorld jobs means scrolling through everything. This view filters by
//   a regex so all HelloWorld/* jobs appear in one place.
// -----------------------------------------------------------------------------
listView('HelloWorld') {
    description('All Hello World pipeline jobs — managed by hello-world/SeedJob.groovy')

    // Match any job whose path starts with "HelloWorld/" followed by
    // letters, digits, hyphens, or underscores.
    jobs {
        regex(/HelloWorld\/[A-Za-z0-9\-_]+/)
    }

    // recurse(true) means the view also shows jobs inside sub-folders.
    recurse(true)

    // Columns shown in the view table in the Jenkins UI.
    columns {
        status()        // coloured ball: blue=passing, red=failing, grey=not built
        weather()       // trend icon: recent pass/fail ratio
        buildButton()   // one-click trigger button
        name()          // job name (clickable link)
        lastSuccess()   // timestamp of the last successful build
        lastFailure()   // timestamp of the last failed build
        lastDuration()  // how long the last build took
    }
}

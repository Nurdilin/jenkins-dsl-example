// =============================================================================
// AllSeedJobGenerator.groovy
// =============================================================================
//
// PURPOSE:
//   This script is the master entrypoint for the entire Jenkins job setup.
//   It is executed by a single, manually-maintained Jenkins freestyle job called
//   "AllSeedJobGenerator". That job runs this script via the Job DSL plugin.
//
// WHAT IT DOES:
//   It reads the ListSeedJobs list below and creates one Jenkins freestyle job
//   (called a "seed job") per entry. Each seed job, when triggered, will clone
//   this repository and run its corresponding DSL script — which in turn creates
//   the real pipeline jobs (multibranchPipelineJob, etc.) in Jenkins.
//
// WHY THIS TWO-LEVEL STRUCTURE?
//   - The AllSeedJobGenerator is the bootstrap. You only ever need one.
//   - Each tool/product gets its own seed job. This keeps them independent:
//     running "HelloWorld_Multibranch" only affects HelloWorld jobs, not others.
//   - If a seed job breaks (e.g. bad YAML), it doesn't take down every other tool.
//
// HOW TO ADD A NEW TOOL:
//   1. Create a new subdirectory in this repo, e.g. my-new-tool/
//   2. Add a SeedJob.groovy and jobs_config.yaml inside it.
//   3. Add an entry to ListSeedJobs below.
//   4. Trigger (or wait for) the AllSeedJobGenerator job to run.
//      Jenkins will create a new "JobDSL_SeedJob_MyNewTool_Multibranch" job.
//   5. Trigger that new seed job once to create the actual pipeline jobs.
//
// NOTE ON IMPORTS:
//   In the real setup, helper classes (CommonJobSettings, etc.) are imported
//   here to apply standard SCM config, credentials, labels, etc. to every job.
//   Those are omitted in this example to keep things readable, but the SCM
//   block below shows what they would configure.
//
// =============================================================================

// Each map in this list describes one seed job to create.
// - Name:    Used in the Jenkins job name ("JobDSL_SeedJob_<Name>")
// - Scripts: Path (relative to the root of this repo) to the DSL script
//            that this seed job will execute.
// - enabled: If false, the entry is skipped. Useful for temporarily disabling
//            a tool without deleting its config.
ListSeedJobs = [
    [ Name: "HelloWorld_Multibranch", Scripts: "hello-world/SeedJob.groovy", enabled: true ],
    // ↑ This entry will produce a job called "JobDSL_SeedJob_HelloWorld_Multibranch".
    //   When that job runs, it executes hello-world/SeedJob.groovy, which reads
    //   hello-world/jobs_config.yaml and creates a multibranchPipelineJob for each
    //   repo listed there.

    // Add more tools here following the same pattern, for example:
    // [ Name: "Cppcheck_Multibranch",  Scripts: "cppcheck/SeedJob.groovy",  enabled: true ],
    // [ Name: "SonarQube_Multibranch", Scripts: "sonarqube/SeedJob.groovy", enabled: false ],
]

// Iterate over the list and create one seed job per enabled entry.
ListSeedJobs.each { seedJobConfig ->
    // Skip disabled entries entirely — don't even create the job.
    if (!seedJobConfig.enabled) return

    String name    = seedJobConfig.Name
    String scripts = seedJobConfig.Scripts

    // job() creates a Jenkins freestyle job.
    // The name is prefixed with "JobDSL_SeedJob_" so it's immediately obvious
    // in the Jenkins UI that these are infrastructure jobs, not application builds.
    job("JobDSL_SeedJob_${name}") {

        // -----------------------------------------------------------------------
        // SCM — where to clone this (jenkinsjobdsl) repo from.
        //
        // WHY: The seed job needs the DSL script files on disk so it can read
        // and execute them. Cloning this repo into the workspace achieves that.
        // -----------------------------------------------------------------------
        scm {
            git {
                remote {
                    // Replace this URL with your organisation's actual repo URL.
                    url('https://git.example.com/scm/devops/jenkinsjobdsl.git')
                    // 'CI_BUILD_CREDENTIALS' is the ID of a Jenkins credential
                    // (username+password or SSH key) that has read access to the repo.
                    credentials('CI_BUILD_CREDENTIALS')
                }
                // Always build from the main branch. In the real setup this might
                // come from a CommonJobSettings helper that standardises it.
                branch('*/main')
            }
        }

        // -----------------------------------------------------------------------
        // Steps — what the seed job actually does when it runs.
        //
        // The 'dsl' step is provided by the Job DSL plugin. It reads the Groovy
        // script at 'scripts' (relative to the workspace root, which is the root
        // of the cloned jenkinsjobdsl repo) and executes it as Job DSL.
        // -----------------------------------------------------------------------
        steps {
            dsl {
                // external() means "read the DSL from a file in the workspace"
                // rather than from an inline string. This keeps everything in
                // version control instead of pasted into the Jenkins UI.
                external(scripts)

                // ignoreExisting(false) means "if a job already exists, update it
                // with whatever the DSL says now". Set to true to skip existing jobs
                // (useful during initial testing to avoid accidentally overwriting).
                ignoreExisting(false)
            }
        }
    }
}

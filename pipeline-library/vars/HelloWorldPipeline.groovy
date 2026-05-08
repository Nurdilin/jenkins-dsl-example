#!groovy
// =============================================================================
// pipeline-library/vars/HelloWorldPipeline.groovy
// =============================================================================
//
// PURPOSE:
//   This is a "pipeline var" — a reusable pipeline definition that lives in the
//   Jenkins Shared Library. Application repos call it from their Jenkinsfiles
//   instead of defining the pipeline stages themselves.
//
// WHERE DOES IT LIVE?
//   The vars/ directory of the shared library repo (pipeline-library).
//   Jenkins requires that reusable pipeline functions live in vars/ and that
//   the filename matches the function name callers use (HelloWorldPipeline.groovy
//   → HelloWorldPipeline { ... }).
//
// HOW IS IT LOADED?
//   Jenkins must be configured to know about this shared library. In Jenkins:
//     Manage Jenkins → Configure System → Global Pipeline Libraries
//     → Add: name="pipeline-library", default version="main", SCM=Git, URL=<repo>
//
//   Then in a Jenkinsfile:
//     @Library('pipeline-library@main') _
//     HelloWorldPipeline {
//         agent_label = 'linux'
//     }
//
// WHY SHARED LIBRARIES?
//   Without a shared library, every app repo must contain the full pipeline
//   definition. When the pipeline needs to change (e.g. add a new security scan
//   stage), you'd have to open PRs in every single app repo. With a shared
//   library, you change one file here and every app picks it up automatically
//   on their next build.
//
// =============================================================================

// def call(body) is the conventional signature for a Jenkins shared library var.
//
// WHY 'call'?
//   When you write  HelloWorldPipeline { ... }  in a Jenkinsfile, Groovy
//   interprets this as calling the object HelloWorldPipeline as if it were a
//   function, i.e. HelloWorldPipeline.call({ ... }). Jenkins looks for a
//   method named 'call' on the script object — that's this method.
//
// WHY 'body'?
//   'body' is the closure (the { ... } block) that the caller passes in.
//   It contains the configuration values the caller wants to override.
//   We use it below to populate a config Map.
def call(body) {

    // -------------------------------------------------------------------------
    // Configuration pattern — DELEGATE_FIRST closure delegation.
    //
    // This pattern allows callers to write:
    //   HelloWorldPipeline {
    //       agent_label = 'my-linux-agent'
    //   }
    //
    // HOW IT WORKS:
    //   1. We create an empty Map called 'config'.
    //   2. We set body.delegate = config, so any variable assignments inside
    //      the closure are stored as keys in 'config' rather than in the
    //      caller's local scope.
    //   3. DELEGATE_FIRST means: when a variable name is encountered inside
    //      the closure, look in 'delegate' (our config Map) FIRST, before
    //      looking in the closure's own scope. This is what makes plain
    //      assignments like  agent_label = 'linux'  work without needing
    //      'config.agent_label = ...' syntax.
    //   4. We call body() to execute the closure, which populates config.
    // -------------------------------------------------------------------------
    Map config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    // Read configuration values. The Elvis operator (?:) provides a default
    // if the caller didn't set the value. This makes every config key optional.
    String agentLabel = config.agent_label ?: 'built-in'
    // ↑ 'built-in' is the Jenkins controller node. In production you'd default
    //   to a specific agent label that matches your infrastructure.

    // -------------------------------------------------------------------------
    // The actual pipeline.
    //
    // This is a standard Jenkins declarative pipeline. Everything from here
    // down is exactly what you'd write in a Jenkinsfile — the only difference
    // is that it's defined in a shared library so many repos can reuse it.
    // -------------------------------------------------------------------------
    pipeline {

        // agent: which Jenkins node to run this build on.
        // The label is taken from the caller's config (or defaults to 'built-in').
        // In the real setup this would be a label like 'linux', 'windows', or
        // a specific Docker image label.
        agent {
            node { label agentLabel }
        }

        options {
            // Prevent two builds of the same branch from running simultaneously.
            // WHY: Concurrent builds can cause race conditions (e.g. writing to
            // the same output directory, or deploying at the same time).
            disableConcurrentBuilds()

            // Fail the build if it runs longer than 10 minutes.
            // WHY: Hangs are hard to detect without a timeout. A stuck build
            // blocks the agent indefinitely and wastes CI resources.
            timeout(time: 10, unit: 'MINUTES')

            // Keep only the last 20 builds in Jenkins.
            // WHY: Old build logs and artifacts consume disk space. 20 builds is
            // usually enough to diagnose recent regressions without filling the disk.
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        stages {
            stage('Hello') {
                steps {
                    // These echo statements are placeholders.
                    // In a real pipeline this is where you'd compile, test, lint, etc.
                    echo "Hello from the shared pipeline library!"
                    echo "Branch: ${env.BRANCH_NAME}"
                    // ↑ BRANCH_NAME is set by the multibranch pipeline job.
                    //   It contains the Git branch name (e.g. "main", "feature/foo").
                    echo "Build number: ${env.BUILD_NUMBER}"
                    // ↑ BUILD_NUMBER increments with each run of this job.
                    sh 'echo "Running on: $(hostname)"'
                    // ↑ sh() runs a shell command on the agent. Useful for
                    //   confirming which node actually executed the build.
                }
            }
        }

        post {
            // 'post' blocks run after all stages, regardless of outcome.
            // They are used for notifications, cleanup, and reporting.

            success {
                // In the real pipeline this might post a Slack notification
                // or update a dashboard. For now, just log.
                echo 'Pipeline finished successfully.'
            }
            failure {
                // In the real pipeline this might page on-call or create a Jira ticket.
                echo 'Pipeline failed.'
            }
        }
    }
}

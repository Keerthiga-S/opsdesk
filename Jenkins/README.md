# Jenkins delivery setup

The root `Jenkinsfile` loads reusable steps from `Jenkins/CI/ci.groovy`
and `Jenkins/CD/cd.groovy`. It is the only file containing a Declarative
`pipeline` block; no Shared Library configuration is required.

| Event | Execution |
| --- | --- |
| PR opened or updated with target `Prod` | Tests, coverage, Dockerfile lint reports, SonarQube quality gate, dependency scan, image build and image scan, then push and sign versioned images |
| Merge into `Prod` | Verify the matching signed images, pull them from Docker Hub, deploy and verify production |
| Push to `Dev` or PR targeting another branch | Delivery stages skipped |

Any validation failure stops the PR build before image publication. Dockerfile
linting currently produces reports only; SonarQube and Trivy retain their
existing gates. PR CI publishes and signs only versioned images; it never
updates `latest` tags or deploys. The image tag is the checked-out Git tree ID,
which identifies the source files. A PR test merge and the final `Prod` merge
can have different commit IDs but the same tree ID when their files match.
CD computes the merged tree ID, verifies the signatures on those images, and
deploys them without rebuilding or repeating CI. If the merged tree differs
from the validated PR tree, CD will fail unless matching signed images already
exist for the new tree ID.
Production branch builds also run on direct pushes; require PRs and the passing
PR Jenkins check in GitHub. Confirm administrators cannot bypass that rule.
Only trusted PR authors should be able to run this job: PR code has access to
the registry and signing credentials during publication.

## Jenkins configuration

1. Use a Multibranch Pipeline with GitHub Branch Source for this repository.
2. Set the script path to `Jenkinsfile` relative to the repository root.
3. Enable discovery of origin pull requests. Choose testing the PR merged with
   the current target branch so validation includes the proposed merge. Disable
   fork pull request discovery for this privileged job.
4. Enable branch discovery including `Prod` (do not exclude `Prod` with filters).
5. Configure the repository webhook to `https://<jenkins-host>/github-webhook/`
   with push and pull request events. Verify deliveries succeed.
6. Ensure Jenkins reports PR build status to GitHub. Copy the actual check name
   shown on a PR; it is needed for the required status check below.

The agent needs a POSIX shell, Git, Docker CLI and access to the Docker daemon.
Tool containers use `--volumes-from jenkins`; the Jenkins container must have
that name and its workspace must be accessible through those volumes.
Required plugins include Pipeline, Git, GitHub Branch Source, Workspace Cleanup,
Credentials Binding and SSH Agent.

Existing credential IDs are retained: `sonarqube-token`, `dockerhub-creds`,
`cosign-private-key`, `cosign-passphrase`, `cosign-public-key`, and
`OpsDesk_Deployment_Server`. Configure the SCM credential in the multibranch job.

## GitHub merge protection (required)

Code alone cannot disable GitHub's merge button. In repository Settings, create
an active ruleset or branch protection rule targeting `Prod`:

1. Require a pull request before merging.
2. Require status checks to pass before merging and select the actual Jenkins
   PR check reported on the repository. Require the PR to be up to date with
   `Prod` before merging.
3. Disable bypasses for users who must also wait for CI, including administrators.

Verify that merging is blocked while the check is pending or failed and becomes
available after success, provided the PR meets the other repository rules.
The new Jenkinsfile and both helper scripts must be committed together.

## Deployment prerequisites

The production directory must already contain the correct Compose configuration
and production environment. The pipeline selects the images using `IMAGE_TAG`;
it does not update the server's checkout. Its Compose verification uses the same
tag as deployment. The production Docker daemon needs registry access to pull
the application images.

References: [Jenkins load step](https://www.jenkins.io/doc/pipeline/steps/workflow-cps/#load-evaluate-a-groovy-source-file-into-the-pipeline-script),
[GitHub Branch Source](https://plugins.jenkins.io/github-branch-source/), and
[GitHub branch protection](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches).

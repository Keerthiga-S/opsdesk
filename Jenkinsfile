node {
checkout scm
if (env.BRANCH_NAME == 'Prod') {
load 'Jenkins/CD/Jenkinsfile'
} else {
load 'Jenkins/CI/Jenkinsfile'
}
}
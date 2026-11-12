node {
    checkout scm

    if (env.BRANCH_NAME == 'Prod') {
        load 'Jenkins/CI/Jenkinsfile'
    } else {
        echo "Skipping OpsDesk CI: branch ${env.BRANCH_NAME} is not Prod."
    }
}
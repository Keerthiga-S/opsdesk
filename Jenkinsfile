
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 60, unit: 'MINUTES')
    }

    stages {
        stage('CI - Pull Request Validation') {
            when {
                changeRequest target: 'Prod'
            }
            steps {
                script {
                    echo "Running CI for PR #${env.CHANGE_ID}"

                    def ci = load 'Jenkins/CI/ci.groovy'
                    ci.runCI()
                }
            }
        }

        stage('CD - Production Deployment') {
            when {
                branch 'Prod'
            }
            steps {
                script {
                    echo 'Running production deployment.'

                    def cd = load 'Jenkins/CD/cd.groovy'
                    cd.runCD()
                }
            }
        }
    }

    post {
        success {
            echo 'OpsDesk pipeline completed successfully.'
        }
        failure {
            echo 'OpsDesk pipeline failed.'
        }
        always {
            echo "Branch: ${env.BRANCH_NAME}"
            echo "PR: ${env.CHANGE_ID ?: 'No'}"
        }
    }
}

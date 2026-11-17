
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
                echo 'Pull request targeting Prod detected.'
                echo 'Running CI pipeline.'

                script {
                    def ciPipeline = load 'Jenkins/CI/Jenkinsfile'
                    ciPipeline.runCI()
                }
            }
        }

        stage('CD - Production Deployment') {
            when {
                branch 'Prod'
            }

            steps {
                echo 'Prod branch detected.'
                echo 'Running CD pipeline.'

                script {
                    def cdPipeline = load 'Jenkins/CD/Jenkinsfile'
                    cdPipeline.runCD()
                }
            }
        }
    }

    post {
        success {
            echo 'OpsDesk pipeline completed successfully.'
        }

        failure {
            echo 'OpsDesk pipeline failed. Check the failed stage.'
        }

        always {
            echo "Branch: ${env.BRANCH_NAME}"
            echo "Pull Request: ${env.CHANGE_ID ?: 'No'}"
        }
    }
}

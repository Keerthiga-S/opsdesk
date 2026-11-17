
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
                echo 'CI will run for this pull request.'
                echo 'The CI pipeline must be implemented directly here or through reusable pipeline functions.'
            }
        }

        stage('CD - Production Deployment') {
            when {
                branch 'Prod'
            }

            steps {
                echo 'Prod branch detected.'
                echo 'CD will run after changes are merged into Prod.'
                echo 'The CD pipeline must be implemented directly here or through reusable pipeline functions.'
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


pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 60, unit: 'MINUTES')
    }

    stages {
        stage('Checkout Pipeline Source') {
            when {
                anyOf {
                    changeRequest target: 'Prod'
                    branch 'Prod'
                }
            }
            steps {
                dir('pipeline-src') {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: '*/Prod']],
                        userRemoteConfigs: [[
                            url: 'https://github.com/Keerthiga-S/opsdesk.git',
                            credentialsId: 'github-token'
                        ]]
                    ])
                }

                sh '''
                    echo "Checking pipeline files from Prod..."
                    test -f pipeline-src/Jenkins/CI/ci.groovy
                    test -f pipeline-src/Jenkins/CD/cd.groovy
                    echo "Both pipeline files found."
                '''
            }
        }

        stage('CI - Pull Request Validation') {
            when {
                changeRequest target: 'Prod'
            }
            steps {
                echo "PR #${env.CHANGE_ID}: Pipeline source is available."
                error('CI scripts must be converted to reusable Groovy scripts before execution.')
            }
        }

        stage('CD - Production Deployment') {
            when {
                branch 'Prod'
            }
            steps {
                echo 'Production pipeline source is available.'
                error('CD scripts must be converted to reusable Groovy scripts before execution.')
            }
        }
    }

    post {
        success {
            echo 'Pipeline completed.'
        }
        failure {
            echo 'Pipeline requires correction. Check the failed stage.'
        }
    }
}

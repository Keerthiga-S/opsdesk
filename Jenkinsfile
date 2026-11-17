pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        skipDefaultCheckout(true)
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        DOCKERHUB_IMAGE = 'iitmpravartak/opsdesk'
        JENKINS_CONTAINER = 'jenkins'
        SONAR_HOST_URL = 'http://192.168.200.16:9000'
        SONAR_PROJECT_KEY = 'opsdesk'
        DEPLOY_HOST = '3.7.206.60'
        DEPLOY_USER = 'ubuntu'
        DEPLOY_DIR = '/var/www/opsdesk/opsdesk'
    }

    stages {
        stage('Prod Delivery') {
            when {
                anyOf {
                    changeRequest target: 'Prod'
                    allOf {
                        branch 'Prod'
                        not { changeRequest() }
                    }
                }
            }
            stages {
                stage('Checkout SCM') {
                    steps {
                        cleanWs()
                        checkout scm
                        script {
                            // The PR test merge and final merge share a tree ID when their files match.
                            env.IMAGE_TAG = sh(script: 'git rev-parse HEAD^{tree}', returnStdout: true).trim()
                            def runId = env.BUILD_TAG.replaceAll('[^a-zA-Z0-9_.-]', '-')
                            env.CI_NETWORK = "opsdesk-ci-${runId}"
                            env.POSTGRES_CONTAINER = "opsdesk-ci-postgres-${runId}"
                            env.REDIS_CONTAINER = "opsdesk-ci-redis-${runId}"
                            env.DOCKER_CONFIG = "${pwd(tmp: true)}/docker-config"
                            echo "Branch: ${env.BRANCH_NAME}; PR: ${env.CHANGE_ID ?: 'none'}; source tree tag: ${env.IMAGE_TAG}"
                        }
                    }
                }

                stage('CI - Validation') {
                    when { changeRequest target: 'Prod' }
                    steps {
                        script {
                            def ci = load 'Jenkins/CI/ci.groovy'
                            ci.runValidation()
                        }
                    }
                }

                stage('CD - Production Deployment') {
                    when {
                        allOf {
                            branch 'Prod'
                            not { changeRequest() }
                        }
                    }
                    steps {
                        script {
                            def cd = load 'Jenkins/CD/cd.groovy'
                            cd.deploy()
                        }
                    }
                }
            }
            post {
                always {
                    archiveArtifacts(
                        artifacts: 'reports/**,backend/coverage.xml,backend/test-results.xml,coverage/lcov.info',
                        allowEmptyArchive: true
                    )
                }
                cleanup {
                    script {
                        if (env.DOCKER_CONFIG) {
                            sh 'docker logout >/dev/null 2>&1 || true'
                            dir(env.DOCKER_CONFIG) {
                                deleteDir()
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            echo 'SUCCESS: all applicable OpsDesk pipeline stages passed.'
        }
        failure {
            echo 'FAILURE: review the failed stage. Publication and deployment stop on failure.'
        }
    }
}

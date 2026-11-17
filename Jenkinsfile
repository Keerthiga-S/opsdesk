pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()
        timeout(time: 60, unit: 'MINUTES')
    }

    environment {
        DOCKERHUB_IMAGE = 'iitmpravartak/opsdesk'
        JENKINS_CONTAINER = 'jenkins'
        SONAR_HOST_URL = 'http://192.168.200.16:9000'
        SONAR_PROJECT_KEY = 'opsdesk'
        CI_NETWORK = "opsdesk-ci-${BUILD_NUMBER}"
        POSTGRES_CONTAINER = "opsdesk-ci-postgres-${BUILD_NUMBER}"
        REDIS_CONTAINER = "opsdesk-ci-redis-${BUILD_NUMBER}"
        DEPLOY_HOST = '3.7.206.60'
        DEPLOY_USER = 'ubuntu'
        DEPLOY_DIR = '/var/www/opsdesk/opsdesk'
    }

    stages {
        stage('Clean Workspace') {
            when { anyOf { changeRequest target: 'Prod'; branch 'Prod' } }
            steps { cleanWs() }
        }

        stage('Checkout SCM') {
            when { anyOf { changeRequest target: 'Prod'; branch 'Prod' } }
            steps {
                checkout scm
                script {
                    // Immutable tag for the exact source revision tested by this build.
                    env.IMAGE_TAG = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    echo "Branch: ${env.BRANCH_NAME}; PR: ${env.CHANGE_ID ?: 'No'}; image tag: ${env.IMAGE_TAG}"
                }
            }
        }

        stage('Unit Testing & Coverage') {
            when { anyOf { changeRequest target: 'Prod'; branch 'Prod' } }
            steps {
                sh '''
                    set -eu
                    mkdir -p reports
                    docker rm -f "$POSTGRES_CONTAINER" "$REDIS_CONTAINER" 2>/dev/null || true
                    docker network rm "$CI_NETWORK" 2>/dev/null || true
                    docker network create "$CI_NETWORK"

                    docker run -d --name "$POSTGRES_CONTAINER" --network "$CI_NETWORK" \
                      --network-alias postgres -e POSTGRES_USER=postgres \
                      -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=postgres pgvector/pgvector:pg16
                    docker run -d --name "$REDIS_CONTAINER" --network "$CI_NETWORK" \
                      --network-alias redis redis:7-alpine

                    for i in $(seq 1 30); do
                      docker exec "$POSTGRES_CONTAINER" pg_isready -U postgres >/dev/null 2>&1 && break
                      sleep 2
                    done
                    docker exec "$POSTGRES_CONTAINER" pg_isready -U postgres
                    for i in $(seq 1 30); do
                      docker exec "$REDIS_CONTAINER" redis-cli ping 2>/dev/null | grep -q PONG && break
                      sleep 2
                    done
                    docker exec "$REDIS_CONTAINER" redis-cli ping

                    docker run --rm --volumes-from "$JENKINS_CONTAINER" --network "$CI_NETWORK" \
                      -w "$WORKSPACE/backend" \
                      -e OPSDESK_TEST_ADMIN_URL='postgresql://postgres:postgres@postgres:5432/postgres' \
                      -e OPSDESK_TEST_REDIS_URL='redis://redis:6379/15' python:3.12-slim bash -lc '
                        set -e
                        python -m pip install --upgrade pip
                        pip install -r requirements.txt pytest-cov
                        python -m pytest --cov=opsdesk --cov-report=term-missing \
                          --cov-report=xml:coverage.xml --junitxml=test-results.xml
                      '

                    docker run --rm --volumes-from "$JENKINS_CONTAINER" -w "$WORKSPACE" \
                      node:22-alpine sh -lc '
                        set -e
                        npm ci --no-audit --no-fund
                        npm run test:coverage
                        npm run build
                      '
                '''
            }
            post {
                always {
                    sh '''
                        docker rm -f "$POSTGRES_CONTAINER" "$REDIS_CONTAINER" 2>/dev/null || true
                        docker network rm "$CI_NETWORK" 2>/dev/null || true
                    '''
                }
            }
        }

        stage('Docker Linting') {
            when { anyOf { changeRequest target: 'Prod'; branch 'Prod' } }
            steps {
                sh '''
                    mkdir -p reports
                    docker run --rm --volumes-from "$JENKINS_CONTAINER" -w "$WORKSPACE" \
                      hadolint/hadolint:v2.12.0-debian hadolint --format json backend/Dockerfile \
                      > reports/backend-hadolint.json || true
                    docker run --rm --volumes-from "$JENKINS_CONTAINER" -w "$WORKSPACE" \
                      hadolint/hadolint:v2.12.0-debian hadolint --format json Dockerfile \
                      > reports/frontend-hadolint.json || true
                '''
            }
        }

        stage('SAST SonarQube Analysis & Quality Gate') {
            when { anyOf { changeRequest target: 'Prod'; branch 'Prod' } }
            steps {
                withCredentials([string(credentialsId: 'sonarqube-token', variable: 'SONAR_TOKEN')]) {
                    sh '''
                        set -e
                        mkdir -p .scannerwork
                        docker run --rm --network host --volumes-from "$JENKINS_CONTAINER" \
                          -w "$WORKSPACE" -e SONAR_TOKEN="$SONAR_TOKEN" \
                          sonarsource/sonar-scanner-cli:latest \
                          -Dsonar.host.url="$SONAR_HOST_URL" -Dsonar.token="$SONAR_TOKEN" \
                          -Dsonar.projectKey="$SONAR_PROJECT_KEY" -Dsonar.projectName=OpsDesk \
                          -Dsonar.sources=backend,src -Dsonar.tests=backend/tests \
                          -Dsonar.python.coverage.reportPaths=backend/coverage.xml \
                          -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info \
                          -Dsonar.exclusions='**/node_modules/**,**/dist/**,**/.venv/**,**/__pycache__/**,**/tests/**,**/*.test.js,**/*.spec.js' \
                          -Dsonar.working.directory="$WORKSPACE/.scannerwork" \
                          -Dsonar.scanner.metadataFilePath="$WORKSPACE/.scannerwork/report-task.txt" \
                          -Dsonar.qualitygate.wait=true -Dsonar.qualitygate.timeout=300
                        test -s .scannerwork/report-task.txt
                    '''
                }
            }
        }

        stage('Dependency Scan') {
            when { anyOf { changeRequest target: 'Prod'; branch 'Prod' } }
            steps {
                sh '''
                    set -e
                    mkdir -p reports
                    docker run --rm --volumes-from "$JENKINS_CONTAINER" -w "$WORKSPACE" \
                      aquasec/trivy:0.58.2 fs --scanners vuln,misconfig,secret \
                      --skip-dirs .git --skip-dirs node_modules --skip-dirs dist \
                      --format json --output reports/dependency-scan.json .
                    docker run --rm --volumes-from "$JENKINS_CONTAINER" -w "$WORKSPACE" \
                      aquasec/trivy:0.58.2 fs --scanners vuln,misconfig,secret \
                      --skip-dirs .git --skip-dirs node_modules --skip-dirs dist \
                      --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 .
                '''
            }
        }

        stage('Build Docker Images') {
            when { anyOf { changeRequest target: 'Prod'; branch 'Prod' } }
            steps {
                sh '''
                    set -e
                    docker build -t "$DOCKERHUB_IMAGE:backend-$IMAGE_TAG" -f backend/Dockerfile backend
                    docker build -t "$DOCKERHUB_IMAGE:frontend-$IMAGE_TAG" -f Dockerfile .
                    docker images | grep opsdesk
                '''
            }
        }

        stage('Trivy Image Scanning') {
            when { anyOf { changeRequest target: 'Prod'; branch 'Prod' } }
            steps {
                sh '''
                    set -e
                    mkdir -p reports
                    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
                      --volumes-from "$JENKINS_CONTAINER" -w "$WORKSPACE" aquasec/trivy:0.58.2 image \
                      --format json --output reports/backend-image-scan.json "$DOCKERHUB_IMAGE:backend-$IMAGE_TAG"
                    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.58.2 image \
                      --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 "$DOCKERHUB_IMAGE:backend-$IMAGE_TAG"
                    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock \
                      --volumes-from "$JENKINS_CONTAINER" -w "$WORKSPACE" aquasec/trivy:0.58.2 image \
                      --format json --output reports/frontend-image-scan.json "$DOCKERHUB_IMAGE:frontend-$IMAGE_TAG"
                    docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.58.2 image \
                      --ignore-unfixed --severity HIGH,CRITICAL --exit-code 1 "$DOCKERHUB_IMAGE:frontend-$IMAGE_TAG"
                '''
            }
        }

        // Pull requests validate only. Publishing and deployment happen after merge to Prod.
        stage('Push Approved Images to Docker Hub') {
            when { branch 'Prod' }
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerhub-creds',
                    usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_PASSWORD')]) {
                    sh '''
                        set -eu
                        echo "$DOCKERHUB_PASSWORD" | docker login --username "$DOCKERHUB_USER" --password-stdin
                        trap 'docker logout >/dev/null 2>&1 || true' EXIT
                        docker tag "$DOCKERHUB_IMAGE:backend-$IMAGE_TAG" "$DOCKERHUB_IMAGE:backend-latest"
                        docker tag "$DOCKERHUB_IMAGE:frontend-$IMAGE_TAG" "$DOCKERHUB_IMAGE:frontend-latest"
                        docker push "$DOCKERHUB_IMAGE:backend-$IMAGE_TAG"
                        docker push "$DOCKERHUB_IMAGE:frontend-$IMAGE_TAG"
                        docker push "$DOCKERHUB_IMAGE:backend-latest"
                        docker push "$DOCKERHUB_IMAGE:frontend-latest"
                    '''
                }
            }
        }

        stage('Sign Approved Images') {
            when { branch 'Prod' }
            steps {
                withCredentials([
                    file(credentialsId: 'cosign-private-key', variable: 'COSIGN_KEY'),
                    string(credentialsId: 'cosign-passphrase', variable: 'COSIGN_PASSWORD'),
                    usernamePassword(credentialsId: 'dockerhub-creds',
                        usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_TOKEN')
                ]) {
                    sh '''
                        set -eu
                        export DOCKER_CONFIG=/var/jenkins_home/.docker
                        mkdir -p "$DOCKER_CONFIG"
                        echo "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USER" --password-stdin
                        trap 'docker logout >/dev/null 2>&1 || true' EXIT
                        JENKINS_UID=$(id -u)
                        JENKINS_GID=$(id -g)
                        sign_image() {
                            docker run --rm --user "$JENKINS_UID:$JENKINS_GID" \
                              --volumes-from "$JENKINS_CONTAINER" \
                              -e COSIGN_PASSWORD="$COSIGN_PASSWORD" \
                              -e DOCKER_CONFIG=/var/jenkins_home/.docker \
                              gcr.io/projectsigstore/cosign:v2.4.1 sign --yes --key "$COSIGN_KEY" "$1"
                        }
                        sign_image "$DOCKERHUB_IMAGE:backend-$IMAGE_TAG"
                        sign_image "$DOCKERHUB_IMAGE:frontend-$IMAGE_TAG"
                    '''
                }
            }
        }

        stage('Verify Image Signatures') {
            when { branch 'Prod' }
            steps {
                withCredentials([file(credentialsId: 'cosign-public-key', variable: 'COSIGN_PUB_KEY')]) {
                    sh '''
                        set -eu
                        export DOCKER_CONFIG=/var/jenkins_home/.docker
                        for image in backend frontend; do
                            docker run --rm --volumes-from "$JENKINS_CONTAINER" \
                              -e DOCKER_CONFIG=/var/jenkins_home/.docker \
                              gcr.io/projectsigstore/cosign:v2.4.1 verify --key "$COSIGN_PUB_KEY" \
                              "$DOCKERHUB_IMAGE:$image-$IMAGE_TAG"
                        done
                    '''
                }
            }
        }

        stage('Deploy to Production') {
            when { branch 'Prod' }
            steps {
                sshagent(credentials: ['OpsDesk_Deployment_Server']) {
                    sh '''
                        set -eu
                        mkdir -p ~/.ssh && chmod 700 ~/.ssh
                        touch ~/.ssh/known_hosts && chmod 600 ~/.ssh/known_hosts
                        ssh-keyscan -H "$DEPLOY_HOST" >> ~/.ssh/known_hosts
                        ssh "$DEPLOY_USER@$DEPLOY_HOST" \
                          "export IMAGE_TAG='$IMAGE_TAG' DEPLOY_DIR='$DEPLOY_DIR'; bash -s" <<'REMOTE'
                        set -eu
                        cd "$DEPLOY_DIR"
                        docker compose config --quiet
                        docker compose pull
                        docker compose up -d --remove-orphans
REMOTE
                    '''
                }
            }
        }

        stage('Verify Production Deployment') {
            when { branch 'Prod' }
            steps {
                sshagent(credentials: ['OpsDesk_Deployment_Server']) {
                    sh '''
                        set -eu
                        ssh "$DEPLOY_USER@$DEPLOY_HOST" "export DEPLOY_DIR='$DEPLOY_DIR'; bash -s" <<'REMOTE'
                        set -eu
                        cd "$DEPLOY_DIR"
                        docker compose ps --all

                        MIGRATE_ID=$(docker compose ps -aq migrate)
                        if [ -z "$MIGRATE_ID" ]; then echo 'ERROR: Migration container not found.'; exit 1; fi
                        MIGRATE_STATUS=$(docker inspect -f '{{.State.Status}}' "$MIGRATE_ID")
                        MIGRATE_EXIT=$(docker inspect -f '{{.State.ExitCode}}' "$MIGRATE_ID")
                        if [ "$MIGRATE_STATUS" != exited ] || [ "$MIGRATE_EXIT" != 0 ]; then
                            docker compose logs --tail=50 migrate
                            echo 'ERROR: Migration failed.'
                            exit 1
                        fi

                        for svc in db api worker beat; do
                            CID=$(docker compose ps -q "$svc")
                            if [ -z "$CID" ]; then echo "ERROR: $svc container not found."; exit 1; fi
                            HEALTH=$(docker inspect -f '{{.State.Health.Status}}' "$CID")
                            if [ "$HEALTH" != healthy ]; then
                                docker compose logs --tail=50 "$svc"
                                echo "ERROR: $svc is not healthy."
                                exit 1
                            fi
                            echo "$svc is healthy."
                        done

                        for svc in redis mailpit web; do
                            CID=$(docker compose ps -q "$svc")
                            if [ -z "$CID" ]; then echo "ERROR: $svc container not found."; exit 1; fi
                            STATUS=$(docker inspect -f '{{.State.Status}}' "$CID")
                            if [ "$STATUS" != running ]; then
                                docker compose logs --tail=50 "$svc"
                                echo "ERROR: $svc is not running."
                                exit 1
                            fi
                            echo "$svc is running."
                        done
                        echo 'All production services passed verification.'
                        docker compose ps
REMOTE
                    '''
                }
            }
        }
    }

    post {
        always {
            echo 'OpsDesk pipeline execution completed.'
            archiveArtifacts(artifacts: 'reports/**,backend/coverage.xml,backend/test-results.xml,coverage/lcov.info', allowEmptyArchive: true)
            sh 'docker logout >/dev/null 2>&1 || true'
            echo "Branch: ${env.BRANCH_NAME}; PR: ${env.CHANGE_ID ?: 'No'}; image tag: ${env.IMAGE_TAG ?: 'Not created'}"
        }
        success { echo 'SUCCESS: OpsDesk pipeline completed successfully.' }
        failure { echo 'FAILURE: OpsDesk pipeline failed. Review the failed stage and console output.' }
    }
}

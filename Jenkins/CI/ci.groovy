// Reusable Scripted Pipeline steps. The root Jenkinsfile owns the pipeline and agent.
def runValidation() {
    if (!(env.CHANGE_ID && env.CHANGE_TARGET == 'Prod')) {
        error('CI validation is allowed only for a pull request targeting Prod.')
    }
    if (env.CHANGE_FORK) {
        error('This credentialed CI job does not publish images from fork pull requests.')
    }

    stage('Unit Testing & Coverage') {
        try {
            script {
                sh '''
                    set -e

                    mkdir -p reports

                    # Remove any leftover test containers/network
                    docker rm -f "${POSTGRES_CONTAINER}" "${REDIS_CONTAINER}" 2>/dev/null || true
                    docker network rm "${CI_NETWORK}" 2>/dev/null || true

                    # Create an isolated Docker network for CI tests
                    docker network create "${CI_NETWORK}"

                    # Start temporary PostgreSQL 16 with pgvector
                    docker run -d \
                        --name "${POSTGRES_CONTAINER}" \
                        --network "${CI_NETWORK}" \
                        --network-alias postgres \
                        -e POSTGRES_USER=postgres \
                        -e POSTGRES_PASSWORD=postgres \
                        -e POSTGRES_DB=postgres \
                        pgvector/pgvector:pg16

                    # Start temporary Redis
                    docker run -d \
                        --name "${REDIS_CONTAINER}" \
                        --network "${CI_NETWORK}" \
                        --network-alias redis \
                        redis:7-alpine

                    # Wait for PostgreSQL to become ready
                    echo "Waiting for PostgreSQL..."

                    for i in $(seq 1 30); do
                        if docker exec "${POSTGRES_CONTAINER}" \
                            pg_isready -U postgres >/dev/null 2>&1; then
                            break
                        fi
                        sleep 2
                    done

                    docker exec "${POSTGRES_CONTAINER}" \
                        pg_isready -U postgres

                    # Wait for Redis to become ready
                    echo "Waiting for Redis..."

                    for i in $(seq 1 30); do
                        if docker exec "${REDIS_CONTAINER}" \
                            redis-cli ping 2>/dev/null | grep -q PONG; then
                            break
                        fi
                        sleep 2
                    done

                    docker exec "${REDIS_CONTAINER}" redis-cli ping

                    # Run backend tests in Python 3.12
                    # The test fixture creates/recreates only opsdesk_test
                    # inside this disposable PostgreSQL container.
                    docker run --rm \
                        --volumes-from "${JENKINS_CONTAINER}" \
                        --network "${CI_NETWORK}" \
                        -w "${WORKSPACE}/backend" \
                        -e OPSDESK_TEST_ADMIN_URL="postgresql://postgres:postgres@postgres:5432/postgres" \
                        -e OPSDESK_TEST_REDIS_URL="redis://redis:6379/15" \
                        python:3.12-slim \
                        bash -lc '
                            set -e

                            python -m pip install --upgrade pip
                            pip install -r requirements.txt
                            pip install pytest-cov

                            python -m pytest \
                                --cov=opsdesk \
                                --cov-report=term-missing \
                                --cov-report=xml:coverage.xml \
                                --junitxml=test-results.xml
                        '

                    # Run frontend tests and production build
                    docker run --rm \
                        --volumes-from "${JENKINS_CONTAINER}" \
                        -w "${WORKSPACE}" \
                        node:22-alpine \
                        sh -lc '
                            set -e

                            npm ci --no-audit --no-fund
                            npm run test:coverage
                            npm run build
                        '
                '''
            }
        } finally {
            sh '''
                docker rm -f "${POSTGRES_CONTAINER}" "${REDIS_CONTAINER}" 2>/dev/null || true
                docker network rm "${CI_NETWORK}" 2>/dev/null || true
            '''
        }
    }

    stage('Docker Linting') {
        sh '''
            set -e

            mkdir -p reports

            echo "Linting backend Dockerfile..."

            docker run --rm \
                --volumes-from "${JENKINS_CONTAINER}" \
                -w "${WORKSPACE}" \
                hadolint/hadolint:v2.12.0-debian \
                hadolint --format json backend/Dockerfile \
                > reports/backend-hadolint.json || true

            echo "Linting frontend Dockerfile..."

            docker run --rm \
                --volumes-from "${JENKINS_CONTAINER}" \
                -w "${WORKSPACE}" \
                hadolint/hadolint:v2.12.0-debian \
                hadolint --format json Dockerfile \
                > reports/frontend-hadolint.json || true

            echo "Docker lint reports generated."
        '''
    }

    stage('SAST SonarQube Analysis & Quality Gate') {
        withCredentials([
            string(
                credentialsId: 'sonarqube-token',
                variable: 'SONAR_TOKEN'
            )
        ]) {
            sh '''
                set -e

                echo "Starting OpsDesk SonarQube analysis..."

                mkdir -p .scannerwork

                docker run --rm \
                    --network host \
                    --volumes-from "${JENKINS_CONTAINER}" \
                    -w "${WORKSPACE}" \
                    -e SONAR_TOKEN="${SONAR_TOKEN}" \
                    sonarsource/sonar-scanner-cli:latest \
                    -Dsonar.host.url="${SONAR_HOST_URL}" \
                    -Dsonar.token="${SONAR_TOKEN}" \
                    -Dsonar.projectKey="${SONAR_PROJECT_KEY}" \
                    -Dsonar.projectName="OpsDesk" \
                    -Dsonar.sources=backend,src \
                    -Dsonar.tests=backend/tests \
                    -Dsonar.python.coverage.reportPaths=backend/coverage.xml \
                    -Dsonar.javascript.lcov.reportPaths=coverage/lcov.info \
                    -Dsonar.exclusions="**/node_modules/**,**/dist/**,**/.venv/**,**/__pycache__/**,**/tests/**,**/*.test.js,**/*.spec.js" \
                    -Dsonar.working.directory="${WORKSPACE}/.scannerwork" \
                    -Dsonar.scanner.metadataFilePath="${WORKSPACE}/.scannerwork/report-task.txt" \
                    -Dsonar.qualitygate.wait=true \
                    -Dsonar.qualitygate.timeout=300

                echo "Checking SonarQube analysis report..."

                test -s .scannerwork/report-task.txt

                echo "SonarQube analysis report generated successfully."
            '''
        }
    }

    stage('Dependency Scan') {
        sh '''
            set -e

            mkdir -p reports

            echo "Running Trivy filesystem and dependency scan..."

            # Generate a complete JSON report
            docker run --rm \
                --volumes-from "${JENKINS_CONTAINER}" \
                -w "${WORKSPACE}" \
                aquasec/trivy:0.58.2 \
                fs \
                --scanners vuln,misconfig,secret \
                --skip-dirs .git \
                --skip-dirs node_modules \
                --skip-dirs dist \
                --format json \
                --output reports/dependency-scan.json \
                .

            # Fail for fixable HIGH and CRITICAL findings
            docker run --rm \
                --volumes-from "${JENKINS_CONTAINER}" \
                -w "${WORKSPACE}" \
                aquasec/trivy:0.58.2 \
                fs \
                --scanners vuln,misconfig,secret \
                --skip-dirs .git \
                --skip-dirs node_modules \
                --skip-dirs dist \
                --ignore-unfixed \
                --severity HIGH,CRITICAL \
                --exit-code 1 \
                .
        '''
    }

    buildAndScanImages()
    publishImages()
}

def buildAndScanImages() {
    if (!(env.CHANGE_ID && env.CHANGE_TARGET == 'Prod')) {
        error('Image build and scan are allowed only for a pull request targeting Prod.')
    }

    stage('Build Docker Image') {
        sh '''
            set -e

            echo "Building OpsDesk backend image..."

            docker build \
                -t "${DOCKERHUB_IMAGE}:backend-${IMAGE_TAG}" \
                -f backend/Dockerfile \
                backend

            echo "Building OpsDesk frontend image..."

            docker build \
                -t "${DOCKERHUB_IMAGE}:frontend-${IMAGE_TAG}" \
                -f Dockerfile \
                .

            echo "Docker images built successfully."

            docker images | grep opsdesk
        '''
    }

    stage('Trivy Image Scanning') {
        sh '''
            set -e

            mkdir -p reports

            echo "Scanning backend Docker image..."

            # Full backend image report
            docker run --rm \
                -v /var/run/docker.sock:/var/run/docker.sock \
                --volumes-from "${JENKINS_CONTAINER}" \
                -w "${WORKSPACE}" \
                aquasec/trivy:0.58.2 \
                image \
                --format json \
                --output reports/backend-image-scan.json \
                "${DOCKERHUB_IMAGE}:backend-${IMAGE_TAG}"

            # Security gate for backend image
            docker run --rm \
                -v /var/run/docker.sock:/var/run/docker.sock \
                aquasec/trivy:0.58.2 \
                image \
                --ignore-unfixed \
                --severity HIGH,CRITICAL \
                --exit-code 1 \
                "${DOCKERHUB_IMAGE}:backend-${IMAGE_TAG}"

            echo "Scanning frontend Docker image..."

            # Full frontend image report
            docker run --rm \
                -v /var/run/docker.sock:/var/run/docker.sock \
                --volumes-from "${JENKINS_CONTAINER}" \
                -w "${WORKSPACE}" \
                aquasec/trivy:0.58.2 \
                image \
                --format json \
                --output reports/frontend-image-scan.json \
                "${DOCKERHUB_IMAGE}:frontend-${IMAGE_TAG}"

            # Security gate for frontend image
            docker run --rm \
                -v /var/run/docker.sock:/var/run/docker.sock \
                aquasec/trivy:0.58.2 \
                image \
                --ignore-unfixed \
                --severity HIGH,CRITICAL \
                --exit-code 1 \
                "${DOCKERHUB_IMAGE}:frontend-${IMAGE_TAG}"

            echo "Backend and frontend image scans completed."
        '''
    }

}

def publishImages() {
    if (!(env.CHANGE_ID && env.CHANGE_TARGET == 'Prod')) {
        error('Image publication is allowed only for a pull request targeting Prod.')
    }
    if (env.CHANGE_FORK) {
        error('Image publication from fork pull requests is not allowed.')
    }

    stage('Push Validated Images to Registry') {
        withCredentials([
            usernamePassword(
                credentialsId: 'dockerhub-creds',
                usernameVariable: 'DOCKERHUB_USER',
                passwordVariable: 'DOCKERHUB_PASSWORD'
            )
        ]) {
            sh '''
                set -e

                echo "Logging in to Docker Hub..."

                echo "${DOCKERHUB_PASSWORD}" | docker login \
                    --username "${DOCKERHUB_USER}" \
                    --password-stdin

                # Publish only source-tree tags; a PR must not change latest.
                docker push "${DOCKERHUB_IMAGE}:backend-${IMAGE_TAG}"
                docker push "${DOCKERHUB_IMAGE}:frontend-${IMAGE_TAG}"

                docker logout

                echo "Both OpsDesk images pushed successfully."
            '''
        }
    }

    stage('Sign Validated Images') {
        withCredentials([
            file(
                credentialsId: 'cosign-private-key',
                variable: 'COSIGN_KEY'
            ),
            string(
                credentialsId: 'cosign-passphrase',
                variable: 'COSIGN_PASSWORD'
            ),
            usernamePassword(
                credentialsId: 'dockerhub-creds',
                usernameVariable: 'DOCKERHUB_USER',
                passwordVariable: 'DOCKERHUB_TOKEN'
            )
        ]) {
            sh '''
                set -e

                echo "Checking Jenkins identity..."
                id

                echo "Checking Cosign private key..."
                ls -l "${COSIGN_KEY}"

                if [ ! -r "${COSIGN_KEY}" ]; then
                    echo "ERROR: Cosign private key is not readable."
                    exit 1
                fi

                JENKINS_UID=$(id -u)
                JENKINS_GID=$(id -g)

                # Authenticate Jenkins with Docker Hub.
                export DOCKER_CONFIG="${DOCKER_CONFIG}"
                mkdir -p "$DOCKER_CONFIG"

                echo "Authenticating with Docker Hub..."
                echo "$DOCKERHUB_TOKEN" | docker login \
                    --username "$DOCKERHUB_USER" \
                    --password-stdin

                sign_image() {
                    IMAGE="$1"

                    echo "Signing: ${IMAGE}"

                    docker run --rm \
                        --user "${JENKINS_UID}:${JENKINS_GID}" \
                        --volumes-from "${JENKINS_CONTAINER}" \
                        -e COSIGN_PASSWORD="${COSIGN_PASSWORD}" \
                        -e DOCKER_CONFIG="${DOCKER_CONFIG}" \
                        gcr.io/projectsigstore/cosign:v2.4.1 \
                        sign --yes \
                        --key "${COSIGN_KEY}" \
                        "${IMAGE}"
                }

                sign_image "${DOCKERHUB_IMAGE}:backend-${IMAGE_TAG}"
                sign_image "${DOCKERHUB_IMAGE}:frontend-${IMAGE_TAG}"

                echo "All OpsDesk images signed successfully."
            '''
        }
    }

}

return this

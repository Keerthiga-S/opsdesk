
pipeline {
    agent any

    options {
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
        disableConcurrentBuilds()
    }

    parameters {
        string(
            name: 'IMAGE_TAG',
            defaultValue: '',
            description: 'Enter the successful CI image tag, for example 21'
        )
    }

    environment {
        DEPLOY_HOST = '3.7.206.60'
        DEPLOY_USER = 'ubuntu'
        DEPLOY_DIR = '/var/www/opsdesk/opsdesk'
        DOCKERHUB_IMAGE = 'iitmpravartak/opsdesk'
        JENKINS_CONTAINER = 'jenkins'
    }

    stages {

        // ==========================================
        // STAGE 1: VALIDATE PARAMETERS
        // ==========================================

        stage('Validate Parameters') {
            steps {
                script {
                    if (!params.IMAGE_TAG?.trim()) {
                        error(
                            'IMAGE_TAG is required. Enter a successful CI image tag.'
                        )
                    }

                    if (!(params.IMAGE_TAG ==~ /^[A-Za-z0-9_.-]+$/)) {
                        error('Invalid IMAGE_TAG format.')
                    }

                    env.IMAGE_TAG = params.IMAGE_TAG.trim()

                    echo "Deploying image tag: ${env.IMAGE_TAG}"
                }
            }
        }

        // ==========================================
        // STAGE 2: VERIFY COSIGN SIGNATURES
        // ==========================================

        stage('Verify Image Signatures') {
            steps {
                withCredentials([
                    file(
                        credentialsId: 'cosign-public-key',
                        variable: 'COSIGN_PUB_KEY'
                    )
                ]) {
                    sh '''
                        set -eu

                        echo "Verifying backend image signature..."

                        docker run --rm \
                            --volumes-from "${JENKINS_CONTAINER}" \
                            -e DOCKER_CONFIG=/var/jenkins_home/.docker \
                            gcr.io/projectsigstore/cosign:v2.4.1 \
                            verify \
                            --key "${COSIGN_PUB_KEY}" \
                            "${DOCKERHUB_IMAGE}:backend-${IMAGE_TAG}"

                        echo "Verifying frontend image signature..."

                        docker run --rm \
                            --volumes-from "${JENKINS_CONTAINER}" \
                            -e DOCKER_CONFIG=/var/jenkins_home/.docker \
                            gcr.io/projectsigstore/cosign:v2.4.1 \
                            verify \
                            --key "${COSIGN_PUB_KEY}" \
                            "${DOCKERHUB_IMAGE}:frontend-${IMAGE_TAG}"

                        echo "SUCCESS: Both image signatures verified."
                    '''
                }
            }

            post {
                failure {
                    echo 'CD stopped: image signature verification failed.'
                }
            }
        }

        // ==========================================
        // STAGE 3: DEPLOY TO PRODUCTION
        // ==========================================

        stage('Deploy via Docker Compose') {
            steps {
                echo "Deploying OpsDesk to ${DEPLOY_HOST}"

                sshagent(credentials: ['OpsDesk_Deployment_Server']) {
                    sh '''
                        set -eu

                        mkdir -p ~/.ssh
                        chmod 700 ~/.ssh

                        touch ~/.ssh/known_hosts
                        chmod 600 ~/.ssh/known_hosts

                        ssh-keyscan -H "${DEPLOY_HOST}" >> ~/.ssh/known_hosts

                        ssh "${DEPLOY_USER}@${DEPLOY_HOST}" \
                            "IMAGE_TAG='${IMAGE_TAG}' DEPLOY_DIR='${DEPLOY_DIR}' bash -s" <<'REMOTE'

                        set -eu

                        cd "$DEPLOY_DIR"

                        echo "Checking Docker Compose configuration..."

                        docker compose config --quiet

                        echo "Pulling versioned Docker images..."

                        docker compose pull

                        echo "Deploying OpsDesk containers..."

                        docker compose up -d --remove-orphans

                        echo "Deployment commands completed."

REMOTE
                    '''
                }
            }

            post {
                failure {
                    echo 'Deployment failed. Check SSH, Docker and Docker Compose logs.'
                }
            }
        }

        // ==========================================
        // STAGE 4: VERIFY PRODUCTION DEPLOYMENT
        // ==========================================

        stage('Verify Deployment') {
            steps {
                sshagent(credentials: ['OpsDesk_Deployment_Server']) {
                    sh '''
                        set -eu

                        ssh "${DEPLOY_USER}@${DEPLOY_HOST}" \
                            "DEPLOY_DIR='${DEPLOY_DIR}' bash -s" <<'REMOTE'

                        set -eu

                        cd "$DEPLOY_DIR"

                        echo "========== CONTAINER STATUS =========="

                        docker compose ps --all

                        echo "======================================"

                        # --------------------------------
                        # Verify database migration
                        # --------------------------------

                        echo "Checking migration status..."

                        MIGRATE_ID=$(docker compose ps -aq migrate)

                        if [ -z "$MIGRATE_ID" ]; then
                            echo "ERROR: Migration container not found."
                            exit 1
                        fi

                        MIGRATE_STATUS=$(docker inspect \
                            -f '{{.State.Status}}' "$MIGRATE_ID")

                        MIGRATE_EXIT=$(docker inspect \
                            -f '{{.State.ExitCode}}' "$MIGRATE_ID")

                        if [ "$MIGRATE_STATUS" != "exited" ] || \
                           [ "$MIGRATE_EXIT" != "0" ]; then

                            echo "ERROR: Migration failed."

                            docker compose logs --tail=50 migrate

                            exit 1
                        fi

                        echo "SUCCESS: Database migration completed."

                        # --------------------------------
                        # Verify healthy services
                        # --------------------------------

                        echo "Checking application health..."

                        for svc in db api worker beat; do

                            CID=$(docker compose ps -q "$svc")

                            if [ -z "$CID" ]; then
                                echo "ERROR: $svc container not found."
                                exit 1
                            fi

                            HEALTH=$(docker inspect \
                                -f '{{.State.Health.Status}}' "$CID")

                            if [ "$HEALTH" != "healthy" ]; then

                                echo "ERROR: $svc is not healthy."
                                docker compose logs --tail=50 "$svc"

                                exit 1
                            fi

                            echo "$svc is healthy."

                        done

                        # --------------------------------
                        # Verify running services
                        # --------------------------------

                        for svc in redis mailpit web; do

                            CID=$(docker compose ps -q "$svc")

                            if [ -z "$CID" ]; then
                                echo "ERROR: $svc container not found."
                                exit 1
                            fi

                            STATUS=$(docker inspect \
                                -f '{{.State.Status}}' "$CID")

                            if [ "$STATUS" != "running" ]; then

                                echo "ERROR: $svc is not running."
                                docker compose logs --tail=50 "$svc"

                                exit 1
                            fi

                            echo "$svc is running."

                        done

                        echo "SUCCESS: All required services verified."

                        docker compose ps

REMOTE
                    '''
                }
            }

            post {
                failure {
                    echo 'Deployment verification failed. Check production container logs.'
                }
            }
        }
    }

    // ==========================================
    // GLOBAL POST ACTIONS
    // ==========================================

    post {
        success {
            echo 'SUCCESS: OpsDesk images verified, deployed and checked.'
        }

        failure {
            echo 'OPSDesk CD PIPELINE FAILED. Review the failed stage and console output.'
        }

        always {
            echo 'OpsDesk CD pipeline execution completed.'
        }
    }
}

// Reusable deployment steps. IMAGE_TAG identifies the source tree built by PR CI.
def deploy() {
    if (env.CHANGE_ID || env.BRANCH_NAME != 'Prod') {
        error('Production deployment is allowed only on the Prod branch after merge.')
    }
    if (!(env.IMAGE_TAG ==~ /^[a-f0-9]{40}$/)) {
        error('CD requires the source tree image tag produced by PR CI.')
    }

    stage('Verify Image Signatures') {
        withCredentials([
            file(
                credentialsId: 'cosign-public-key',
                variable: 'COSIGN_PUB_KEY'
            ),
            usernamePassword(
                credentialsId: 'dockerhub-creds',
                usernameVariable: 'DOCKERHUB_USER',
                passwordVariable: 'DOCKERHUB_PASSWORD'
            )
        ]) {
            sh '''
                set -eu

                # The registry is private; Cosign needs the same Docker config as Docker CLI.
                set +x
                mkdir -p "${DOCKER_CONFIG}"
                printf '%s' "${DOCKERHUB_PASSWORD}" | docker login \
                    --username "${DOCKERHUB_USER}" --password-stdin
                set -x

                echo "Verifying backend image signature..."

                # Cosign caches Rekor keys under HOME; / is not writable by Jenkins.
                docker run --rm \
                    --user "$(id -u):$(id -g)" \
                    --volumes-from "${JENKINS_CONTAINER}" \
                    -e HOME=/tmp \
                    -e DOCKER_CONFIG="${DOCKER_CONFIG}" \
                    gcr.io/projectsigstore/cosign:v2.4.1 \
                    verify \
                    --key "${COSIGN_PUB_KEY}" \
                    "${DOCKERHUB_IMAGE}:backend-${IMAGE_TAG}"

                echo "Verifying frontend image signature..."

                docker run --rm \
                    --user "$(id -u):$(id -g)" \
                    --volumes-from "${JENKINS_CONTAINER}" \
                    -e HOME=/tmp \
                    -e DOCKER_CONFIG="${DOCKER_CONFIG}" \
                    gcr.io/projectsigstore/cosign:v2.4.1 \
                    verify \
                    --key "${COSIGN_PUB_KEY}" \
                    "${DOCKERHUB_IMAGE}:frontend-${IMAGE_TAG}"

                echo "SUCCESS: Both image signatures verified."
            '''
        }
    }

    stage('Deploy via Docker Compose') {
        echo "Deploying OpsDesk to ${DEPLOY_HOST}"

        sshagent(credentials: ['OpsDesk_Deployment_Server']) {
            sh '''
                set -eu

                mkdir -p ~/.ssh
                chmod 700 ~/.ssh

                touch ~/.ssh/known_hosts
                chmod 600 ~/.ssh/known_hosts

                ssh-keyscan -H "${DEPLOY_HOST}" >> ~/.ssh/known_hosts

                # Docker CLI uses Jenkins's registry login when pulling through
                # the production host's Docker daemon over SSH.
                IMAGES=$(ssh "${DEPLOY_USER}@${DEPLOY_HOST}" \
                    "cd '${DEPLOY_DIR}' && IMAGE_TAG='${IMAGE_TAG}' docker compose config --images")
                test -n "$IMAGES"

                for image in $IMAGES; do
                    echo "Pulling $image on the production Docker daemon..."
                    docker -H "ssh://${DEPLOY_USER}@${DEPLOY_HOST}" pull "$image"
                done

                ssh "${DEPLOY_USER}@${DEPLOY_HOST}" \
                    "export IMAGE_TAG='${IMAGE_TAG}' DEPLOY_DIR='${DEPLOY_DIR}'; bash -s" <<'REMOTE'

                set -eu

                cd "$DEPLOY_DIR"

                echo "Checking Docker Compose configuration..."

                docker compose config --quiet

                echo "Deploying OpsDesk containers..."

                docker compose up -d --pull never --no-build --remove-orphans

                echo "Deployment commands completed."

REMOTE
            '''
        }
    }

    stage('Verify Deployment') {
        sshagent(credentials: ['OpsDesk_Deployment_Server']) {
            sh '''
                set -eu

                ssh "${DEPLOY_USER}@${DEPLOY_HOST}" \
                    "export IMAGE_TAG='${IMAGE_TAG}' DEPLOY_DIR='${DEPLOY_DIR}'; bash -s" <<'REMOTE'

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

                HEALTH_DEADLINE=$(($(date +%s) + 180))

                for svc in db api worker beat web; do

                    CID=$(docker compose ps -aq "$svc")

                    if [ -z "$CID" ]; then
                        echo "ERROR: $svc container not found."
                        exit 1
                    fi

                    while :; do
                        STATUS=$(docker inspect -f '{{.State.Status}}' "$CID")
                        HEALTH=$(docker inspect \
                            -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$CID")

                        if [ "$STATUS" != "running" ]; then
                            echo "ERROR: $svc stopped (status: $STATUS)."
                            docker compose logs --tail=50 "$svc"
                            exit 1
                        fi

                        if [ "$HEALTH" = "healthy" ]; then
                            echo "$svc is healthy."
                            break
                        fi

                        if [ "$HEALTH" != "starting" ] || [ "$(date +%s)" -ge "$HEALTH_DEADLINE" ]; then
                            echo "ERROR: $svc did not become healthy (status: $HEALTH)."
                            docker compose logs --tail=50 "$svc"
                            exit 1
                        fi

                        echo "Waiting for $svc health check (status: $HEALTH)..."
                        sleep 5
                    done

                done

                # --------------------------------
                # Verify running services
                # --------------------------------

                for svc in redis mailpit; do

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

}

return this

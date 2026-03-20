#!/bin/bash
# Start all backend services locally with correct env overrides
# Usage: ./start-local.sh [service-name]  (or no args to start all)

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"

# Load .env
set -a
source "$BACKEND_DIR/.env"
set +a

# Override Docker hostnames for local development
export EUREKA_SERVER_URL="http://localhost:8761/eureka"
export SERVICE_AUTH_JWK_SET_URI="http://127.0.0.1:8088/oauth2/jwks"
export PERMISSION_SERVICE_BASE_URL="http://127.0.0.1:8084"
export SECURITY_JWT_JWK_SET_URI="http://localhost:8081/realms/serban/protocol/openid-connect/certs"
export SECURITY_JWT_ISSUER="http://localhost:8081/realms/serban"
export SECURITY_JWT_AUDIENCE="none"
export AUTH_LOCAL_DB_URL="jdbc:postgresql://localhost:5432/users"

start_service() {
    local name=$1
    local port=$2
    local profile=${3:-""}
    local dir="$BACKEND_DIR/$name"

    if ! [ -d "$dir" ]; then
        echo "  [SKIP] $name (directory not found)"
        return
    fi

    # Kill existing
    local pid=$(lsof -ti:$port 2>/dev/null)
    if [ -n "$pid" ]; then
        kill $pid 2>/dev/null
        sleep 1
    fi

    local profile_arg=""
    if [ -n "$profile" ]; then
        profile_arg="-Dspring-boot.run.profiles=$profile"
    fi

    echo "  [START] $name on port $port..."
    cd "$dir"
    nohup mvn spring-boot:run $profile_arg -q > /tmp/$name.log 2>&1 &
    echo "  [PID] $name: $!"
}

wait_for_service() {
    local name=$1
    local port=$2
    local max=${3:-30}
    for i in $(seq 1 $max); do
        curl -s http://localhost:$port/actuator/health 2>/dev/null | grep -q "UP" && echo "  [UP] $name" && return 0
        sleep 2
    done
    echo "  [TIMEOUT] $name did not start in time"
    return 1
}

TARGET=${1:-all}

if [ "$TARGET" = "all" ] || [ "$TARGET" = "discovery-server" ]; then
    # Check if discovery already running
    if lsof -ti:8761 >/dev/null 2>&1; then
        echo "[OK] discovery-server already running"
    else
        start_service discovery-server 8761
        wait_for_service discovery-server 8761 20
    fi
fi

if [ "$TARGET" = "all" ]; then
    start_service auth-service 8088
    start_service user-service 8089
    start_service permission-service 8084
    start_service report-service 8095 dev

    echo ""
    echo "Waiting for services..."
    wait_for_service auth-service 8088 40
    wait_for_service user-service 8089 40
    wait_for_service permission-service 8084 40
    wait_for_service report-service 8095 40

    # Start gateway last (needs other services registered)
    sleep 5
    start_service api-gateway 8080
    wait_for_service api-gateway 8080 30

    echo ""
    echo "=== Eureka Registered Services ==="
    curl -s http://localhost:8761/eureka/apps 2>/dev/null | grep -o "<name>[^<]*</name>" | sed 's/<[^>]*>//g' | sort
else
    start_service "$TARGET" "${2:-8080}" "${3:-}"
    wait_for_service "$TARGET" "${2:-8080}" 40
fi

echo ""
echo "Done! Logs: /tmp/{service-name}.log"

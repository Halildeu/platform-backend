#!/bin/bash
# VPN & DB connectivity checker
# Usage: ./check-vpn.sh [--watch]

DB_HOST="${REPORT_MSSQL_HOST:-10.9.193.201}"
DB_PORT="${REPORT_MSSQL_PORT:-1433}"
TIMEOUT=3

check() {
    nc -z -w$TIMEOUT "$DB_HOST" "$DB_PORT" 2>/dev/null
    if [ $? -eq 0 ]; then
        echo "$(date '+%H:%M:%S') [OK] DB reachable at $DB_HOST:$DB_PORT"
        return 0
    else
        echo "$(date '+%H:%M:%S') [FAIL] DB unreachable at $DB_HOST:$DB_PORT — check VPN"
        return 1
    fi
}

if [ "$1" = "--watch" ]; then
    INTERVAL="${2:-30}"
    echo "Watching DB connectivity every ${INTERVAL}s (Ctrl+C to stop)"
    echo "---"
    while true; do
        check
        sleep "$INTERVAL"
    done
else
    check
fi

#!/bin/bash
set -euo pipefail

docker compose stop
docker compose up -d

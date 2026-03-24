#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    SELECT 'CREATE DATABASE ragnarok_antifraude_db'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ragnarok_antifraude_db')\gexec

    SELECT 'CREATE DATABASE ragnarok_core_db'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'ragnarok_core_db')\gexec
EOSQL

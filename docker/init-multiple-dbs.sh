#!/bin/bash
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE ragnarok_db;
    CREATE DATABASE ragnarok_antifraude_db;
EOSQL

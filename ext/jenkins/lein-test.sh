#!/usr/bin/env bash

set -e
set -x

DBNAME=$(echo "puppetdb_${PUPPETDB_BRANCH}_${BUILD_ID}_${BUILD_NUMBER}_${PUPPETDB_DBTYPE}_${JDK}" | tr - _)
DBUSER="puppetdb"
DBHOST="fixture-pg94.delivery.puppetlabs.net"
DBPORT="5432"

# Set up project specific database variables
export PUPPETDB_DBUSER="$DBUSER"
export PUPPETDB_DBPASSWORD="puppetdb137"
export PGPASSWORD="puppetdb137"
export PUPPETDB_DBSUBNAME="//${DBHOST}:${DBPORT}/${DBNAME}"

rm -f testreports.xml *.war *.jar

export HTTP_CLIENT="wget --no-check-certificate -O"

psql -h "${DBHOST}" -U "${DBUSER}" -d postgres -c "create database ${DBNAME}"

lein --version
lein clean
lein deps
lein compile

# Sadly, no JUnit output at this point in time.
lein test

# Clean up our database
psql -h "${DBHOST}" -U "${DBUSER}" -d postgres -c "drop database ${DBNAME}"

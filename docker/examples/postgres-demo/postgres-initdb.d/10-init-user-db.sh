#!/bin/bash
#####################################################################
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#####################################################################

# Provisions the three OFBiz databases of the compose example with TWO accounts each.
#
# This script used to create one account per database and give it ALL PRIVILEGES, which is the
# privilege set the container needs while it is CREATING the schema, not the one it needs while it is
# serving traffic. Rendering check-on-start="false" and add-missing-on-start="false" stops a serving
# instance from ISSUING DDL, but as long as it authenticates as an account that MAY issue DDL, anything
# that reaches the instance or reads its environment inherits that privilege.
#
# So each database gets:
#
#   * an initialization role, which OWNS the database and its public schema and may therefore create,
#     alter and drop objects. Only the one-shot OFBIZ_SCHEMA_INIT=true execution authenticates as it.
#   * a serving role, which may read and write rows and may NOT create, alter or drop anything. Every
#     instance that serves traffic authenticates as this one.
#
# ALTER DEFAULT PRIVILEGES is what makes this maintainable: the serving role is granted its row
# privileges BEFORE the tables exist, so the schema the init execution creates afterwards is
# immediately readable and writable by the serving role without any follow-up grant.
#
# See "Database roles and least privilege" in DOCKER.adoc for the same grants written out for a single
# database, and for how to verify the result.
#
# Every value is taken from the environment - docker-compose.yml passes the same variables to this
# service and to the OFBiz services, so the roles created here are the roles OFBiz authenticates as.
# Values are handed to psql as psql variables and interpolated with :"name" for identifiers and
# :'name' for literals, so psql does the quoting. Building the SQL by shell expansion instead would
# break on a password containing a quote, and would put the password in this script's own text.

set -e

for ofbizPostgresGroup in OFBIZ OLAP TENANT; do
  ofbizPostgresDatabaseVariable="OFBIZ_POSTGRES_${ofbizPostgresGroup}_DB"
  ofbizPostgresServingUserVariable="OFBIZ_POSTGRES_${ofbizPostgresGroup}_USER"
  ofbizPostgresServingPasswordVariable="OFBIZ_POSTGRES_${ofbizPostgresGroup}_PASSWORD"
  ofbizPostgresInitUserVariable="OFBIZ_POSTGRES_${ofbizPostgresGroup}_INIT_USER"
  ofbizPostgresInitPasswordVariable="OFBIZ_POSTGRES_${ofbizPostgresGroup}_INIT_PASSWORD"

  # Refused rather than defaulted. A missing value here would either create a role nobody
  # authenticates as, or - worse, for a password - create one with no password at all.
  for ofbizPostgresRequiredVariable in "$ofbizPostgresDatabaseVariable" \
      "$ofbizPostgresServingUserVariable" "$ofbizPostgresServingPasswordVariable" \
      "$ofbizPostgresInitUserVariable" "$ofbizPostgresInitPasswordVariable"; do
    if [ -z "${!ofbizPostgresRequiredVariable}" ]; then
      printf 'ERROR: %s is not set, so the %s database cannot be provisioned.\n' \
        "$ofbizPostgresRequiredVariable" "$ofbizPostgresGroup" >&2
      exit 1
    fi
  done

  ofbizPostgresDatabase="${!ofbizPostgresDatabaseVariable}"

  # The initialization role must not BE the serving role, or the separation is decorative. The entry
  # point refuses that configuration too; refusing it here as well means the databases are never
  # provisioned in a shape the container would go on to reject.
  if [ "${!ofbizPostgresInitUserVariable}" = "${!ofbizPostgresServingUserVariable}" ]; then
    printf 'ERROR: %s and %s name the same role, so the %s database would be served by the account that may alter its schema.\n' \
      "$ofbizPostgresInitUserVariable" "$ofbizPostgresServingUserVariable" \
      "$ofbizPostgresGroup" >&2
    exit 1
  fi

  # Cluster-wide objects: the two roles, the database, and who may connect to it. Run against the
  # maintenance database because the target database does not exist yet.
  psql -v ON_ERROR_STOP=1 --username "postgres" --dbname "postgres" \
    --set=initRole="${!ofbizPostgresInitUserVariable}" \
    --set=initPassword="${!ofbizPostgresInitPasswordVariable}" \
    --set=servingRole="${!ofbizPostgresServingUserVariable}" \
    --set=servingPassword="${!ofbizPostgresServingPasswordVariable}" \
    --set=database="$ofbizPostgresDatabase" <<-'EOSQL'
	CREATE ROLE :"initRole" LOGIN PASSWORD :'initPassword';
	CREATE ROLE :"servingRole" LOGIN PASSWORD :'servingPassword';

	-- Owned by the initialization role, so that role can create and alter objects in it without any
	-- explicit grant, and no other role can.
	CREATE DATABASE :"database" OWNER :"initRole";

	-- PUBLIC may connect to any database and create temporary objects in it unless told otherwise.
	-- Say otherwise, then grant back only what each role needs. TEMPORARY permits session-local
	-- objects only, which cannot alter the persistent schema.
	REVOKE ALL ON DATABASE :"database" FROM PUBLIC;
	GRANT CONNECT, TEMPORARY ON DATABASE :"database" TO :"initRole";
	GRANT CONNECT, TEMPORARY ON DATABASE :"database" TO :"servingRole";
	EOSQL

  # Schema-level privileges, which have to be set from inside the database they belong to. "public" is
  # the schema the managed datasources are rendered with (schema-name="public").
  psql -v ON_ERROR_STOP=1 --username "postgres" --dbname "$ofbizPostgresDatabase" \
    --set=initRole="${!ofbizPostgresInitUserVariable}" \
    --set=servingRole="${!ofbizPostgresServingUserVariable}" <<-'EOSQL'
	-- Before PostgreSQL 15 every role may create objects in "public", which would hand the serving
	-- role exactly the DDL this arrangement removes. USAGE without CREATE lets it reach the objects
	-- in the schema without being able to add, rename or drop any.
	ALTER SCHEMA public OWNER TO :"initRole";
	REVOKE ALL ON SCHEMA public FROM PUBLIC;
	GRANT USAGE ON SCHEMA public TO :"servingRole";

	-- Rows yes, tables no - and granted ahead of time, so the tables the init execution creates later
	-- are writable by the serving role the moment they exist.
	ALTER DEFAULT PRIVILEGES FOR ROLE :"initRole" IN SCHEMA public
	    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"servingRole";
	ALTER DEFAULT PRIVILEGES FOR ROLE :"initRole" IN SCHEMA public
	    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"servingRole";

	-- No-ops on the empty database this script provisions, and the reason the same statements are
	-- also correct against a database that already carries a schema.
	GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"servingRole";
	GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :"servingRole";
	EOSQL

  printf 'Provisioned %s: schema owned by %s, served by %s (no DDL).\n' \
    "$ofbizPostgresDatabase" "${!ofbizPostgresInitUserVariable}" \
    "${!ofbizPostgresServingUserVariable}"
done

# The PostgreSQL image sources this file into its own entry point rather than executing it, so leave
# nothing behind - a stray password in a shell variable outlives this script otherwise.
unset ofbizPostgresGroup ofbizPostgresDatabaseVariable ofbizPostgresServingUserVariable \
  ofbizPostgresServingPasswordVariable ofbizPostgresInitUserVariable \
  ofbizPostgresInitPasswordVariable ofbizPostgresRequiredVariable ofbizPostgresDatabase

Node database
=============

Default in-memory database
--------------------------
By default, nodes store their data in an H2 database. See :doc:`node-database-access-h2`.

.. _standalone_database_config_examples_ref:

Standalone database
-------------------

Running a node against a standalone database requires the following setup steps:

* A database administrator needs to create database users/logins, an empty schema and permissions on the custom database.
  Database user may be set with different permissions:

  * Administrative permissions used for initial database setup (e.g. to create tables) - more flexible as allows the node
    to create all tables during initial startup and it follows node behavior when using in-memory H2 database.
  * Restricted permission for normal node operation to select/insert/delete data. It requires a database administrator
    to create tables/sequences using :ref:`Database management tool <migration-tool>`.

  The example DDL scripts (shown below) contain both variants of database user setup.
* Add node JDBC connection properties to the `dataSourceProperties` entry and Hibernate properties to the `database` entry - see :ref:`Node configuration <database_properties_ref>`.
  Each node needs to use a separate database schema which requires a separate database user/login with a default schema set.
  Properties can be generated with the :ref:`deployNodes Cordform task <testing_cordform_ref>`.
* The Corda distribution does not include any JDBC drivers with the exception of the H2 driver used by samples.
  It is the responsibility of the node administrator to download the appropriate JDBC drivers and configure the database settings.
  Corda will search for valid JDBC drivers under the ``./drivers`` subdirectory of the node base directory.
  Corda distributed via published artifacts (e.g. added as Gradle dependency) will also search for the paths specified by the ``jarDirs`` field of the node configuration.
  The ``jarDirs`` property is a list of paths, separated by commas and wrapped in single quotes e.g. `jarDirs = [ '/lib/jdbc/driver' ]`.
* When a node reuses an existing database (e.g. frequent tests when developing a Cordapp), the data is not deleted by the node at startup.
  E.g. ``Cordform`` Gradle task always delete existing H2 database data file, while a remote database is not altered.
  Ensure that in such cases the database rows have been deleted or all tables and sequences were dropped.

Example configuration for supported standalone databases are shown below.
In each configuration replace placeholders `[USER]`, `[PASSWORD]` and `[SCHEMA]`.

.. note::
   SQL database schema setup scripts doesn't use grouping roles and doesn't contain database physical settings e.g. max disk space quota for a user.

SQL Azure and SQL Server
````````````````````````
Corda has been tested with SQL Server 2017 (14.0.3006.16) and Azure SQL (12.0.2000.8), using Microsoft JDBC Driver 6.2.

To set up a database schema with administrative permissions, run the following SQL:

.. sourcecode:: sql

    --for Azure SQL, a login needs to be created on the master database and not on a user database
    CREATE LOGIN [LOGIN] WITH PASSWORD = [PASSWORD];
    CREATE SCHEMA [SCHEMA];
    CREATE USER [USER] FOR LOGIN [SCHEMA] WITH DEFAULT_SCHEMA = [SCHEMA];
    GRANT SELECT, INSERT, UPDATE, DELETE, VIEW DEFINITION, ALTER, REFERENCES ON SCHEMA::[SCHEMA] TO [USER];
    GRANT CREATE TABLE TO [USER];

To set up a database schema with normal operation permissions, run the following SQL:

.. sourcecode:: sql

    --for Azure SQL, a login needs to be created on the master database and not on a user database
    CREATE LOGIN [LOGIN] WITH PASSWORD = '[PASSWORD]';
    CREATE SCHEMA [SCHEMA];
    CREATE USER [USER] FOR LOGIN [LOGIN] WITH DEFAULT_SCHEMA = [SCHEMA];
    GRANT SELECT, INSERT, UPDATE, DELETE, VIEW DEFINITION, REFERENCES ON SCHEMA::[SCHEMA] TO [USER];

Example node configuration for SQL Azure:

.. sourcecode:: none

    dataSourceProperties = {
        dataSourceClassName = "com.microsoft.sqlserver.jdbc.SQLServerDataSource"
        dataSource.url = "jdbc:sqlserver://[DATABASE_SERVER].database.windows.net:1433;databaseName=[DATABASE];
            encrypt=true;trustServerCertificate=false;hostNameInCertificate=*.database.windows.net;loginTimeout=30"
        dataSource.user = [USER]
        dataSource.password = [PASSWORD]
    }
    database = {
        transactionIsolationLevel = READ_COMMITTED
        schema = [SCHEMA]
        runMigration = [true|false]
    }

Note that:

* The ``runMigration`` is `false` or may be omitted for node setup with normal operation permissions
* The ``database.schema`` property is optional
* The minimum transaction isolation level ``database.transactionIsolationLevel`` is `READ_COMMITTED`
* Ensure that the Microsoft JDBC driver JAR is copied to the ``./drivers`` subdirectory or if applicable specify a path in the ``jarDirs`` property,
  the driver can be downloaded from `Microsoft Download Center <https://www.microsoft.com/en-us/download/details.aspx?id=55539>`_,
  extract the archive and copy the single file ``mssql-jdbc-6.2.2.jre8.jar`` as the archive comes with two JAR versions

Example dataSource.url for SQL Server:

.. sourcecode:: none

    dataSource.url = "jdbc:sqlserver://[HOST]:[PORT];databaseName=[DATABASE_NAME]"

Note that:

* By default the connection to the database is not SSL, for securing JDBC connection refer to
  `Securing JDBC Driver Application <https://docs.microsoft.com/en-us/sql/connect/jdbc/securing-jdbc-driver-applications?view=sql-server-2017>`_,
* Ensure JDBC connection properties match the SQL Server setup, especially when trying to reuse JDBC URL format valid for Azure SQL,
  as misconfiguration may prevent Corda node to start with supposedly unrelated error message e.g.:
  `Caused by: org.hibernate.HibernateException: Access to DialectResolutionInfo cannot be null when 'hibernate.dialect' not set`

To delete existing data from the database, run the following SQL:

.. sourcecode:: sql

    DROP TABLE IF EXISTS [SCHEMA].cash_state_participants;
    DROP TABLE IF EXISTS [SCHEMA].cash_states_v2_participants;
    DROP TABLE IF EXISTS [SCHEMA].cp_states_v2_participants;
    DROP TABLE IF EXISTS [SCHEMA].dummy_linear_state_parts;
    DROP TABLE IF EXISTS [SCHEMA].dummy_linear_states_v2_parts;
    DROP TABLE IF EXISTS [SCHEMA].dummy_deal_states_parts;
    DROP TABLE IF EXISTS [SCHEMA].node_attachments_contracts;
    DROP TABLE IF EXISTS [SCHEMA].node_attachments;
    DROP TABLE IF EXISTS [SCHEMA].node_checkpoints;
    DROP TABLE IF EXISTS [SCHEMA].node_transactions;
    DROP TABLE IF EXISTS [SCHEMA].node_message_retry;
    DROP TABLE IF EXISTS [SCHEMA].node_message_ids;
    DROP TABLE IF EXISTS [SCHEMA].vault_states;
    DROP TABLE IF EXISTS [SCHEMA].node_our_key_pairs;
    DROP TABLE IF EXISTS [SCHEMA].node_scheduled_states;
    DROP TABLE IF EXISTS [SCHEMA].node_network_map_nodes;
    DROP TABLE IF EXISTS [SCHEMA].node_network_map_subscribers;
    DROP TABLE IF EXISTS [SCHEMA].node_notary_committed_states;
    DROP TABLE IF EXISTS [SCHEMA].node_notary_request_log;
    DROP TABLE IF EXISTS [SCHEMA].node_transaction_mappings;
    DROP TABLE IF EXISTS [SCHEMA].vault_fungible_states_parts;
    DROP TABLE IF EXISTS [SCHEMA].vault_linear_states_parts;
    DROP TABLE IF EXISTS [SCHEMA].vault_fungible_states;
    DROP TABLE IF EXISTS [SCHEMA].vault_linear_states;
    DROP TABLE IF EXISTS [SCHEMA].node_bft_committed_states;
    DROP TABLE IF EXISTS [SCHEMA].node_raft_committed_states;
    DROP TABLE IF EXISTS [SCHEMA].vault_transaction_notes;
    DROP TABLE IF EXISTS [SCHEMA].link_nodeinfo_party;
    DROP TABLE IF EXISTS [SCHEMA].node_link_nodeinfo_party;
    DROP TABLE IF EXISTS [SCHEMA].node_info_party_cert;
    DROP TABLE IF EXISTS [SCHEMA].node_info_hosts;
    DROP TABLE IF EXISTS [SCHEMA].node_infos;
    DROP TABLE IF EXISTS [SCHEMA].cp_states;
    DROP TABLE IF EXISTS [SCHEMA].node_contract_upgrades;
    DROP TABLE IF EXISTS [SCHEMA].node_identities;
    DROP TABLE IF EXISTS [SCHEMA].node_named_identities;
    DROP TABLE IF EXISTS [SCHEMA].node_properties;
    DROP TABLE IF EXISTS [SCHEMA].children;
    DROP TABLE IF EXISTS [SCHEMA].parents;
    DROP TABLE IF EXISTS [SCHEMA].contract_cash_states;
    DROP TABLE IF EXISTS [SCHEMA].contract_cash_states_v1;
    DROP TABLE IF EXISTS [SCHEMA].messages;
    DROP TABLE IF EXISTS [SCHEMA].state_participants;
    DROP TABLE IF EXISTS [SCHEMA].cash_states_v2;
    DROP TABLE IF EXISTS [SCHEMA].cash_states_v3;
    DROP TABLE IF EXISTS [SCHEMA].cp_states_v1;
    DROP TABLE IF EXISTS [SCHEMA].cp_states_v2;
    DROP TABLE IF EXISTS [SCHEMA].dummy_deal_states;
    DROP TABLE IF EXISTS [SCHEMA].dummy_linear_states;
    DROP TABLE IF EXISTS [SCHEMA].dummy_linear_states_v2;
    DROP TABLE IF EXISTS [SCHEMA].dummy_test_states_parts;
    DROP TABLE IF EXISTS [SCHEMA].dummy_test_states;
    DROP TABLE IF EXISTS [SCHEMA].node_mutual_exclusion;
    DROP TABLE IF EXISTS [SCHEMA].DATABASECHANGELOG;
    DROP TABLE IF EXISTS [SCHEMA].DATABASECHANGELOGLOCK;
    DROP TABLE IF EXISTS [SCHEMA].cert_revocation_request_AUD;
    DROP TABLE IF EXISTS [SCHEMA].cert_signing_request_AUD;
    DROP TABLE IF EXISTS [SCHEMA].network_map_AUD;
    DROP TABLE IF EXISTS [SCHEMA].REVINFO;
    DROP TABLE IF EXISTS [SCHEMA].cert_revocation_request;
    DROP TABLE IF EXISTS [SCHEMA].cert_data;
    DROP TABLE IF EXISTS [SCHEMA].cert_revocation_list;
    DROP TABLE IF EXISTS [SCHEMA].node_info;
    DROP TABLE IF EXISTS [SCHEMA].cert_signing_request;
    DROP TABLE IF EXISTS [SCHEMA].network_map;
    DROP TABLE IF EXISTS [SCHEMA].parameters_update;
    DROP TABLE IF EXISTS [SCHEMA].network_parameters;
    DROP TABLE IF EXISTS [SCHEMA].private_network;
    DROP SEQUENCE [SCHEMA].hibernate_sequence;

Oracle
``````
Corda supports Oracle 11g RC2 (with ojdbc6.jar) and Oracle 12c (ojdbc8.jar).

To set up a database schema with administrative permissions, run the following SQL:

.. sourcecode:: sql

    CREATE USER [USER] IDENTIFIED BY [PASSWORD];
    GRANT UNLIMITED TABLESPACE TO [USER];
    GRANT CREATE SESSION TO [USER];
    GRANT CREATE TABLE TO [USER];
    GRANT CREATE SEQUENCE TO [USER];

To set up a database schema with normal operation permissions:

In Oracle database a user has full control over own schema because a schema is essentially the user account.
In order to restrict the permissions two the database, two users needs to be created, the first one with administrative permissions (`ADMIN_USER` in SQL script),
the second one with operation permissions (`USER` in the SQL script). Corda node will access the tables
in the schema of the other user for which it has permissions to select/insert/delete data.

.. sourcecode:: sql

    CREATE USER [ADMIN_USER] IDENTIFIED BY [PASSWORD];
    GRANT UNLIMITED TABLESPACE TO [ADMIN_USER];
    GRANT CREATE SESSION TO [ADMIN_USER];
    GRANT CREATE TABLE TO [ADMIN_USER];
    GRANT CREATE SEQUENCE TO [ADMIN_USER];

    CREATE USER [USER] identified by [PASSWORD];
    GRANT CREATE SESSION TO [USER];
    -- repeat for each table
    GRANT SELECT, INSERT, UPDATE, DELETE ANY [ADMIN_USER].[TABLE] TO [USER];
    GRANT SELECT ANY SEQUENCE TO [USER];

When connecting via database user with normal operation permissions, all queries needs to be prefixed with the other schema name.
Corda node doesn't guarantee to prefix each SQL query with a schema namespace.
Additional node configuration entry allows to set current schema to ADMIN_USER while connecting to the database:

.. sourcecode:: none

    dataSourceProperties {
        [...]
        connectionInitSql="alter session set current_schema=[ADMIN_USER]"
    }

To allow VARCHAR2 and NVARCHAR2 column types to store more than 2000 characters ensure the database instance is configured to use
extended data types, e.g. for Oracle 12.1 refer to `MAX_STRING_SIZE <https://docs.oracle.com/database/121/REFRN/GUID-D424D23B-0933-425F-BC69-9C0E6724693C.htm#REFRN10321>`_.

Example node configuration for Oracle:

.. sourcecode:: none

    dataSourceProperties = {
        dataSourceClassName = "oracle.jdbc.pool.OracleDataSource"
        dataSource.url = "jdbc:oracle:thin:@[IP]:[PORT]:xe"
        dataSource.user = [USER]
        dataSource.password = [PASSWORD]
    }
    database = {
        transactionIsolationLevel = READ_COMMITTED
        schema = [SCHEMA]
        runMigration = [true|false]
    }

Note that:

* SCHEMA name equals to USER name if the schema was setup with administrative permissions (see the first DDL snippet for Oracle)
* The ``runMigration`` is `false` or may be omitted for node setup with normal operation permissions
* The ``database.schema`` property is optional
* The minimum transaction isolation level ``database.transactionIsolationLevel`` is `READ_COMMITTED`
* Ensure that the Oracle JDBC driver JAR is copied to the ``./drivers`` subdirectory or if applicable specify path in the ``jarDirs`` property

To delete existing data from the database, run the following SQL:

.. sourcecode:: sql

    DROP TABLE [USER].cash_state_participants CASCADE CONSTRAINTS;
    DROP TABLE [USER].cash_states_v2_participants CASCADE CONSTRAINTS;
    DROP TABLE [USER].cp_states_v2_participants CASCADE CONSTRAINTS;
    DROP TABLE [USER].dummy_linear_state_parts CASCADE CONSTRAINTS;
    DROP TABLE [USER].dummy_linear_states_v2_parts CASCADE CONSTRAINTS;
    DROP TABLE [USER].dummy_deal_states_parts CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_attchments_contracts CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_attachments CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_checkpoints CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_transactions CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_message_retry CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_message_ids CASCADE CONSTRAINTS;
    DROP TABLE [USER].vault_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_our_key_pairs CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_scheduled_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_network_map_nodes CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_network_map_subscribers CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_notary_committed_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_notary_request_log CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_transaction_mappings CASCADE CONSTRAINTS;
    DROP TABLE [USER].vault_fungible_states_parts CASCADE CONSTRAINTS;
    DROP TABLE [USER].vault_linear_states_parts CASCADE CONSTRAINTS;
    DROP TABLE [USER].vault_fungible_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].vault_linear_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_bft_committed_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_raft_committed_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].vault_transaction_notes CASCADE CONSTRAINTS;
    DROP TABLE [USER].link_nodeinfo_party CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_link_nodeinfo_party CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_info_party_cert CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_info_hosts CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_infos CASCADE CONSTRAINTS;
    DROP TABLE [USER].cp_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_contract_upgrades CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_identities CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_named_identities CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_properties CASCADE CONSTRAINTS;
    DROP TABLE [USER].children CASCADE CONSTRAINTS;
    DROP TABLE [USER].parents CASCADE CONSTRAINTS;
    DROP TABLE [USER].contract_cash_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].contract_cash_states_v1 CASCADE CONSTRAINTS;
    DROP TABLE [USER].messages CASCADE CONSTRAINTS;
    DROP TABLE [USER].state_participants CASCADE CONSTRAINTS;
    DROP TABLE [USER].cash_states_v2 CASCADE CONSTRAINTS;
    DROP TABLE [USER].cash_states_v3 CASCADE CONSTRAINTS;
    DROP TABLE [USER].cp_states_v1 CASCADE CONSTRAINTS;
    DROP TABLE [USER].cp_states_v2 CASCADE CONSTRAINTS;
    DROP TABLE [USER].dummy_deal_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].dummy_linear_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].dummy_linear_states_v2 CASCADE CONSTRAINTS;
    DROP TABLE [USER].dummy_test_states_parts CASCADE CONSTRAINTS;
    DROP TABLE [USER].dummy_test_states CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_mutual_exclusion CASCADE CONSTRAINTS;
    DROP TABLE [USER].DATABASECHANGELOG CASCADE CONSTRAINTS;
    DROP TABLE [USER].DATABASECHANGELOGLOCK CASCADE CONSTRAINTS;
    DROP TABLE [USER].cert_revocation_request_AUD CASCADE CONSTRAINTS;
    DROP TABLE [USER].cert_signing_request_AUD CASCADE CONSTRAINTS;
    DROP TABLE [USER].network_map_AUD CASCADE CONSTRAINTS;
    DROP TABLE [USER].REVINFO CASCADE CONSTRAINTS;
    DROP TABLE [USER].cert_revocation_request CASCADE CONSTRAINTS;
    DROP TABLE [USER].cert_data CASCADE CONSTRAINTS;
    DROP TABLE [USER].cert_revocation_list CASCADE CONSTRAINTS;
    DROP TABLE [USER].node_info CASCADE CONSTRAINTS;
    DROP TABLE [USER].cert_signing_request CASCADE CONSTRAINTS;
    DROP TABLE [USER].network_map CASCADE CONSTRAINTS;
    DROP TABLE [USER].parameters_update CASCADE CONSTRAINTS;
    DROP TABLE [USER].network_parameters CASCADE CONSTRAINTS;
    DROP TABLE [USER].private_network CASCADE CONSTRAINTS;
    DROP SEQUENCE [USER].hibernate_sequence;

.. _postgres_ref:

PostgreSQL
``````````
Corda has been tested on PostgreSQL 9.6 database, using PostgreSQL JDBC Driver 42.1.4.

To set up a database schema with administration permissions:

.. sourcecode:: sql

    CREATE USER "[USER]" WITH LOGIN password '[PASSWORD]';
    CREATE SCHEMA "[SCHEMA]";
    GRANT USAGE, CREATE ON SCHEMA "[SCHEMA]" TO "[USER]";
    GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES ON ALL tables IN SCHEMA "[SCHEMA]" TO "[USER]";
    ALTER DEFAULT privileges IN SCHEMA "[SCHEMA]" GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES ON tables TO "[USER]";
    GRANT USAGE, SELECT ON ALL sequences IN SCHEMA "[SCHEMA]" TO "[USER]";
    ALTER DEFAULT privileges IN SCHEMA "[SCHEMA]" GRANT USAGE, SELECT ON sequences TO "[USER]";
    ALTER ROLE "[USER]" SET search_path = "[SCHEMA]";

To set up a database schema with normal operation permissions:
The setup differs with admin access by lack of schema permission of CREATE.

.. sourcecode:: sql

    CREATE USER "[USER]" WITH LOGIN password '[PASSWORD]';
    CREATE SCHEMA "[SCHEMA]";
    GRANT USAGE ON SCHEMA "[SCHEMA]" TO "[USER]";
    GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES ON ALL tables IN SCHEMA "[SCHEMA]" TO "[USER]";
    ALTER DEFAULT privileges IN SCHEMA "[SCHEMA]" GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES ON tables TO "[USER]";
    GRANT USAGE, SELECT ON ALL sequences IN SCHEMA "[SCHEMA]" TO "[USER]";
    ALTER DEFAULT privileges IN SCHEMA "[SCHEMA]" GRANT USAGE, SELECT ON sequences TO "[USER]";
    ALTER ROLE "[USER]" SET search_path = "[SCHEMA]";


Example node configuration for PostgreSQL:

.. sourcecode:: none

    dataSourceProperties = {
        dataSourceClassName = "org.postgresql.ds.PGSimpleDataSource"
        dataSource.url = "jdbc:postgresql://[HOST]:[PORT]/postgres"
        dataSource.user = [USER]
        dataSource.password = [PASSWORD]
    }
    database = {
        transactionIsolationLevel = READ_COMMITTED
        schema = [SCHEMA]
        runMigration = [true|false]
    }

Note that:

* The ``runMigration`` is `false` or may be omitted for node setup with normal operation permissions
* The ``database.schema`` property is optional
* If you provide a custom ``database.schema``, its value must either match the ``dataSource.user`` value to end up
  on the standard schema search path according to the
  `PostgreSQL documentation <https://www.postgresql.org/docs/9.3/static/ddl-schemas.html#DDL-SCHEMAS-PATH>`_, or
  the schema search path must be set explicitly via the ``ALTER ROLE "[USER]" SET search_path = "[SCHEMA]"`` statement.
* The value of ``database.schema`` is automatically wrapped in double quotes to preserve case-sensitivity
  (e.g. `AliceCorp` becomes `"AliceCorp"`, without quotes PostgresSQL would treat the value as `alicecorp`),
  this behaviour differs from Corda Open Source where the value is not wrapped in double quotes
* Ensure that the PostgreSQL JDBC driver JAR is copied to the ``./drivers`` subdirectory or if applicable specify path in the ``jarDirs`` property

To delete existing data from the database, drop the existing schema and recreate it using the relevant setup script:

.. sourcecode:: sql

    DROP SCHEMA IF EXISTS "[SCHEMA]" CASCADE;


Guideline for adding support for other databases
````````````````````````````````````````````````

The Corda distribution can be extended to support other databases without recompilation.
This assumes that all SQL queries run by Corda are compatible with the database and the JDBC driver doesn't require any custom serialization.
To add support for another database to a Corda node, the following JAR files must be provisioned:

* JDBC driver compatible with JDBC 4.2
* Hibernate dialect
* Liquibase extension for the database management (https://www.liquibase.org)
* Implementation of database specific Cash Selection SQL query.
  Class with SQL query needs to extend the ``net.corda.finance.contracts.asset.cash.selection.AbstractCashSelection`` class:

  .. sourcecode:: kotlin

      package net.corda.finance.contracts.asset.cash.selection
      //...
      class CashSelectionCustomDatabaseImpl : AbstractCashSelection() {
            //...
      }

  The ``corda-finance`` module contains ``AbstractCashSelection`` class, so it needs to be added to your project, e.g. when using Gradle:

  .. sourcecode:: groovy

      compile "com.r3.corda:corda-finance:$corda_version"

  The compiled JAR needs to contain a ``resources/META-INF/net.corda.finance.contracts.asset.cash.selection.AbstractCashSelection`` file
  with a class entry to inform the Corda node about the class at startup:

  .. sourcecode:: none

     net.corda.finance.contracts.asset.cash.selection.CashSelectionCustomDatabaseImpl

All additional JAR files need to be copy into ``./drivers`` subdirectory of the node.

.. note:: This is a general guideline. In some cases, it might not be feasible to add support for your desired database without recompiling the Corda source code.
package com.swiftmako.jormanager.services

import java.net.URI
import java.sql.DriverManager
import java.util.UUID
import liquibase.changelog.ChangeLogParameters
import liquibase.command.CommandScope
import liquibase.database.DatabaseFactory
import liquibase.database.OfflineConnection
import liquibase.database.jvm.JdbcConnection
import liquibase.parser.ChangeLogParserFactory
import liquibase.resource.ClassLoaderResourceAccessor
import liquibase.sqlgenerator.SqlGeneratorFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class AuthorizedOperationMigrationTest {
    @Test
    fun `changelog 048 is registered after 047 and generates drops for unused dijkstra tables and columns`() {
        val accessor = ClassLoaderResourceAccessor()
        val database =
            DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                OfflineConnection("offline:postgresql", accessor),
            )
        val master =
            ChangeLogParserFactory
                .getInstance()
                .getParser("db/liquibase-changelog.xml", accessor)
                .parse("db/liquibase-changelog.xml", ChangeLogParameters(database), accessor)

        val paths = master.changeSets.map { it.filePath }
        val index047 = paths.indexOfLast { it.endsWith("047-remove-authorized-operations.xml") }
        val index048 = paths.indexOfFirst { it.endsWith("048-remove-unused-dijkstra-tables.xml") }
        assertTrue(index047 >= 0, "Expected 047 changelog in master changelog")
        assertTrue(index048 > index047, "Expected 048 changelog to be registered after 047")

        val sql =
            master.changeSets
                .filter { it.filePath.endsWith("048-remove-unused-dijkstra-tables.xml") }
                .flatMap { changeSet ->
                    changeSet.changes.flatMap { change ->
                        SqlGeneratorFactory.getInstance().generateSql(change, database).map { it.toSql() }
                    }
                }.joinToString("\n")

        assertTrue(sql.contains("nodes_configuration_bundle_fk"), sql)
        assertTrue(sql.contains("configuration_bundle_id"), sql)
        assertTrue(sql.contains("bundle_id"), sql)
        listOf(
            "network_reconfiguration_reviews",
            "network_configuration_bundles",
            "host_qualified_operations",
            "qualified_operations",
            "era_observations",
        ).forEach { table ->
            assertTrue(sql.contains(table), "Expected SQL to drop $table, got:\n$sql")
        }
    }

    @Test
    fun `migration 048 drops unused dijkstra tables and columns while preserving nodes and snapshots`() {
        val url = System.getenv("JORMANAGER_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(), "Set JORMANAGER_TEST_DATABASE_URL to a disposable local PostgreSQL database")
        val target = URI(requireNotNull(url).removePrefix("jdbc:"))
        require(
            target.scheme == "postgresql" && target.host in setOf("localhost", "127.0.0.1") &&
                target.path.matches(Regex("/jormanager_test_[a-zA-Z0-9_]+"))
        )
        val name = "jormanager_test_migration_" + UUID.randomUUID().toString().replace("-", "")
        val sibling = "jdbc:" + URI(target.scheme, target.userInfo, target.host, target.port, "/$name", target.query, null)
        var created = false
        try {
            DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("CREATE DATABASE $name") } }
            created = true
            DriverManager.getConnection(sibling).use { connection ->
                connection.createStatement().use { s ->
                    s.execute(
                        """
                        CREATE TABLE network_configuration_bundles (id varchar(64) PRIMARY KEY);
                        CREATE TABLE network_reconfiguration_reviews (id varchar(64) PRIMARY KEY, bundle_id varchar(64) REFERENCES network_configuration_bundles(id));
                        CREATE TABLE nodes (
                            id bigint PRIMARY KEY,
                            name text NOT NULL,
                            configuration_bundle_id varchar(64),
                            CONSTRAINT nodes_configuration_bundle_fk FOREIGN KEY (configuration_bundle_id) REFERENCES network_configuration_bundles(id)
                        );
                        CREATE TABLE node_configuration_snapshots (
                            id varchar(64) PRIMARY KEY,
                            node_id bigint NOT NULL REFERENCES nodes(id),
                            bundle_id varchar(64) REFERENCES network_configuration_bundles(id)
                        );
                        CREATE TABLE qualified_operations (operation_key varchar(255) PRIMARY KEY);
                        CREATE TABLE host_qualified_operations (id bigint PRIMARY KEY);
                        CREATE TABLE era_observations (id bigint PRIMARY KEY, node_id bigint REFERENCES nodes(id));
                        INSERT INTO network_configuration_bundles VALUES ('bundle-1');
                        INSERT INTO network_reconfiguration_reviews VALUES ('review-1', 'bundle-1');
                        INSERT INTO nodes VALUES (1, 'core-1', 'bundle-1');
                        INSERT INTO node_configuration_snapshots VALUES ('snap-1', 1, 'bundle-1');
                        INSERT INTO qualified_operations VALUES ('op-1');
                        INSERT INTO host_qualified_operations VALUES (1);
                        INSERT INTO era_observations VALUES (1, 1);
                        """.trimIndent(),
                    )
                }
                val migration = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
                CommandScope("update")
                    .addArgumentValue("database", migration)
                    .addArgumentValue("changelogFile", "db/changelog/048-remove-unused-dijkstra-tables.xml")
                    .execute()
                connection.createStatement().use { s ->
                    fun value(sql: String): String? =
                        s.executeQuery(sql).use { rs ->
                            assertTrue(rs.next())
                            rs.getString(1)
                        }
                    assertEquals(
                        "0",
                        value(
                            """
                            SELECT count(*) FROM information_schema.tables
                            WHERE table_schema='public'
                              AND table_name IN (
                                'network_reconfiguration_reviews',
                                'network_configuration_bundles',
                                'host_qualified_operations',
                                'qualified_operations',
                                'era_observations'
                              )
                            """.trimIndent(),
                        ),
                    )
                    assertEquals(
                        "0",
                        value(
                            """
                            SELECT count(*) FROM information_schema.columns
                            WHERE table_schema='public'
                              AND ((table_name='nodes' AND column_name='configuration_bundle_id')
                                OR (table_name='node_configuration_snapshots' AND column_name='bundle_id'))
                            """.trimIndent(),
                        ),
                    )
                    assertEquals("core-1", value("SELECT name FROM nodes WHERE id=1"))
                    assertEquals("snap-1", value("SELECT id FROM node_configuration_snapshots WHERE node_id=1"))
                }
            }
        } finally {
            if (created) DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("DROP DATABASE $name WITH (FORCE)") } }
        }
    }

    @Test
    fun `preserves acknowledged receipts and history without retaining replay data`() {
        val url = System.getenv("JORMANAGER_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(), "Set JORMANAGER_TEST_DATABASE_URL to a disposable local PostgreSQL database")
        val target = URI(requireNotNull(url).removePrefix("jdbc:"))
        require(
            target.scheme == "postgresql" && target.host in setOf("localhost", "127.0.0.1") &&
                target.path.matches(Regex("/jormanager_test_[a-zA-Z0-9_]+"))
        )
        val name = "jormanager_test_migration_" + UUID.randomUUID().toString().replace("-", "")
        val sibling = "jdbc:" + URI(target.scheme, target.userInfo, target.host, target.port, "/$name", target.query, null)
        var created = false
        try {
            DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("CREATE DATABASE $name") } }
            created = true
            DriverManager.getConnection(sibling).use { connection ->
                connection.createStatement().use { s ->
                    s.execute(
                        """
                        CREATE SEQUENCE hibernate_sequence;
                        CREATE TABLE files (id bigint PRIMARY KEY, content text NOT NULL);
                        CREATE TABLE nodes (id bigint PRIMARY KEY, genesis_shelley_file_id bigint, config_file_id bigint, network_key_discriminator text);
                        CREATE TABLE wallet (id bigint PRIMARY KEY, secret text);
                        CREATE TABLE leios_voting_keys (id bigint PRIMARY KEY, public_key text);
                        CREATE TABLE transactions (id bigint PRIMARY KEY, txid text NOT NULL, at text NOT NULL);
                        CREATE TABLE pool_deployment_previews (id text PRIMARY KEY, node_id bigint NOT NULL, details text NOT NULL);
                        CREATE TABLE authorized_operations (id text PRIMARY KEY, node_id bigint, network_key text, operation text, state text, request_payload text, tx_id text);
                        CREATE TABLE authorized_operation_inputs (id text PRIMARY KEY, operation_id text REFERENCES authorized_operations(id));
                        INSERT INTO files VALUES (1, '{"networkMagic":42,"systemStart":"start"}'),
                          (2, '{"RequiresNetworkMagic":"RequiresMagic","ByronGenesisHash":"AA","ShelleyGenesisHash":"BB"}');
                        INSERT INTO nodes VALUES (10,1,2,NULL), (99,1,2,NULL);
                        INSERT INTO wallet VALUES (1,'wallet-key');
                        INSERT INTO leios_voting_keys VALUES (7,'voting-key');
                        INSERT INTO transactions VALUES (100,'existing','original');
                        INSERT INTO pool_deployment_previews VALUES
                          ('a',10,'{"registrationRequestId":"accepted","keyId":7}'),
                          ('b',10,'{"registrationRequestId":"uncertain"}'),
                          ('c',10,'{"registrationRequestId":"confirmed"}'),
                          ('d',10,'{"registrationRequestId":"wrong-network"}'),
                          ('e',10,'{"registrationRequestId":"wrong-key","keyId":7}');
                        INSERT INTO authorized_operations
                        SELECT id, 99, CASE WHEN id='wrong-network' THEN 'other' ELSE
                          '42:start:' || encode(sha256(convert_to((SELECT content FROM files WHERE id=1), 'UTF8')), 'hex') ||
                          ':RequiresMagic:-:aa:bb:-:-:-' END,
                          'pool.register', state, payload, tx FROM (VALUES
                          ('accepted','submitted','{"nodeId":10,"keyId":7,"publication":{"pool":"x"}}','existing'),
                          ('uncertain','submitting','{"nodeId":10}','uncertain-tx'),
                          ('confirmed','confirmed','{"nodeId":10,"keyId":null,"publication":"ignored"}','confirmed-tx'),
                          ('wrong-network','submitted','{"nodeId":10}','network-tx'),
                          ('wrong-key','submitted','{"nodeId":10,"keyId":8}','key-tx')) AS v(id,state,payload,tx);
                        INSERT INTO authorized_operation_inputs VALUES ('input','accepted');
                        """.trimIndent()
                    )
                }
                val migration = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
                CommandScope("update")
                    .addArgumentValue("database", migration)
                    .addArgumentValue("changelogFile", "db/changelog/047-remove-authorized-operations.xml")
                    .execute()
                connection.createStatement().use { s ->
                    fun value(sql: String): String? =
                        s.executeQuery(sql).use { rs ->
                            assertTrue(rs.next())
                            rs.getString(1)
                        }
                    assertEquals("submitted:existing:x:accepted", value("SELECT (details::jsonb->>'registrationState') || ':' || (details::jsonb->>'transactionId') || ':' || (details::jsonb->'publication'->>'pool') || ':' || (details::jsonb->>'registrationRequestId') FROM pool_deployment_previews WHERE id='a'"))
                    assertEquals("submitted:confirmed-tx:false", value("SELECT (details::jsonb->>'registrationState') || ':' || (details::jsonb->>'transactionId') || ':' || (details::jsonb ? 'publication')::text FROM pool_deployment_previews WHERE id='c'"))
                    assertEquals("0", value("SELECT count(*) FROM pool_deployment_previews WHERE id IN ('b','d','e') AND details::jsonb ? 'registrationState'"))
                    assertEquals("existing:original,confirmed-tx:unknown,key-tx:unknown,network-tx:unknown", value("SELECT string_agg(txid || ':' || at, ',' ORDER BY CASE WHEN txid='existing' THEN 0 ELSE 1 END, txid) FROM transactions"))
                    assertEquals("wallet-key", value("SELECT secret FROM wallet WHERE id=1"))
                    assertEquals("voting-key", value("SELECT public_key FROM leios_voting_keys WHERE id=7"))
                    assertEquals("0", value("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('authorized_operations','authorized_operation_inputs')"))
                }
            }
        } finally {
            if (created) DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("DROP DATABASE $name WITH (FORCE)") } }
        }
    }

    @Test
    fun `changelog 049 is registered after 048 and generates cleanup SQL for draft nodes and tables`() {
        val accessor = ClassLoaderResourceAccessor()
        val database =
            DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                OfflineConnection("offline:postgresql", accessor),
            )
        val master =
            ChangeLogParserFactory
                .getInstance()
                .getParser("db/liquibase-changelog.xml", accessor)
                .parse("db/liquibase-changelog.xml", ChangeLogParameters(database), accessor)

        val paths = master.changeSets.map { it.filePath }
        val index048 = paths.indexOfLast { it.endsWith("048-remove-unused-dijkstra-tables.xml") }
        val index049 = paths.indexOfFirst { it.endsWith("049-remove-draft-nodes.xml") }
        assertTrue(index048 >= 0, "Expected 048 changelog in master changelog")
        assertTrue(index049 > index048, "Expected 049 changelog to be registered after 048")

        val sql =
            master.changeSets
                .filter { it.filePath.endsWith("049-remove-draft-nodes.xml") }
                .flatMap { changeSet ->
                    changeSet.changes.flatMap { change ->
                        SqlGeneratorFactory.getInstance().generateSql(change, database).map { it.toSql() }
                    }
                }.joinToString("\n")

        listOf(
            "pool_draft_config",
            "pool_deployment_previews",
            "node_configuration_snapshots",
            "key_installations",
            "leios_install_previews",
            "relays",
            "topology_entries",
            "pool_key_observations",
            "leios_voting_keys",
            "DELETE FROM nodes WHERE is_draft = true",
            "is_draft",
        ).forEach { expected ->
            assertTrue(sql.contains(expected), "Expected SQL to contain '$expected', got:\n$sql")
        }
    }

    @Test
    fun `changelog 050 is registered after 049 and adds ssh fingerprint columns to hosts`() {
        val accessor = ClassLoaderResourceAccessor()
        val database =
            DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
                OfflineConnection("offline:postgresql", accessor),
            )
        val master =
            ChangeLogParserFactory
                .getInstance()
                .getParser("db/liquibase-changelog.xml", accessor)
                .parse("db/liquibase-changelog.xml", ChangeLogParameters(database), accessor)

        val paths = master.changeSets.map { it.filePath }
        val index049 = paths.indexOfLast { it.endsWith("049-remove-draft-nodes.xml") }
        val index050 = paths.indexOfFirst { it.endsWith("050-host-ssh-fingerprints.xml") }
        assertTrue(index049 >= 0, "Expected 049 changelog in master changelog")
        assertTrue(index050 > index049, "Expected 050 changelog to be registered after 049")

        val sql =
            master.changeSets
                .filter { it.filePath.endsWith("050-host-ssh-fingerprints.xml") }
                .flatMap { changeSet ->
                    changeSet.changes.flatMap { change ->
                        SqlGeneratorFactory.getInstance().generateSql(change, database).map { it.toSql() }
                    }
                }.joinToString("\n")

        assertTrue(sql.contains("hosts"), sql)
        assertTrue(sql.contains("ssh_fingerprint"), sql)
        assertTrue(sql.contains("ssh_mismatch_fingerprint"), sql)
    }

    @Test
    fun `migration 049 removes draft nodes, draft-only keys and files, and drops draft tables and column`() {
        val url = System.getenv("JORMANAGER_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(), "Set JORMANAGER_TEST_DATABASE_URL to a disposable local PostgreSQL database")
        val target = URI(requireNotNull(url).removePrefix("jdbc:"))
        require(
            target.scheme == "postgresql" &&
                target.host in setOf("localhost", "127.0.0.1") &&
                target.path.matches(Regex("/jormanager_test_[a-zA-Z0-9_]+")),
        )
        val name = "jormanager_test_migration_" + UUID.randomUUID().toString().replace("-", "")
        val sibling = "jdbc:" + URI(target.scheme, target.userInfo, target.host, target.port, "/$name", target.query, null)
        var created = false
        try {
            DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("CREATE DATABASE $name") } }
            created = true
            DriverManager.getConnection(sibling).use { connection ->
                connection.createStatement().use { s ->
                    s.execute(
                        """
                        CREATE TABLE files (id bigint PRIMARY KEY, name text NOT NULL, content text NOT NULL);
                        CREATE TABLE wallet (
                            id bigint PRIMARY KEY,
                            payment_vkey_id bigint,
                            payment_skey_id bigint,
                            staking_vkey_id bigint,
                            staking_skey_id bigint
                        );
                        CREATE TABLE nodes (
                            id bigint PRIMARY KEY,
                            name text NOT NULL,
                            is_draft boolean NOT NULL DEFAULT false,
                            parent_id bigint REFERENCES nodes(id),
                            genesis_byron_file_id bigint,
                            genesis_shelley_file_id bigint,
                            genesis_alonzo_file_id bigint,
                            genesis_conway_file_id bigint,
                            genesis_dijkstra_file_id bigint,
                            config_file_id bigint,
                            topology_file_id bigint,
                            peer_snapshot_file_id bigint,
                            core_skey_id bigint,
                            core_vkey_id bigint,
                            core_counter_id bigint,
                            core_opcert_id bigint,
                            vrf_skey_id bigint,
                            vrf_vkey_id bigint,
                            kes_skey_id bigint,
                            kes_vkey_id bigint,
                            itn_prv_id bigint,
                            itn_pub_id bigint
                        );
                        CREATE TABLE leios_voting_keys (
                            id bigint PRIMARY KEY,
                            pool_id text NOT NULL,
                            signing_key_file_id bigint NOT NULL REFERENCES files(id),
                            public_key_file_id bigint NOT NULL REFERENCES files(id),
                            proof_of_possession_file_id bigint NOT NULL REFERENCES files(id)
                        );
                        CREATE TABLE key_installations (
                            id bigint PRIMARY KEY,
                            key_id bigint NOT NULL REFERENCES leios_voting_keys(id),
                            node_id bigint NOT NULL REFERENCES nodes(id)
                        );
                        CREATE TABLE leios_install_previews (
                            id varchar(64) PRIMARY KEY,
                            key_id bigint NOT NULL REFERENCES leios_voting_keys(id),
                            node_id bigint NOT NULL REFERENCES nodes(id)
                        );
                        CREATE TABLE pool_key_observations (
                            id bigint PRIMARY KEY,
                            pool_id text NOT NULL
                        );
                        CREATE TABLE relays (id bigint PRIMARY KEY, node_id bigint NOT NULL REFERENCES nodes(id));
                        CREATE TABLE topology_entries (id bigint PRIMARY KEY, node_id bigint NOT NULL REFERENCES nodes(id));
                        CREATE TABLE pool_draft_config (node_id bigint PRIMARY KEY REFERENCES nodes(id), details text NOT NULL);
                        CREATE TABLE pool_deployment_previews (id varchar(64) PRIMARY KEY, node_id bigint NOT NULL REFERENCES nodes(id));
                        CREATE TABLE node_configuration_snapshots (id varchar(64) PRIMARY KEY, node_id bigint NOT NULL REFERENCES nodes(id));

                        INSERT INTO files VALUES
                            (1, 'catalog-byron.json', '{}'),
                            (2, 'catalog-shelley.json', '{}'),
                            (3, 'unreferenced-catalog.json', '{}'),
                            (4, 'wallet.skey', '{}'),
                            (5, 'active-cold.skey', '{}'),
                            (6, 'active-bls.skey', '{}'),
                            (7, 'active-bls.vkey', '{}'),
                            (8, 'active-bls.pop', '{}'),
                            (9, 'draft-cold.skey', '{}'),
                            (10, 'draft-bls.skey', '{}'),
                            (11, 'draft-bls.vkey', '{}'),
                            (12, 'draft-bls.pop', '{}');

                        INSERT INTO wallet VALUES (1, NULL, 4, NULL, NULL);
                        INSERT INTO nodes (id, name, is_draft, parent_id, genesis_byron_file_id, genesis_shelley_file_id, core_skey_id)
                        VALUES
                            (1, 'active-core', false, NULL, 1, 2, 5),
                            (2, 'draft-core', true, NULL, 1, 2, 9),
                            (3, 'orphaned-child', false, 2, 1, 2, NULL);

                        INSERT INTO leios_voting_keys VALUES
                            (10, 'pool-active', 6, 7, 8),
                            (20, 'pool-draft', 10, 11, 12);
                        INSERT INTO key_installations VALUES (1, 10, 1), (2, 20, 2);
                        INSERT INTO leios_install_previews VALUES ('prev-active', 10, 1), ('prev-draft', 20, 2);
                        INSERT INTO pool_key_observations VALUES (1, 'pool-active'), (2, 'pool-draft');
                        INSERT INTO relays VALUES (1, 1), (2, 2);
                        INSERT INTO topology_entries VALUES (1, 1), (2, 2);
                        INSERT INTO pool_draft_config VALUES (2, '{}');
                        INSERT INTO pool_deployment_previews VALUES ('dep-1', 2);
                        INSERT INTO node_configuration_snapshots VALUES ('snap-1', 1), ('snap-2', 2);
                        """.trimIndent(),
                    )
                }
                val migration = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
                CommandScope("update")
                    .addArgumentValue("database", migration)
                    .addArgumentValue("changelogFile", "db/changelog/049-remove-draft-nodes.xml")
                    .execute()
                connection.createStatement().use { s ->
                    fun value(sql: String): String? =
                        s.executeQuery(sql).use { rs ->
                            assertTrue(rs.next())
                            rs.getString(1)
                        }
                    assertEquals(
                        "0",
                        value(
                            """
                            SELECT count(*) FROM information_schema.tables
                            WHERE table_schema='public'
                              AND table_name IN ('pool_draft_config', 'pool_deployment_previews', 'node_configuration_snapshots')
                            """.trimIndent(),
                        ),
                    )
                    assertEquals(
                        "0",
                        value(
                            """
                            SELECT count(*) FROM information_schema.columns
                            WHERE table_schema='public' AND table_name='nodes' AND column_name='is_draft'
                            """.trimIndent(),
                        ),
                    )
                    assertEquals("1,3", value("SELECT string_agg(id::text, ',' ORDER BY id) FROM nodes"))
                    assertEquals(null, value("SELECT parent_id::text FROM nodes WHERE id=3"))
                    assertEquals("10", value("SELECT string_agg(id::text, ',' ORDER BY id) FROM leios_voting_keys"))
                    assertEquals("1", value("SELECT string_agg(id::text, ',' ORDER BY id) FROM key_installations"))
                    assertEquals("prev-active", value("SELECT string_agg(id, ',' ORDER BY id) FROM leios_install_previews"))
                    assertEquals("pool-active", value("SELECT string_agg(pool_id, ',' ORDER BY id) FROM pool_key_observations"))
                    assertEquals("1", value("SELECT string_agg(id::text, ',' ORDER BY id) FROM relays"))
                    assertEquals("1", value("SELECT string_agg(id::text, ',' ORDER BY id) FROM topology_entries"))
                    assertEquals("1,2,3,4,5,6,7,8", value("SELECT string_agg(id::text, ',' ORDER BY id) FROM files"))
                }
            }
        } finally {
            if (created) DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("DROP DATABASE $name WITH (FORCE)") } }
        }
    }
}

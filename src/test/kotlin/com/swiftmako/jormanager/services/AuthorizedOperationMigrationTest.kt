package com.swiftmako.jormanager.services

import java.net.URI
import java.sql.DriverManager
import java.util.UUID
import liquibase.command.CommandScope
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class AuthorizedOperationMigrationTest {
    @Test
    fun `preserves acknowledged receipts and history without retaining replay data`() {
        val url = System.getenv("JORMANAGER_TEST_DATABASE_URL")
        assumeTrue(!url.isNullOrBlank(), "Set JORMANAGER_TEST_DATABASE_URL to a disposable local PostgreSQL database")
        val target = URI(requireNotNull(url).removePrefix("jdbc:"))
        require(target.scheme == "postgresql" && target.host in setOf("localhost", "127.0.0.1") &&
            target.path.matches(Regex("/jormanager_test_[a-zA-Z0-9_]+")))
        val name = "jormanager_test_migration_" + UUID.randomUUID().toString().replace("-", "")
        val sibling = "jdbc:" + URI(target.scheme, target.userInfo, target.host, target.port, "/$name", target.query, null)
        var created = false
        try {
            DriverManager.getConnection(url).use { it.createStatement().use { s -> s.execute("CREATE DATABASE $name") } }
            created = true
            DriverManager.getConnection(sibling).use { connection ->
                connection.createStatement().use { s ->
                    s.execute("""
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
                    """.trimIndent())
                }
                val migration = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
                CommandScope("update").addArgumentValue("database", migration)
                    .addArgumentValue("changelogFile", "db/changelog/047-remove-authorized-operations.xml").execute()
                connection.createStatement().use { s ->
                    fun value(sql: String): String? = s.executeQuery(sql).use { rs -> assertTrue(rs.next()); rs.getString(1) }
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
}

/*
 * Copyright 2014 BitPOS Pty Ltd.
 * Copyright 2014 Andreas Schildbach
 * Copyright 2014 Kalpesh Parmar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.store;

import org.bitcoinj.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>A full pruned block store using the Postgres database engine. As an added bonus an address index is calculated,
 * so you can use {@link #calculateBalanceForAddress(org.bitcoinj.core.Address)} to quickly look up
 * the quantity of bitcoins controlled by that address.</p>
 */
public class PostgresFullPrunedBlockStore extends DatabaseFullPrunedBlockStore {
    private static final Logger log = LoggerFactory.getLogger(PostgresFullPrunedBlockStore.class);

    private static final String DATABASE_DRIVER_CLASS = "org.postgresql.Driver";
    private static final String DATABASE_CONNECTION_URL_PREFIX = "jdbc:postgresql://";

    // create table SQL
    private static final String CREATE_SETTINGS_TABLE = "CREATE TABLE settings (\n" +
            "    name character varying(32) NOT NULL,\n" +
            "    value bytea,\n" +
            "    CONSTRAINT setting_pk PRIMARY KEY (name)\n" +
            ")\n";

    private static final String CREATE_HEADERS_TABLE = "CREATE TABLE headers (\n" +
            "    hash bytea NOT NULL,\n" +
            "    chainwork bytea NOT NULL,\n" +
            "    height integer NOT NULL,\n" +
            "    header bytea NOT NULL,\n" +
            "    wasundoable boolean NOT NULL,\n" +
            "    CONSTRAINT headers_pk PRIMARY KEY (hash)\n" +
            ")\n";

    private static final String CREATE_UNDOABLE_TABLE = "CREATE TABLE undoableblocks (\n" +
            "    hash bytea NOT NULL,\n" +
            "    height integer NOT NULL,\n" +
            "    txoutchanges bytea,\n" +
            "    transactions bytea,\n" +
            "    CONSTRAINT undoableblocks_pk PRIMARY KEY (hash)\n" +
            ")\n";

    private static final String CREATE_OPEN_OUTPUT_TABLE = "CREATE TABLE openoutputs (\n" +
            "    hash bytea NOT NULL,\n" +
            "    index integer NOT NULL,\n" +
            "    height integer NOT NULL,\n" +
            "    value bigint NOT NULL,\n" +
            "    scriptbytes bytea NOT NULL,\n" +
            "    toaddress character varying(35),\n" +
            "    addresstargetable smallint,\n" +
            "    coinbase boolean,\n" +
            "    CONSTRAINT openoutputs_pk PRIMARY KEY (hash,index)\n" +
            ")\n";

    // Some indexes to speed up inserts
    private static final String CREATE_OUTPUTS_ADDRESS_MULTI_INDEX      = "CREATE INDEX openoutputs_hash_index_num_height_toaddress_idx ON openoutputs USING btree (hash, index, height, toaddress)";
    private static final String CREATE_OUTPUTS_TOADDRESS_INDEX          = "CREATE INDEX openoutputs_toaddress_idx ON openoutputs USING btree (toaddress)";
    private static final String CREATE_OUTPUTS_ADDRESSTARGETABLE_INDEX  = "CREATE INDEX openoutputs_addresstargetable_idx ON openoutputs USING btree (addresstargetable)";
    private static final String CREATE_OUTPUTS_HASH_INDEX               = "CREATE INDEX openoutputs_hash_idx ON openoutputs USING btree (hash)";
    private static final String CREATE_UNDOABLE_TABLE_INDEX             = "CREATE INDEX undoableblocks_height_idx ON undoableBlocks USING btree (height)";

    /**
     * Creates a new PostgresFullPrunedBlockStore.
     *
     * @param params A copy of the NetworkParameters used
     * @param fullStoreDepth The number of blocks of history stored in full (something like 1000 is pretty safe)
     * @param hostname The hostname of the database to connect to
     * @param dbName The database to connect to
     * @param username The database username
     * @param password The password to the database
     * @throws BlockStoreException if the database fails to open for any reason
     */
    public PostgresFullPrunedBlockStore(NetworkParameters params, int fullStoreDepth, String hostname, String dbName,
                                        String username, String password) throws BlockStoreException {
        super(params, DATABASE_CONNECTION_URL_PREFIX + hostname + "/" + dbName, fullStoreDepth, username, password, null);
    }

    /**
     * <p>Create a new PostgresFullPrunedBlockStore, storing the tables in the schema specified.  You may want to
     * specify a schema to avoid name collisions, or just to keep the database better organized.  The schema is not
     * required, and if one is not provided than the default schema for the username will be used.  See
     * <a href="http://www.postgres.org/docs/9.3/static/ddl-schemas.html">the postgres schema docs</a> for more on
     * schemas.</p>
     *
     * @param params A copy of the NetworkParameters used.
     * @param fullStoreDepth The number of blocks of history stored in full (something like 1000 is pretty safe).
     * @param hostname The hostname of the database to connect to.
     * @param dbName The database to connect to.
     * @param username The database username.
     * @param password The password to the database.
     * @param schemaName The name of the schema to put the tables in.  May be null if no schema is being used.
     * @throws BlockStoreException If the database fails to open for any reason.
     */
    public PostgresFullPrunedBlockStore(NetworkParameters params, int fullStoreDepth, String hostname, String dbName,
                                        String username, String password, @Nullable String schemaName) throws BlockStoreException {
        super(params, DATABASE_CONNECTION_URL_PREFIX + hostname + "/" + dbName, fullStoreDepth, username, password, schemaName);
    }

    @Override
    protected List<String> getCreateTablesSQL() {
        List<String> sqlStatements = new ArrayList<>();
        sqlStatements.add(CREATE_SETTINGS_TABLE);
        sqlStatements.add(CREATE_HEADERS_TABLE);
        sqlStatements.add(CREATE_UNDOABLE_TABLE);
        sqlStatements.add(CREATE_OPEN_OUTPUT_TABLE);
        return sqlStatements;
    }

    @Override
    protected List<String> getCreateIndexesSQL() {
        List<String> sqlStatements = new ArrayList<>();
        sqlStatements.add(CREATE_UNDOABLE_TABLE_INDEX);
        sqlStatements.add(CREATE_OUTPUTS_ADDRESS_MULTI_INDEX);
        sqlStatements.add(CREATE_OUTPUTS_ADDRESSTARGETABLE_INDEX);
        sqlStatements.add(CREATE_OUTPUTS_HASH_INDEX);
        sqlStatements.add(CREATE_OUTPUTS_TOADDRESS_INDEX);
        return sqlStatements;
    }

    @Override
    protected List<String> getCreateSchemeSQL() {
        List<String> sqlStatements = new ArrayList<>();
        sqlStatements.add("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        sqlStatements.add("set search_path to '" + schemaName +"'");
        return sqlStatements;
    }

    @Override
    protected String getDatabaseDriverClass() {
        return DATABASE_DRIVER_CLASS;
    }

}
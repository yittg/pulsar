/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.service.schema;

import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.protobuf.ByteString.copyFrom;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.pulsar.broker.service.schema.BookkeeperSchemaStorage.Functions.newSchemaEntry;

import com.google.common.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.validation.constraints.NotNull;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerEntry;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.mledger.impl.LedgerMetadataUtils;
import org.apache.bookkeeper.util.ZkUtils;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.common.protocol.schema.SchemaVersion;
import org.apache.pulsar.zookeeper.ZooKeeperCache;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BookkeeperSchemaStorage implements SchemaStorage {
    private static final Logger log = LoggerFactory.getLogger(BookkeeperSchemaStorage.class);

    private static final String SchemaPath = "/schemas";
    private static final List<ACL> Acl = ZooDefs.Ids.OPEN_ACL_UNSAFE;
    private static final byte[] LedgerPassword = "".getBytes();

    private final PulsarService pulsar;
    private final ZooKeeper zooKeeper;
    private final ZooKeeperCache localZkCache;
    private final ServiceConfiguration config;
    private BookKeeper bookKeeper;

    private final ConcurrentMap<String, CompletableFuture<StoredSchema>> readSchemaOperations = new ConcurrentHashMap<>();

    @VisibleForTesting
    BookkeeperSchemaStorage(PulsarService pulsar) {
        this.pulsar = pulsar;
        this.localZkCache = pulsar.getLocalZkCache();
        this.zooKeeper = localZkCache.getZooKeeper();
        this.config = pulsar.getConfiguration();
    }

    @VisibleForTesting
    public void init() throws KeeperException, InterruptedException {
        try {
            if (zooKeeper.exists(SchemaPath, false) == null) {
                zooKeeper.create(SchemaPath, new byte[]{}, Acl, CreateMode.PERSISTENT);
            }
        } catch (KeeperException.NodeExistsException error) {
            // race on startup, ignore.
        }
    }

    @Override
    public void start() throws IOException {
        this.bookKeeper = pulsar.getBookKeeperClientFactory().create(
            pulsar.getConfiguration(),
            pulsar.getZkClient(),
            Optional.empty(),
            null
        );
    }

    @Override
    public CompletableFuture<SchemaVersion> put(String key, byte[] value, byte[] hash) {
        return putSchema(key, value, hash).thenApply(LongSchemaVersion::new);
    }

    @Override
    public CompletableFuture<StoredSchema> get(String key, SchemaVersion version) {
        if (version == SchemaVersion.Latest) {
            return getSchema(key);
        } else {
            LongSchemaVersion longVersion = (LongSchemaVersion) version;
            return getSchema(key, longVersion.getVersion());
        }
    }

    @Override
    public CompletableFuture<List<CompletableFuture<StoredSchema>>> getAll(String key) {
        CompletableFuture<List<CompletableFuture<StoredSchema>>> result = new CompletableFuture<>();
        getSchemaLocator(getSchemaPath(key)).thenAccept(locator -> {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Get all schemas - locator: {}", key, locator);
            }

            if (!locator.isPresent()) {
                result.complete(Collections.emptyList());
            }

            SchemaStorageFormat.SchemaLocator schemaLocator = locator.get().locator;
            List<CompletableFuture<StoredSchema>> list = new ArrayList<>();
            schemaLocator.getIndexList().forEach(indexEntry -> list.add(readSchemaEntry(indexEntry.getPosition())
                .thenApply(entry -> new StoredSchema
                    (
                        entry.getSchemaData().toByteArray(),
                        new LongSchemaVersion(indexEntry.getVersion())
                    )
                )
            ));
            result.complete(list);
        });
        return result;
    }

    @Override
    public CompletableFuture<SchemaVersion> delete(String key) {
        return deleteSchema(key).thenApply(LongSchemaVersion::new);
    }

    @NotNull
    private CompletableFuture<StoredSchema> getSchema(String schemaId) {
        // There's already a schema read operation in progress. Just piggyback on that
        return readSchemaOperations.computeIfAbsent(schemaId, key -> {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Fetching schema from store", schemaId);
            }
            CompletableFuture<StoredSchema> future = new CompletableFuture<>();

            getSchemaLocator(getSchemaPath(schemaId)).thenCompose(locator -> {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Got schema locator {}", schemaId, locator);
                }
                if (!locator.isPresent()) {
                    return completedFuture(null);
                }

                SchemaStorageFormat.SchemaLocator schemaLocator = locator.get().locator;

                return readSchemaEntry(schemaLocator.getInfo().getPosition())
                        .thenApply(entry -> new StoredSchema(entry.getSchemaData().toByteArray(),
                                new LongSchemaVersion(schemaLocator.getInfo().getVersion())));
            }).handleAsync((res, ex) -> {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] Get operation completed. res={} -- ex={}", schemaId, res, ex);
                }

                // Cleanup the pending ops from the map
                readSchemaOperations.remove(schemaId, future);
                if (ex != null) {
                    future.completeExceptionally(ex);
                } else {
                    future.complete(res);
                }
                return null;
            });

            return future;
        });
    }

    @Override
    public SchemaVersion versionFromBytes(byte[] version) {
        // The schema storage converts the schema from bytes to long
        // so it handles both cases 1) version is 64 bytes long pre 2.4.0;
        // 2) version is 8 bytes long post 2.4.0
        //
        // NOTE: if you are planning to change the logic here. you should consider
        //       both 64 bytes and 8 bytes cases.
        ByteBuffer bb = ByteBuffer.wrap(version);
        return new LongSchemaVersion(bb.getLong());
    }

    @Override
    public void close() throws Exception {
        if (nonNull(bookKeeper)) {
            bookKeeper.close();
        }
    }

    @NotNull
    private CompletableFuture<StoredSchema> getSchema(String schemaId, long version) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] Get schema - version: {}", schemaId, version);
        }

        return getSchemaLocator(getSchemaPath(schemaId)).thenCompose(locator -> {
            if (log.isDebugEnabled()) {
                log.debug("[{}] Get schema - version: {} - locator: {}", schemaId, version, locator);
            }

            if (!locator.isPresent()) {
                return completedFuture(null);
            }

            SchemaStorageFormat.SchemaLocator schemaLocator = locator.get().locator;
            if (version > schemaLocator.getInfo().getVersion()) {
                return completedFuture(null);
            }

            return findSchemaEntryByVersion(schemaLocator.getIndexList(), version)
                .thenApply(entry ->
                    new StoredSchema(
                        entry.getSchemaData().toByteArray(),
                        new LongSchemaVersion(version)
                    )
                );
        });
    }

    @NotNull
    private CompletableFuture<Long> putSchema(String schemaId, byte[] data, byte[] hash) {
        return getSchemaLocator(getSchemaPath(schemaId)).thenCompose(optLocatorEntry -> {

            if (optLocatorEntry.isPresent()) {
                // Schema locator was already present
                SchemaStorageFormat.SchemaLocator locator = optLocatorEntry.get().locator;
                byte[] storedHash = locator.getInfo().getHash().toByteArray();
                if (storedHash.length > 0 && Arrays.equals(storedHash, hash)) {
                    return completedFuture(locator.getInfo().getVersion());
                }

                if (log.isDebugEnabled()) {
                    log.debug("[{}] findSchemaEntryByHash - hash={}", schemaId, hash);
                }

                //don't check the schema whether already exist
                return readSchemaEntry(locator.getIndexList().get(0).getPosition())
                        .thenCompose(schemaEntry -> addNewSchemaEntryToStore(schemaId, locator.getIndexList(), data).thenCompose(
                        position -> updateSchemaLocator(schemaId, optLocatorEntry.get(), position, hash)));
            } else {
                // No schema was defined yet
                CompletableFuture<Long> future = new CompletableFuture<>();
                createNewSchema(schemaId, data, hash)
                        .thenAccept(future::complete)
                        .exceptionally(ex -> {
                            if (ex.getCause() instanceof NodeExistsException) {
                                // There was a race condition on the schema creation. Since it has now been created,
                                // retry the whole operation so that we have a chance to recover without bubbling error
                                // back to producer/consumer
                                putSchema(schemaId, data, hash)
                                        .thenAccept(future::complete)
                                        .exceptionally(ex2 -> {
                                            future.completeExceptionally(ex2);
                                            return null;
                                        });
                            } else {
                                // For other errors, just fail the operation
                                future.completeExceptionally(ex);
                            }
                            return null;
                        });

                return future;
            }
        });
    }

    private CompletableFuture<Long> createNewSchema(String schemaId, byte[] data, byte[] hash) {
        SchemaStorageFormat.IndexEntry emptyIndex = SchemaStorageFormat.IndexEntry.newBuilder()
                        .setVersion(0)
                        .setHash(copyFrom(hash))
                        .setPosition(SchemaStorageFormat.PositionInfo.newBuilder()
                                .setEntryId(-1L)
                                .setLedgerId(-1L)
                        ).build();

        return addNewSchemaEntryToStore(schemaId, Collections.singletonList(emptyIndex), data).thenCompose(position -> {
            // The schema was stored in the ledger, now update the z-node with the pointer to it
            SchemaStorageFormat.IndexEntry info = SchemaStorageFormat.IndexEntry.newBuilder()
                    .setVersion(0)
                    .setPosition(position)
                    .setHash(copyFrom(hash))
                    .build();

            return createSchemaLocator(getSchemaPath(schemaId), SchemaStorageFormat.SchemaLocator.newBuilder()
                    .setInfo(info)
                    .addAllIndex(
                            newArrayList(info))
                    .build())
                            .thenApply(ignore -> 0L);
        });
    }

    @NotNull
    private CompletableFuture<Long> deleteSchema(String schemaId) {
        return getSchema(schemaId).thenCompose(schemaAndVersion -> {
            if (isNull(schemaAndVersion)) {
                return completedFuture(null);
            } else {
                return putSchema(schemaId, new byte[]{}, new byte[]{});
            }
        });
    }

    @NotNull
    private static String getSchemaPath(String schemaId) {
        return SchemaPath + "/" + schemaId;
    }

    @NotNull
    private CompletableFuture<SchemaStorageFormat.PositionInfo> addNewSchemaEntryToStore(
        String schemaId,
        List<SchemaStorageFormat.IndexEntry> index,
        byte[] data
    ) {
        SchemaStorageFormat.SchemaEntry schemaEntry = newSchemaEntry(index, data);
        return createLedger(schemaId).thenCompose(ledgerHandle ->
            addEntry(ledgerHandle, schemaEntry).thenApply(entryId ->
                Functions.newPositionInfo(ledgerHandle.getId(), entryId)
            )
        );
    }

    @NotNull
    private CompletableFuture<Long> updateSchemaLocator(
        String schemaId,
        LocatorEntry locatorEntry,
        SchemaStorageFormat.PositionInfo position,
        byte[] hash
    ) {
        long nextVersion = locatorEntry.locator.getInfo().getVersion() + 1;
        SchemaStorageFormat.SchemaLocator locator = locatorEntry.locator;
        SchemaStorageFormat.IndexEntry info =
            SchemaStorageFormat.IndexEntry.newBuilder()
                .setVersion(nextVersion)
                .setPosition(position)
                .setHash(copyFrom(hash))
                .build();
        return updateSchemaLocator(getSchemaPath(schemaId),
            SchemaStorageFormat.SchemaLocator.newBuilder()
                .setInfo(info)
                .addAllIndex(
                    concat(locator.getIndexList(), newArrayList(info))
                ).build(), locatorEntry.zkZnodeVersion
        ).thenApply(ignore -> nextVersion);
    }

    @NotNull
    private CompletableFuture<SchemaStorageFormat.SchemaEntry> findSchemaEntryByVersion(
        List<SchemaStorageFormat.IndexEntry> index,
        long version
    ) {

        if (index.isEmpty()) {
            return completedFuture(null);
        }

        SchemaStorageFormat.IndexEntry lowest = index.get(0);
        if (version < lowest.getVersion()) {
            return readSchemaEntry(lowest.getPosition())
                .thenCompose(entry -> findSchemaEntryByVersion(entry.getIndexList(), version));
        }

        for (SchemaStorageFormat.IndexEntry entry : index) {
            if (entry.getVersion() == version) {
                return readSchemaEntry(entry.getPosition());
            } else if (entry.getVersion() > version) {
                break;
            }
        }

        return completedFuture(null);
    }

    @NotNull
    private CompletableFuture<SchemaStorageFormat.SchemaEntry> readSchemaEntry(
        SchemaStorageFormat.PositionInfo position
    ) {
        if (log.isDebugEnabled()) {
            log.debug("Reading schema entry from {}", position);
        }

        return openLedger(position.getLedgerId())
            .thenCompose((ledger) ->
                Functions.getLedgerEntry(ledger, position.getEntryId())
                    .thenCompose(entry -> closeLedger(ledger)
                        .thenApply(ignore -> entry)
                    )
            ).thenCompose(Functions::parseSchemaEntry);
    }

    @NotNull
    private CompletableFuture<Void> updateSchemaLocator(String id, SchemaStorageFormat.SchemaLocator schema, int version) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        zooKeeper.setData(id, schema.toByteArray(), version, (rc, path, ctx, stat) -> {
            Code code = Code.get(rc);
            if (code != Code.OK) {
                future.completeExceptionally(KeeperException.create(code));
            } else {
                future.complete(null);
            }
        }, null);
        return future;
    }

    @NotNull
    private CompletableFuture<LocatorEntry> createSchemaLocator(String id, SchemaStorageFormat.SchemaLocator locator) {
        CompletableFuture<LocatorEntry> future = new CompletableFuture<>();

        ZkUtils.asyncCreateFullPathOptimistic(zooKeeper, id, locator.toByteArray(), Acl,
                CreateMode.PERSISTENT, (rc, path, ctx, name) -> {
                    Code code = Code.get(rc);
                    if (code != Code.OK) {
                        future.completeExceptionally(KeeperException.create(code));
                    } else {
                        // Newly created z-node will have version 0
                        future.complete(new LocatorEntry(locator, 0));
                    }
                }, null);

        return future;
    }

    @NotNull
    private CompletableFuture<Optional<LocatorEntry>> getSchemaLocator(String schema) {
        return localZkCache.getEntryAsync(schema, new SchemaLocatorDeserializer()).thenApply(optional ->
            optional.map(entry -> new LocatorEntry(entry.getKey(), entry.getValue().getVersion()))
        );
    }

    @NotNull
    private CompletableFuture<Long> addEntry(LedgerHandle ledgerHandle, SchemaStorageFormat.SchemaEntry entry) {
        final CompletableFuture<Long> future = new CompletableFuture<>();
        ledgerHandle.asyncAddEntry(entry.toByteArray(),
            (rc, handle, entryId, ctx) -> {
                if (rc != BKException.Code.OK) {
                    future.completeExceptionally(bkException("Failed to add entry", rc, ledgerHandle.getId(), -1));
                } else {
                    future.complete(entryId);
                }
            }, null
        );
        return future;
    }

    @NotNull
    private CompletableFuture<LedgerHandle> createLedger(String schemaId) {
        Map<String, byte[]> metadata = LedgerMetadataUtils.buildMetadataForSchema(schemaId);
        final CompletableFuture<LedgerHandle> future = new CompletableFuture<>();
        bookKeeper.asyncCreateLedger(
            config.getManagedLedgerDefaultEnsembleSize(),
            config.getManagedLedgerDefaultWriteQuorum(),
            config.getManagedLedgerDefaultAckQuorum(),
            BookKeeper.DigestType.fromApiDigestType(config.getManagedLedgerDigestType()),
            LedgerPassword,
            (rc, handle, ctx) -> {
                if (rc != BKException.Code.OK) {
                    future.completeExceptionally(bkException("Failed to create ledger", rc, -1, -1));
                } else {
                    future.complete(handle);
                }
            }, null, metadata
        );
        return future;
    }

    @NotNull
    private CompletableFuture<LedgerHandle> openLedger(Long ledgerId) {
        final CompletableFuture<LedgerHandle> future = new CompletableFuture<>();
        bookKeeper.asyncOpenLedger(
            ledgerId,
            BookKeeper.DigestType.fromApiDigestType(config.getManagedLedgerDigestType()),
            LedgerPassword,
            (rc, handle, ctx) -> {
                if (rc != BKException.Code.OK) {
                    future.completeExceptionally(bkException("Failed to open ledger", rc, ledgerId, -1));
                } else {
                    future.complete(handle);
                }
            }, null
        );
        return future;
    }

    @NotNull
    private CompletableFuture<Void> closeLedger(LedgerHandle ledgerHandle) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        ledgerHandle.asyncClose((rc, handle, ctx) -> {
            if (rc != BKException.Code.OK) {
                future.completeExceptionally(bkException("Failed to close ledger", rc, ledgerHandle.getId(), -1));
            } else {
                future.complete(null);
            }
        }, null);
        return future;
    }

    interface Functions {
        static CompletableFuture<LedgerEntry> getLedgerEntry(LedgerHandle ledger, long entry) {
            final CompletableFuture<LedgerEntry> future = new CompletableFuture<>();
            ledger.asyncReadEntries(entry, entry,
                (rc, handle, entries, ctx) -> {
                    if (rc != BKException.Code.OK) {
                        future.completeExceptionally(bkException("Failed to read entry", rc, ledger.getId(), entry));
                    } else {
                        future.complete(entries.nextElement());
                    }
                }, null
            );
            return future;
        }

        static CompletableFuture<SchemaStorageFormat.SchemaEntry> parseSchemaEntry(LedgerEntry ledgerEntry) {
            CompletableFuture<SchemaStorageFormat.SchemaEntry> result = new CompletableFuture<>();
            try {
                result.complete(SchemaStorageFormat.SchemaEntry.parseFrom(ledgerEntry.getEntry()));
            } catch (IOException e) {
                result.completeExceptionally(e);
            }
            return result;
        }

        static SchemaStorageFormat.SchemaEntry newSchemaEntry(
            List<SchemaStorageFormat.IndexEntry> index,
            byte[] data
        ) {
            return SchemaStorageFormat.SchemaEntry.newBuilder()
                .setSchemaData(copyFrom(data))
                .addAllIndex(index)
                .build();
        }

        static SchemaStorageFormat.PositionInfo newPositionInfo(long ledgerId, long entryId) {
            return SchemaStorageFormat.PositionInfo.newBuilder()
                .setLedgerId(ledgerId)
                .setEntryId(entryId)
                .build();
        }
    }

    static class SchemaLocatorDeserializer implements ZooKeeperCache.Deserializer<SchemaStorageFormat.SchemaLocator> {
        @Override
        public SchemaStorageFormat.SchemaLocator deserialize(String key, byte[] content) throws Exception {
            return SchemaStorageFormat.SchemaLocator.parseFrom(content);
        }
    }

    static class LocatorEntry {
        final SchemaStorageFormat.SchemaLocator locator;
        final Integer zkZnodeVersion;

        LocatorEntry(SchemaStorageFormat.SchemaLocator locator, Integer zkZnodeVersion) {
            this.locator = locator;
            this.zkZnodeVersion = zkZnodeVersion;
        }
    }

    public static Exception bkException(String operation, int rc, long ledgerId, long entryId) {
        String message = org.apache.bookkeeper.client.api.BKException.getMessage(rc)
                + " -  ledger=" + ledgerId + " - operation=" + operation;

        if (entryId != -1) {
            message += " - entry=" + entryId;
        }
        return new IOException(message);
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.odps.cupid.table.v1.tunnel.impl;

import com.aliyun.odps.*;
import com.aliyun.odps.cupid.table.v1.Attribute;
import com.aliyun.odps.cupid.table.v1.util.Options;
import com.aliyun.odps.cupid.table.v1.util.TableUtils;
import com.aliyun.odps.cupid.table.v1.util.Validator;
import com.aliyun.odps.cupid.table.v1.writer.BucketFileInfo;
import com.aliyun.odps.cupid.table.v1.writer.TableWriteSession;
import com.aliyun.odps.cupid.table.v1.writer.WriteSessionInfo;
import com.aliyun.odps.cupid.table.v1.writer.WriterCommitMessage;
import com.aliyun.odps.tunnel.TableTunnel;
import com.aliyun.odps.tunnel.TunnelException;
import com.aliyun.odps.utils.StringUtils;

import java.io.IOException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.aliyun.odps.cupid.table.v1.tunnel.impl.Util.UPSERT_ENABLE;
import static com.aliyun.odps.cupid.table.v1.tunnel.impl.Util.WRITER_STREAM_ENABLE;

public class TunnelWriteSession extends TableWriteSession {

    private WriteSessionInfo sessionInfo;
    private List<Attribute> dataColumns;
    private List<Attribute> partitionColumns;
    private TableTunnel.UploadSession uploadSession;
    private TableTunnel.UpsertSession upsertSession;
    private Odps odps;

    TunnelWriteSession(String project,
                       String table,
                       TableSchema tableSchema,
                       Map<String, String> partitionSpec,
                       boolean overwrite,
                       Options options) {
        super(project, table, options, tableSchema, partitionSpec, overwrite);
        initOdps();
    }

    TunnelWriteSession(WriteSessionInfo writeSessionInfo) {
        super(writeSessionInfo.getProject(),
                writeSessionInfo.getTable(),
                writeSessionInfo.getOptions(),
                TableUtils.toTableSchema(writeSessionInfo),
                writeSessionInfo.getPartitionSpec(),
                writeSessionInfo.isOverwrite());
        initOdps();
        this.sessionInfo = writeSessionInfo;
        rebuildTunnelSession();
    }

    private void rebuildTunnelSession() {
        if (sessionInfo == null || !(sessionInfo instanceof TunnelWriteSessionInfo)) {
            throw new RuntimeException("Session info error");
        }

        // Dynamic partition spec or stream write
        if (((TunnelWriteSessionInfo) sessionInfo).isDynamicPartition()
                || ((TunnelWriteSessionInfo) sessionInfo).isStream()
                // TODO: upsert session: now return
                || ((TunnelWriteSessionInfo) sessionInfo).isUpsert()) {
            return;
        }

        try {
            TableTunnel tunnel = Util.getTableTunnel(this.options);
            if (partitionSpec.isEmpty()) {
                if (((TunnelWriteSessionInfo) sessionInfo).isUpsert()) {
                    // TODO: upsert session: now unreachable
                    upsertSession = tunnel.buildUpsertSession(project, table)
                            .setUpsertId(((TunnelWriteSessionInfo) sessionInfo).getUploadId())
                            .build();
                } else {
                    uploadSession = tunnel.getUploadSession(project, table, ((TunnelWriteSessionInfo) sessionInfo).getUploadId());
                }
                uploadSession = tunnel.getUploadSession(project, table, ((TunnelWriteSessionInfo) sessionInfo).getUploadId());
            } else {
                PartitionSpec odpsPartitionSpec = Util.toOdpsPartitionSpec(partitionSpec);
                // TODO: upsert session: now unreachable
                if (((TunnelWriteSessionInfo) sessionInfo).isUpsert()) {
                    upsertSession = tunnel.buildUpsertSession(project, table)
                            .setUpsertId(((TunnelWriteSessionInfo) sessionInfo).getUploadId())
                            .setPartitionSpec(odpsPartitionSpec)
                            .build();
                } else {
                    uploadSession = tunnel.getUploadSession(project, table, odpsPartitionSpec, ((TunnelWriteSessionInfo) sessionInfo).getUploadId());
                }
                uploadSession = tunnel.getUploadSession(project, table, odpsPartitionSpec, ((TunnelWriteSessionInfo) sessionInfo).getUploadId());
            }
        } catch (TunnelException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public TableSchema getTableSchema() {
        if (this.tableSchema == null) {
            if (this.odps == null) {
                initOdps();
            }
            this.tableSchema = this.odps.tables().get(project, table).getSchema();
        }
        return this.tableSchema;
    }

    @Override
    public WriteSessionInfo getOrCreateSessionInfo() throws IOException {
        if (sessionInfo != null) {
            return sessionInfo;
        }

        TableTunnel tunnel = new TableTunnel(odps);
        if (!StringUtils.isNullOrEmpty(this.options.getOdpsConf().getTunnelEndpoint())) {
            tunnel.setEndpoint(this.options.getOdpsConf().getTunnelEndpoint());
        }
        init();

        boolean isStream = this.options.getOrDefault(WRITER_STREAM_ENABLE, false);
        boolean isDynamicPartition = partitionSpec.values().stream().anyMatch(Objects::isNull);
        boolean isUpsert = this.options.getOrDefault(UPSERT_ENABLE, false);

        if (this.overwrite && (isDynamicPartition || isStream || isUpsert)) {
            throw new UnsupportedOperationException("Overwrite is not unsupported by tunnel provider!");
        }

        if (isStream) {
            this.sessionInfo = getSessionInfoInternal("", isDynamicPartition, true, false);
        } else {
            if (partitionSpec.isEmpty()) {
                String id  = null;
                if (isUpsert) {
                    // TODO: upsert session: now unreachable
//                    this.upsertSession = Util.getOrCreateUpsertSession(
//                            project,
//                            table,
//                            null,
//                            tunnel,
//                            null);
//                    id = this.upsertSession.getId();
                } else {
                    this.uploadSession = Util.createUploadSession(
                            project,
                            table,
                            null,
                            overwrite,
                            tunnel);
                    id = this.uploadSession.getId();
                }
                this.sessionInfo = getSessionInfoInternal(id, false, false, isUpsert);
            } else {
                if (isDynamicPartition) {
                    // Dynamic partition spec, will not create session
                    this.sessionInfo = getSessionInfoInternal("", true, false, isUpsert);
                } else {
                    String id  = null;
                    if (isUpsert) {
                        // TODO: upsert session: now unreachable
//                        this.upsertSession = Util.getOrCreateUpsertSession(
//                                project,
//                                table,
//                                Util.toOdpsPartitionSpec(partitionSpec),
//                                tunnel,
//                                null);
//                        id = this.upsertSession.getId();
                    } else {
                        this.uploadSession = Util.createUploadSession(
                                project,
                                table,
                                Util.toOdpsPartitionSpec(partitionSpec),
                                overwrite,
                                tunnel);
                        id = this.uploadSession.getId();
                    }
                    this.sessionInfo = getSessionInfoInternal(id, false, false, isUpsert);
                }
            }
        }
        return this.sessionInfo;
    }

    /**
     * Generate serializable data columns and partition columns
     */
    private void init() {
        if (tableSchema == null) {
            tableSchema = getTableSchema();
        }

        dataColumns = new ArrayList<>();
        for (Column c : tableSchema.getColumns()) {
            String type = c.getTypeInfo().getTypeName();
            dataColumns.add(new Attribute(c.getName(), type));
        }
        partitionColumns = new ArrayList<>();
        for (Column c : tableSchema.getPartitionColumns()) {
            String type = c.getTypeInfo().getTypeName();
            partitionColumns.add(new Attribute(c.getName(), type));
        }
    }

    private WriteSessionInfo getSessionInfoInternal(String sessionId,
                                                    boolean isDynamicPartition,
                                                    boolean isStream,
                                                    boolean isUpsert) {
        return new TunnelWriteSessionInfo(
                project,
                table,
                dataColumns,
                partitionColumns,
                partitionSpec,
                sessionId,
                overwrite,
                isDynamicPartition,
                isStream,
                isUpsert,
                options);
    }

    @Override
    public void commitTable() throws IOException {
        if (((TunnelWriteSessionInfo) sessionInfo).isStream()) {
            return;
        }
        try {
            if (((TunnelWriteSessionInfo) sessionInfo).isUpsert()) {
                // TODO: upsert session: now unreachable
                //if (upsertSession == null) {
                //    throw new UnsupportedOperationException("Dynamic partition not support commit table");
                // }
                // upsertSession.commit(false);
                // upsertSession.close();
            } else {
                if (uploadSession == null) {
                    throw new UnsupportedOperationException("Dynamic partition not support commit table");
                }
                uploadSession.commit();
            }
        } catch (TunnelException e) {
            throw new IOException(e);
        }
    }

    @Override
    public void commitTableWithPartitions(List<Map<String, String>> partitionSpecs) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commitClusteredTable(List<BucketFileInfo> partitionSpecs) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commitTableWithMessage(List<WriterCommitMessage> commitMessages) throws IOException {
        TableTunnel tunnel = Util.getTableTunnel(this.options);
        for (WriterCommitMessage commitMessage : commitMessages) {
            if (commitMessage instanceof TunnelDynamicWriteMsg) {
                TunnelDynamicWriteMsg msg = (TunnelDynamicWriteMsg) commitMessage;
                try {
                    if (!msg.getPartitionSpec().isEmpty()) {
                        PartitionSpec odpsPartitionSpec = Util.toOdpsPartitionSpec(msg.getPartitionSpec());
                        if (((TunnelWriteSessionInfo) sessionInfo).isUpsert()) {
                            // TODO: upsert session: now unreachable
                            // upsertSession = tunnel.buildUpsertSession(project, table)
                            //        .setUpsertId(msg.getUploadId())
                            //        .setPartitionSpec(odpsPartitionSpec)
                            //        .build();
                        } else {
                            uploadSession = tunnel.getUploadSession(project, table, odpsPartitionSpec, msg.getUploadId());
                        }
                    } else {
                        throw new InvalidParameterException("Tunnel dynamic partition is empty");
                    }
                } catch (TunnelException e) {
                    throw new RuntimeException(e);
                }
                commitTable();
            } else if (commitMessage instanceof TunnelWriteMsg) {
                commitTable();
                return;
            } else {
                throw new UnsupportedOperationException("commit message must be tunnel writer commit message");
            }
        }
    }

    @Override
    public void cleanup() throws IOException {
        return;
    }

    private void initOdps() {
        Validator.checkNotNull(this.options, "options");
        if (this.odps == null) {
            this.odps = Util.getOdps(this.options);
        }
    }
}

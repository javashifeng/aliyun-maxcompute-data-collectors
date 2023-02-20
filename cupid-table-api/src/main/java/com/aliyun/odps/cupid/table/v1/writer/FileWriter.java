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

package com.aliyun.odps.cupid.table.v1.writer;

import java.io.IOException;

public interface FileWriter<T> {

    void write(T data) throws IOException;

    void close() throws IOException;

    void commit() throws IOException;

    long getBytesWritten();

    long getRowsWritten();

    default WriterCommitMessage commitWithResult() throws IOException {
        throw new UnsupportedOperationException();
    }

    default void flush() throws IOException {
    }

    default long getBufferBytes() {
        throw new UnsupportedOperationException();
    }

    default long getBufferRows() {
        throw new UnsupportedOperationException();
    }

    default void upsert(T data) throws IOException {
        throw new UnsupportedOperationException();
    }

    default void delete(T data) throws IOException {
        throw new UnsupportedOperationException();
    }

    default T newElement() {
        throw new UnsupportedOperationException();
    }
}

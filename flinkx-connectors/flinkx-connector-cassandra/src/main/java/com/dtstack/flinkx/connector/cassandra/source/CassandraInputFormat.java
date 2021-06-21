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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.connector.cassandra.source;

import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.dtstack.flinkx.conf.FieldConf;
import com.dtstack.flinkx.connector.cassandra.conf.CassandraSourceConf;
import com.dtstack.flinkx.connector.cassandra.util.CassandraService;
import com.dtstack.flinkx.exception.ReadRecordException;
import com.dtstack.flinkx.inputformat.BaseRichInputFormat;
import com.google.common.base.Preconditions;

import org.apache.flink.core.io.InputSplit;
import org.apache.flink.table.data.RowData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author tiezhu
 * @since 2021/6/21 星期一
 */
public class CassandraInputFormat extends BaseRichInputFormat {

    private CassandraSourceConf sourceConf;

    private List<FieldConf> columns;

    private transient Session session;

    protected transient Iterator<Row> cursor;

    @Override
    protected InputSplit[] createInputSplitsInternal(int minNumSplits) throws Exception {
        ArrayList<CassandraInputSplit> splits = new ArrayList<>();

        try {
            Preconditions.checkNotNull(sourceConf.getTableName(), "table must not null");
            return CassandraService.splitJob(sourceConf, minNumSplits, splits);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            CassandraService.close(session);
        }
    }

    @Override
    protected void openInternal(InputSplit inputSplit) throws IOException {
        CassandraInputSplit split = (CassandraInputSplit) inputSplit;

        String tableName = sourceConf.getTableName();
        String keyspaces = sourceConf.getKeyspaces();

        Preconditions.checkNotNull(tableName, "table must not null");
        session = CassandraService.session(sourceConf);

        String consistency = sourceConf.getConsistency();
        ConsistencyLevel consistencyLevel = CassandraService.consistencyLevel(consistency);

        Select select = QueryBuilder.select(columnNameList).from(keyspaces, tableName);
        select.setConsistencyLevel(consistencyLevel);
        // TODO where ? group by ? order by ?

        PreparedStatement preparedStatement = session.prepare(select);
        LOG.info("split: {}, {}", split.getMinToken(), split.getMaxToken());
        ResultSet resultSet = session.execute(preparedStatement.bind());
        cursor = resultSet.all().iterator();
    }

    @Override
    protected RowData nextRecordInternal(RowData rowData) throws ReadRecordException {
        try {
            Row cqlRow = cursor.next();
            ColumnDefinitions columnDefinitions = cqlRow.getColumnDefinitions();
            rowData = rowConverter.toInternal(columnDefinitions);

            LOG.info("nextRecordInternal: numReadCounter = {}", numReadCounter.getLocalValue());
        } catch (Exception e) {
            throw new ReadRecordException("Cassandra next record error!", e, -1, rowData);
        }

        return rowData;
    }

    @Override
    protected void closeInternal() throws IOException {
        CassandraService.close(session);
    }

    @Override
    public boolean reachedEnd() throws IOException {
        return !cursor.hasNext();
    }

    public CassandraSourceConf getSourceConf() {
        return sourceConf;
    }

    public void setSourceConf(CassandraSourceConf sourceConf) {
        this.sourceConf = sourceConf;
    }

    public List<FieldConf> getColumns() {
        return columns;
    }

    public void setColumns(List<FieldConf> columns) {
        this.columns = columns;
    }
}

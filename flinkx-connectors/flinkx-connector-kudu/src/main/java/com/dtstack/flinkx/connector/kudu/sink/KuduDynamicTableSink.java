/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.flinkx.connector.kudu.sink;

import org.apache.flink.table.api.TableSchema;
import org.apache.flink.table.connector.ChangelogMode;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.sink.SinkFunctionProvider;
import org.apache.flink.table.types.logical.RowType;

import com.dtstack.flinkx.conf.FieldConf;
import com.dtstack.flinkx.connector.kudu.conf.KuduSinkConf;
import com.dtstack.flinkx.connector.kudu.converter.KuduRawTypeConverter;
import com.dtstack.flinkx.connector.kudu.converter.KuduRowConverter;
import com.dtstack.flinkx.streaming.api.functions.sink.DtOutputFormatSinkFunction;
import com.dtstack.flinkx.util.TableUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * @author tiezhu
 * @since 2021/6/21 星期一
 */
public class KuduDynamicTableSink implements DynamicTableSink {

    private static final String IDENTIFIER = "Kudu";

    private final TableSchema tableSchema;

    private final KuduSinkConf sinkConf;

    public KuduDynamicTableSink(KuduSinkConf sinkConf, TableSchema tableSchema) {
        this.tableSchema = tableSchema;
        this.sinkConf = sinkConf;
    }

    @Override
    public ChangelogMode getChangelogMode(ChangelogMode requestedMode) {
        // TODO upsert ? update ? append ?
        return ChangelogMode.insertOnly();
    }

    @Override
    public SinkRuntimeProvider getSinkRuntimeProvider(Context context) {
        KuduOutputFormatBuilder builder = new KuduOutputFormatBuilder();
        final RowType rowType =
                TableUtil.createRowType(sinkConf.getColumn(), KuduRawTypeConverter::apply);

        String[] fieldNames = tableSchema.getFieldNames();
        List<FieldConf> columnList = new ArrayList<>(fieldNames.length);
        for (String name : fieldNames) {
            FieldConf field = new FieldConf();
            field.setName(name);
            columnList.add(field);
        }
        sinkConf.setColumn(columnList);

        builder.setSinkConf(sinkConf);
        builder.setRowConverter(new KuduRowConverter(rowType));

        return SinkFunctionProvider.of(
                new DtOutputFormatSinkFunction(builder.finish()), sinkConf.getParallelism());
    }

    @Override
    public DynamicTableSink copy() {
        return new KuduDynamicTableSink(sinkConf, tableSchema);
    }

    @Override
    public String asSummaryString() {
        return IDENTIFIER;
    }
}

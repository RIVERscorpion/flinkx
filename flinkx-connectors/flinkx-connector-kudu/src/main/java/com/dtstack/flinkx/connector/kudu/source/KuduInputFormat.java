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

package com.dtstack.flinkx.connector.kudu.source;

import com.dtstack.flinkx.conf.FieldConf;
import com.dtstack.flinkx.connector.kudu.conf.KuduSourceConf;
import com.dtstack.flinkx.connector.kudu.converter.KuduColumnConverter;
import com.dtstack.flinkx.connector.kudu.converter.KuduRawTypeConverter;
import com.dtstack.flinkx.connector.kudu.util.KuduUtil;
import com.dtstack.flinkx.exception.ReadRecordException;
import com.dtstack.flinkx.inputformat.BaseRichInputFormat;
import com.dtstack.flinkx.util.TableUtil;

import org.apache.flink.core.io.InputSplit;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.types.logical.RowType;

import org.apache.kudu.client.KuduClient;
import org.apache.kudu.client.KuduException;
import org.apache.kudu.client.KuduScanToken;
import org.apache.kudu.client.KuduScanner;
import org.apache.kudu.client.RowResult;
import org.apache.kudu.client.RowResultIterator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author tiezhu
 * @since 2021/6/9 星期三
 */
public class KuduInputFormat extends BaseRichInputFormat {

    private KuduSourceConf sourceConf;

    private Map<String, Object> hadoopConf;

    private List<FieldConf> columns;

    private KuduClient client;

    private KuduScanner scanner;

    private RowResultIterator iterator;

    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    @Override
    protected InputSplit[] createInputSplitsInternal(int minNumSplits) throws Exception {
        LOG.info("execute createInputSplits,minNumSplits:{}", minNumSplits);
        List<KuduScanToken> scanTokens = KuduUtil.getKuduScanToken(sourceConf, hadoopConf);
        KuduInputSplit[] inputSplits = new KuduInputSplit[scanTokens.size()];
        for (int i = 0; i < scanTokens.size(); i++) {
            inputSplits[i] = new KuduInputSplit(scanTokens.get(i).serialize(), i);
        }

        return inputSplits;
    }

    @Override
    protected void openInternal(InputSplit inputSplit) throws IOException {
        super.openInputFormat();

        client = KuduUtil.getKuduClient(sourceConf, hadoopConf);

        LOG.info(
                "Execute openInternal: splitNumber = {}, indexOfSubtask  = {}",
                inputSplit.getSplitNumber(),
                indexOfSubTask);

        KuduInputSplit kuduTableSplit = (KuduInputSplit) inputSplit;
        scanner = KuduScanToken.deserializeIntoScanner(kuduTableSplit.getToken(), client);

        List<String> columnNames = new ArrayList<>();
        List<FieldConf> fieldConfList = sourceConf.getColumn();
        fieldConfList.forEach(field -> columnNames.add(field.getName()));
        RowType rowType = TableUtil.createRowType(fieldConfList, KuduRawTypeConverter::apply);

        setRowConverter(
                rowConverter == null
                        ? new KuduColumnConverter(rowType, columnNames)
                        : rowConverter);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected RowData nextRecordInternal(RowData rowData) throws ReadRecordException {
        try {
            RowResult result = iterator.next();
            rowData = rowConverter.toInternal(result);
        } catch (Exception e) {
            throw new ReadRecordException("Kudu next record error!", e, -1, rowData);
        }
        return rowData;
    }

    @Override
    protected void closeInternal() throws IOException {

        if (!isClosed.get()) {
            return;
        }

        LOG.info("closeInternal: closing input format.");

        if (client != null) {
            try {
                client.close();
            } catch (KuduException e) {
                LOG.warn("Kudu Client close failed.", e);
            }
        }

        if (scanner != null) {
            try {
                scanner.close();
            } catch (KuduException e) {
                LOG.warn("Kudu Scanner close failed.", e);
            }
        }

        isClosed.compareAndSet(false, true);
    }

    @Override
    public boolean reachedEnd() throws IOException {
        if (iterator == null || !iterator.hasNext()) {

            // The kudu scanner re-acquires the row iterator. If iterator is null or
            // iterator.hasNext is false, then the data has been reached end.
            if (scanner.hasMoreRows()) {
                iterator = scanner.nextRows();
            }

            return iterator == null || !iterator.hasNext();
        }

        return false;
    }

    public void setSourceConf(KuduSourceConf sourceConf) {
        this.sourceConf = sourceConf;
    }

    public void setHadoopConf(Map<String, Object> hadoopConf) {
        this.hadoopConf = hadoopConf;
    }

    public void setColumns(List<FieldConf> columns) {
        this.columns = columns;
    }

    public List<FieldConf> getColumns() {
        return columns;
    }

    public KuduSourceConf getSourceConf() {
        return sourceConf;
    }
}

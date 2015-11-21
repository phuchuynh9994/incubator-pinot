/**
 * Copyright (C) 2014-2015 LinkedIn Corp. (pinot-core@linkedin.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linkedin.pinot.tools.scan.query;

import com.linkedin.pinot.common.request.AggregationInfo;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.request.GroupBy;
import com.linkedin.pinot.pql.parsers.Pql2Compiler;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ScanBasedQueryProcessor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ScanBasedQueryProcessor.class);
  private final String _segmentsDir;
  private long _timeoutInSeconds = 1000;

  public ScanBasedQueryProcessor(String segmentsDir) {
    _segmentsDir = segmentsDir;
  }

  public QueryResponse processQuery(String query)
      throws Exception {
    long startTimeInMillis = System.currentTimeMillis();
    Pql2Compiler pql2Compiler = new Pql2Compiler();
    BrokerRequest brokerRequest = pql2Compiler.compileToBrokerRequest(query);
    ResultTable results = null;
    File file = new File(_segmentsDir);

    Aggregation aggregation = null;
    List<String> groupByColumns;
    List<AggregationInfo> aggregationsInfo = brokerRequest.getAggregationsInfo();
    if (aggregationsInfo != null) {
      GroupBy groupBy = brokerRequest.getGroupBy();
      groupByColumns = (brokerRequest.isSetGroupBy()) ? groupBy.getColumns() : null;
      long topN = (groupByColumns != null) ? groupBy.getTopN() : 10;
      aggregation = new Aggregation(brokerRequest.getAggregationsInfo(), groupByColumns, topN);
    }

    int numDocsScanned = 0;
    int totalDocs = 0;
    int numSegments = 0;
    LOGGER.info("Processing Query: {}", query);

    List<ResultTable> resultTables = processSegments(query, brokerRequest, file);
    for (ResultTable segmentResults : resultTables) {
      numDocsScanned += segmentResults.getNumDocsScanned();
      totalDocs += segmentResults.getTotalDocs();
      ++numSegments;
      results = (results == null) ? segmentResults : results.append(segmentResults);
    }

    if (aggregation != null && numSegments > 1 && numDocsScanned > 0) {
      results = aggregation.aggregate(results);
    }

    results.setNumDocsScanned(numDocsScanned);
    results.setTotalDocs(totalDocs);
    long totalUsedMs = System.currentTimeMillis() - startTimeInMillis;
    results.setProcessingTime(totalUsedMs);

    results.convertSumToAvgIfNeeded();
    QueryResponse queryResponse = new QueryResponse(results);

    return queryResponse;
  }

  private List<ResultTable> processSegments(final String query, final BrokerRequest brokerRequest, File segmentsDir)
      throws InterruptedException {
    ExecutorService executorService = Executors.newFixedThreadPool(8);
    List<ResultTable> resultTables = Collections.synchronizedList(new ArrayList<ResultTable>());

    for (final File segment : segmentsDir.listFiles()) {
      executorService.execute(new Runnable() {
        @Override
        public void run() {
          LOGGER.info("Processing segment: " + segment.getName());
          SegmentQueryProcessor processor = new SegmentQueryProcessor(brokerRequest, segment);
          try {
            ResultTable resultTable = processor.process(query);
            if (resultTable != null) {
              resultTables.add(resultTable);
            }
          } catch (Exception e) {
            LOGGER.error("Exception caught while processing segment.", e);
            return;
          }
        }
      });
    }
    executorService.shutdown();
    executorService.awaitTermination(_timeoutInSeconds, TimeUnit.SECONDS);
    return resultTables;
  }

  public static void main(String[] args)
      throws Exception {
    if (args.length != 2) {
      LOGGER.error("Incorrect arguments");
      LOGGER.info("Usage: <exec> <UntarredSegmentDir> <QueryFile");
      System.exit(1);
    }

    String segDir = args[0];
    String queryFile = args[1];
    String query;

    ScanBasedQueryProcessor scanBasedQueryProcessor = new ScanBasedQueryProcessor(segDir);
    BufferedReader bufferedReader = new BufferedReader(new FileReader(queryFile));

    while ((query = bufferedReader.readLine()) != null) {
      QueryResponse queryResponse = scanBasedQueryProcessor.processQuery(query);
      printResult(queryResponse);
    }
    bufferedReader.close();
  }

  public static void printResult(QueryResponse queryResponse)
      throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    LOGGER.info(objectMapper.defaultPrettyPrintingWriter().writeValueAsString(queryResponse));
  }
}

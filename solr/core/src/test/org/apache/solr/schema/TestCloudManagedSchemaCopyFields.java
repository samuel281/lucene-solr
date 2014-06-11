package org.apache.solr.schema;
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.cloud.AbstractFullDistribZkTestBase;
import org.apache.solr.util.RESTfulServerProvider;
import org.apache.solr.util.RestTestHarness;
import org.eclipse.jetty.servlet.ServletHolder;
import org.restlet.ext.servlet.ServerServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class TestCloudManagedSchemaCopyFields extends AbstractFullDistribZkTestBase {
  private static final Logger log = LoggerFactory.getLogger(TestCloudManagedSchemaAddField.class);

  public TestCloudManagedSchemaCopyFields() {
    super();
    fixShardCount = true;

    sliceCount = 4;
    shardCount = 8;
  }

  @Override
  protected String getCloudSolrConfig() {
    return "solrconfig-tlog-mutable-managed-schema.xml";
  }
  
  @Override
  public SortedMap<ServletHolder,String> getExtraServlets() {
    final SortedMap<ServletHolder,String> extraServlets = new TreeMap<ServletHolder,String>();
    final ServletHolder solrRestApi = new ServletHolder("SolrRestApi", ServerServlet.class);
    solrRestApi.setInitParameter("org.restlet.application", "org.apache.solr.rest.SolrRestApi");
    extraServlets.put(solrRestApi, "/schema/*");  // '/schema/*' matches '/schema', '/schema/', and '/schema/whatever...'
    return extraServlets;
  }
  
  private List<RestTestHarness> restTestHarnesses = new ArrayList<RestTestHarness>();
  
  private void setupHarnesses() {
    for (int i = 0 ; i < clients.size() ; ++i) {
      final HttpSolrServer client = (HttpSolrServer)clients.get(i);
      RestTestHarness harness = new RestTestHarness(new RESTfulServerProvider() {
        @Override
        public String getBaseURL() {
          return client.getBaseURL();
        }
      });
      restTestHarnesses.add(harness);
    }
  }
  
  @Override
  public void doTest() throws Exception {
    setupHarnesses();
    
    // First, add the same copy field directive a bunch of times.    
    // Then verify each shard's schema has it.
    int numFields = 200;
    for (int i = 1 ; i <= numFields ; ++i) {
      RestTestHarness publisher = restTestHarnesses.get(r.nextInt(restTestHarnesses.size()));
      final String content = "[{\"source\":\""+"sku1"+"\",\"dest\":[\"sku2\"]}]";
      String request = "/schema/copyfields/?wt=xml";             
      String response = publisher.post(request, content);
      String result = publisher.validateXPath
          (response, "/response/lst[@name='responseHeader']/int[@name='status'][.='0']");
      if (null != result) {
        fail("POST REQUEST FAILED: xpath=" + result + "  request=" + request 
            + "  content=" + content + "  response=" + response);
      }
    }
    
    Thread.sleep(100000);
    
    String request = "/schema/copyfields/?wt=xml&indent=on&source.fl=sku1";
    for (RestTestHarness client : restTestHarnesses) {
      String response = client.query(request);
      String result = client.validateXPath(response,
          "/response/lst[@name='responseHeader']/int[@name='status'][.='0']",
          "/response/arr[@name='copyFields']/lst/str[@name='dest'][.='sku2']");
      if (null != result) {
        fail("QUERY FAILED: xpath=" + result + "  request=" + request + "  response=" + response);
      }
    }
  }
}

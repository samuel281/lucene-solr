package org.apache.solr.cloud;

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

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.lucene.util.LuceneTestCase.Slow;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.cloud.hdfs.HdfsTestUtil;
import org.apache.solr.common.cloud.ClusterStateUtil;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.util.DefaultSolrThreadFactory;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope.Scope;

@Slow
@ThreadLeakScope(Scope.NONE) // hdfs client currently leaks thread(s)
public class SharedFSAutoReplicaFailoverTest extends AbstractFullDistribZkTestBase {
  
  private static final boolean DEBUG = true;
  private static MiniDFSCluster dfsCluster;

  ThreadPoolExecutor executor = new ThreadPoolExecutor(0,
      Integer.MAX_VALUE, 5, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
      new DefaultSolrThreadFactory("testExecutor"));
  
  CompletionService<Object> completionService;
  Set<Future<Object>> pending;

  
  @BeforeClass
  public static void hdfsFailoverBeforeClass() throws Exception {
    dfsCluster = HdfsTestUtil.setupClass(TEMP_DIR + File.separator + "sharedFsAutoReplicaFailoverTest");
  }
  
  @AfterClass
  public static void hdfsFailoverAfterClass() throws Exception {
    HdfsTestUtil.teardownClass(dfsCluster);
    dfsCluster = null;
  }
  
  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    useJettyDataDir = false;
    System.setProperty("solr.xml.persist", "true");
  }
  
  protected String getSolrXml() {
    return "solr-no-core.xml";
  }

  
  public SharedFSAutoReplicaFailoverTest() {
    fixShardCount = true;
    
    sliceCount = 2;
    shardCount = 4;
    completionService = new ExecutorCompletionService<Object>(executor);
    pending = new HashSet<Future<Object>>();
    checkCreatedVsState = false;
    
  }
  
  @Override
  public void doTest() throws Exception {
    try {
      testBasics();
    } finally {
      if (DEBUG) {
        super.printLayout();
      }
    }
  }
  
  // very slow tests, especially since jetty is started and stopped
  // serially
  private void testBasics() throws Exception {
    String collection1 = "solrj_collection";
    CollectionAdminResponse response = CollectionAdminRequest.createCollection(collection1, 2,
        2, 2, null, "conf1", "myOwnField", true, cloudClient);
    assertEquals(0, response.getStatus());
    assertTrue(response.isSuccess());
    waitForRecoveriesToFinish(collection1, false);
    
    String collection2 = "solrj_collection2";
    CollectionAdminResponse response2 = CollectionAdminRequest.createCollection(collection2, 2,
        2, 2, null, "conf1", "myOwnField", false, cloudClient);
    assertEquals(0, response2.getStatus());
    assertTrue(response2.isSuccess());
    
    waitForRecoveriesToFinish(collection2, false);
    
    String collection3 = "solrj_collection3";
    CollectionAdminResponse response3 = CollectionAdminRequest.createCollection(collection3, 5,
        1, 1, null, "conf1", "myOwnField", true, cloudClient);
    assertEquals(0, response3.getStatus());
    assertTrue(response3.isSuccess());
    
    waitForRecoveriesToFinish(collection3, false);
    
    ChaosMonkey.stop(jettys.get(1));
    ChaosMonkey.stop(jettys.get(2));
    
    Thread.sleep(5000);

    assertTrue("Timeout waiting for all live and active", ClusterStateUtil.waitForAllActiveAndLiveReplicas(cloudClient.getZkStateReader(), collection1, 260000));
    
    assertSliceAndReplicaCount(collection1);
    
    assertEquals(4, ClusterStateUtil.getLiveAndActiveReplicaCount(cloudClient.getZkStateReader(), collection1));
    assertTrue(ClusterStateUtil.getLiveAndActiveReplicaCount(cloudClient.getZkStateReader(), collection2) < 4);
    
    // collection3 has maxShardsPerNode=1, there are 4 standard jetties and one control jetty and 2 nodes stopped
    ClusterStateUtil.waitForLiveAndActiveReplicaCount(cloudClient.getZkStateReader(), collection3, 3, 30000);
    
    // collection1 should still be at 4
    assertEquals(4, ClusterStateUtil.getLiveAndActiveReplicaCount(cloudClient.getZkStateReader(), collection1));
    // and collection2 less than 4
    assertTrue(ClusterStateUtil.getLiveAndActiveReplicaCount(cloudClient.getZkStateReader(), collection2) < 4);
    
    ChaosMonkey.stop(jettys);
    ChaosMonkey.stop(controlJetty);

    assertTrue("Timeout waiting for all not live", ClusterStateUtil.waitForAllReplicasNotLive(cloudClient.getZkStateReader(), 45000));

    ChaosMonkey.start(jettys);
    ChaosMonkey.start(controlJetty);
    
    assertTrue("Timeout waiting for all live and active", ClusterStateUtil.waitForAllActiveAndLiveReplicas(cloudClient.getZkStateReader(), collection1, 120000));

    assertSliceAndReplicaCount(collection1);
    assertSingleReplicationAndShardSize(collection3, 5);
    
    int jettyIndex = random().nextInt(jettys.size());
    ChaosMonkey.stop(jettys.get(jettyIndex));
    ChaosMonkey.start(jettys.get(jettyIndex));
    
    assertTrue("Timeout waiting for all live and active", ClusterStateUtil.waitForAllActiveAndLiveReplicas(cloudClient.getZkStateReader(), collection1, 60000));
    
    assertSliceAndReplicaCount(collection1);
    
    assertSingleReplicationAndShardSize(collection3, 5);
    ClusterStateUtil.waitForLiveAndActiveReplicaCount(cloudClient.getZkStateReader(), collection3, 5, 30000);
  }

  private void assertSingleReplicationAndShardSize(String collection, int numSlices) {
    Collection<Slice> slices;
    slices = cloudClient.getZkStateReader().getClusterState().getActiveSlices(collection);
    assertEquals(numSlices, slices.size());
    for (Slice slice : slices) {
      assertEquals(1, slice.getReplicas().size());
    }
  }

  private void assertSliceAndReplicaCount(String collection) {
    Collection<Slice> slices;
    slices = cloudClient.getZkStateReader().getClusterState().getActiveSlices(collection);
    assertEquals(2, slices.size());
    for (Slice slice : slices) {
      assertEquals(2, slice.getReplicas().size());
    }
  }
  
  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    System.clearProperty("solr.xml.persist");
  }
}

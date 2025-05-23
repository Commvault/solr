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
package org.apache.solr.cloud.overseer;

import java.lang.invoke.MethodHandles;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.cloud.Overseer;
import org.apache.solr.cloud.OverseerTest;
import org.apache.solr.cloud.Stats;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.cloud.ZkTestServer;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.DocRouter;
import org.apache.solr.common.cloud.PerReplicaStatesOps;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.Compressor;
import org.apache.solr.common.util.Utils;
import org.apache.solr.common.util.ZLibCompressor;
import org.apache.solr.handler.admin.ConfigSetsHandler;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZkStateWriterTest extends SolrTestCaseJ4 {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private static final ZkStateWriter.ZkWriteCallback FAIL_ON_WRITE =
      () -> fail("Got unexpected flush");

  private static final Compressor STATE_COMPRESSION_PROVIDER = new ZLibCompressor();

  @BeforeClass
  public static void setup() {
    System.setProperty("solr.OverseerStateUpdateDelay", "1000");
    System.setProperty("solr.OverseerStateUpdateBatchSize", "10");
  }

  @AfterClass
  public static void cleanup() {
    System.clearProperty("solr.OverseerStateUpdateDelay");
    System.clearProperty("solr.OverseerStateUpdateBatchSize");
  }

  public void testZkStateWriterBatching() throws Exception {
    Path zkDir = createTempDir("testZkStateWriterBatching");

    ZkTestServer server = new ZkTestServer(zkDir);

    SolrZkClient zkClient = null;

    try {
      server.run();

      zkClient =
          new SolrZkClient.Builder()
              .withUrl(server.getZkAddress())
              .withTimeout(OverseerTest.DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
              .build();
      ZkController.createClusterZkNodes(zkClient);

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c1", true);
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c2", true);
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c3", true);

        Map<String, Object> props =
            Collections.singletonMap(
                ZkStateReader.CONFIGNAME_PROP, ConfigSetsHandler.DEFAULT_CONFIGSET_NAME);
        ZkWriteCommand c1 = new ZkWriteCommand("c1", createDocCollection("c1", props));
        ZkWriteCommand c2 = new ZkWriteCommand("c2", createDocCollection("c2", props));
        ZkWriteCommand c3 = new ZkWriteCommand("c3", createDocCollection("c3", props));
        ZkStateWriter writer =
            new ZkStateWriter(reader, new Stats(), -1, STATE_COMPRESSION_PROVIDER);

        // First write is flushed immediately
        ClusterState clusterState =
            writer.enqueueUpdate(reader.getClusterState(), Collections.singletonList(c1), null);
        clusterState =
            writer.enqueueUpdate(clusterState, Collections.singletonList(c1), FAIL_ON_WRITE);
        clusterState =
            writer.enqueueUpdate(clusterState, Collections.singletonList(c2), FAIL_ON_WRITE);

        Thread.sleep(Overseer.STATE_UPDATE_DELAY + 100);
        AtomicBoolean didWrite = new AtomicBoolean(false);
        clusterState =
            writer.enqueueUpdate(
                clusterState, Collections.singletonList(c3), () -> didWrite.set(true));
        assertTrue("Exceed the update delay, should be flushed", didWrite.get());

        for (int i = 0; i <= Overseer.STATE_UPDATE_BATCH_SIZE; i++) {
          clusterState =
              writer.enqueueUpdate(
                  clusterState, Collections.singletonList(c3), () -> didWrite.set(true));
        }
        assertTrue("Exceed the update batch size, should be flushed", didWrite.get());
      }

    } finally {
      IOUtils.close(zkClient);
      server.shutdown();
    }
  }

  public void testZkStateWriterPendingAndNonBatchedTimeExceeded() throws Exception {
    Path zkDir = createTempDir("testZkStateWriterBatching");

    ZkTestServer server = new ZkTestServer(zkDir);

    SolrZkClient zkClient = null;

    try {
      server.run();

      zkClient =
          new SolrZkClient.Builder()
              .withUrl(server.getZkAddress())
              .withTimeout(OverseerTest.DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
              .build();
      ZkController.createClusterZkNodes(zkClient);

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c1", true);
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c2", true);
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c3", true);
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/prs1", true);

        ZkWriteCommand c1 = new ZkWriteCommand("c1", createDocCollection("c1", new HashMap<>()));
        ZkWriteCommand c2 = new ZkWriteCommand("c2", createDocCollection("c2", new HashMap<>()));
        ZkWriteCommand c3 = new ZkWriteCommand("c3", createDocCollection("c3", new HashMap<>()));
        Map<String, Object> prsProps = new HashMap<>();
        prsProps.put("perReplicaState", Boolean.TRUE);
        ZkWriteCommand prs1 =
            new ZkWriteCommand(
                "prs1",
                DocCollection.create(
                    "prs1",
                    new HashMap<>(),
                    prsProps,
                    DocRouter.DEFAULT,
                    0,
                    Instant.now(),
                    PerReplicaStatesOps.getZkClientPrsSupplier(
                        zkClient, DocCollection.getCollectionPath("c1"))));
        ZkStateWriter writer =
            new ZkStateWriter(reader, new Stats(), -1, STATE_COMPRESSION_PROVIDER);

        // First write is flushed immediately
        ClusterState clusterState =
            writer.enqueueUpdate(reader.getClusterState(), Collections.singletonList(c1), null);
        clusterState =
            writer.enqueueUpdate(clusterState, Collections.singletonList(c1), FAIL_ON_WRITE);
        clusterState =
            writer.enqueueUpdate(clusterState, Collections.singletonList(c2), FAIL_ON_WRITE);

        Thread.sleep(Overseer.STATE_UPDATE_DELAY + 100);
        AtomicBoolean didWrite = new AtomicBoolean(false);
        clusterState =
            writer.enqueueUpdate(
                clusterState, Collections.singletonList(prs1), () -> didWrite.set(true));
        assertTrue("Exceed the update delay, should be flushed", didWrite.get());
        didWrite.set(false);
        clusterState =
            writer.enqueueUpdate(
                clusterState, Collections.singletonList(c3), () -> didWrite.set(true));
        assertTrue("Exceed the update delay, should be flushed", didWrite.get());
        assertTrue(
            "The updates queue should be empty having been flushed", writer.updates.isEmpty());
        didWrite.set(false);
      }

    } finally {
      IOUtils.close(zkClient);
      server.shutdown();
    }
  }

  public void testZkStateWriterPendingAndNonBatchedBatchSizeExceeded() throws Exception {
    Path zkDir = createTempDir("testZkStateWriterBatching");

    ZkTestServer server = new ZkTestServer(zkDir);

    SolrZkClient zkClient = null;

    try {
      server.run();

      zkClient =
          new SolrZkClient.Builder()
              .withUrl(server.getZkAddress())
              .withTimeout(OverseerTest.DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
              .build();
      ZkController.createClusterZkNodes(zkClient);

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c1", true);
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c2", true);
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c3", true);
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/prs1", true);

        ZkWriteCommand c1 = new ZkWriteCommand("c1", createDocCollection("c1", new HashMap<>()));
        ZkWriteCommand c2 = new ZkWriteCommand("c2", createDocCollection("c2", new HashMap<>()));
        ZkWriteCommand c3 = new ZkWriteCommand("c3", createDocCollection("c3", new HashMap<>()));
        Map<String, Object> prsProps = new HashMap<>();
        prsProps.put("perReplicaState", Boolean.TRUE);
        ZkWriteCommand prs1 =
            new ZkWriteCommand(
                "prs1",
                DocCollection.create(
                    "prs1",
                    new HashMap<>(),
                    prsProps,
                    DocRouter.DEFAULT,
                    0,
                    Instant.now(),
                    PerReplicaStatesOps.getZkClientPrsSupplier(
                        zkClient, DocCollection.getCollectionPath("prs1"))));
        ZkStateWriter writer =
            new ZkStateWriter(reader, new Stats(), -1, STATE_COMPRESSION_PROVIDER);

        // First write is flushed immediately
        ClusterState clusterState =
            writer.enqueueUpdate(reader.getClusterState(), Collections.singletonList(c1), null);

        AtomicBoolean didWrite = new AtomicBoolean(false);
        AtomicBoolean didWritePrs = new AtomicBoolean(false);
        for (int i = 0; i <= Overseer.STATE_UPDATE_BATCH_SIZE; i++) {
          clusterState =
              writer.enqueueUpdate(
                  clusterState, Collections.singletonList(c3), () -> didWrite.set(true));
          // Write a PRS update in the middle and make sure we still get the right results
          if (i == (Overseer.STATE_UPDATE_BATCH_SIZE / 2)) {
            clusterState =
                writer.enqueueUpdate(
                    clusterState, Collections.singletonList(prs1), () -> didWritePrs.set(true));
          }
        }
        assertTrue("Exceed the update batch size, should be flushed", didWrite.get());
        assertTrue("PRS update should always be written", didWritePrs.get());
        assertTrue(
            "The updates queue should be empty having been flushed", writer.updates.isEmpty());
      }

    } finally {
      IOUtils.close(zkClient);
      server.shutdown();
    }
  }

  public void testSingleExternalCollection() throws Exception {
    Path zkDir = createTempDir("testSingleExternalCollection");

    ZkTestServer server = new ZkTestServer(zkDir);

    SolrZkClient zkClient = null;

    try {
      server.run();

      zkClient =
          new SolrZkClient.Builder()
              .withUrl(server.getZkAddress())
              .withTimeout(OverseerTest.DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
              .build();
      ZkController.createClusterZkNodes(zkClient);

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        ZkStateWriter writer =
            new ZkStateWriter(reader, new Stats(), -1, STATE_COMPRESSION_PROVIDER);

        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c1", true);

        // create new collection
        ZkWriteCommand c1 =
            new ZkWriteCommand(
                "c1",
                createDocCollection(
                    "c1",
                    Collections.singletonMap(
                        ZkStateReader.CONFIGNAME_PROP, ConfigSetsHandler.DEFAULT_CONFIGSET_NAME)));

        writer.enqueueUpdate(reader.getClusterState(), Collections.singletonList(c1), null);
        ClusterState clusterState = writer.writePendingUpdates();

        Map<?, ?> map =
            (Map<?, ?>)
                Utils.fromJSON(
                    zkClient.getData(
                        ZkStateReader.COLLECTIONS_ZKNODE + "/c1/state.json", null, null, true));
        assertNotNull(map.get("c1"));

        Stat stat = new Stat();
        zkClient.getData(ZkStateReader.getCollectionPath("c1"), null, stat, false);
        assertEquals(
            Instant.ofEpochMilli(stat.getCtime()),
            clusterState.getCollection("c1").getCreationTime());
      }
    } finally {
      IOUtils.close(zkClient);
      server.shutdown();
    }
  }

  public void testExternalModification() throws Exception {
    Path zkDir = createTempDir("testExternalModification");

    ZkTestServer server = new ZkTestServer(zkDir);

    SolrZkClient zkClient = null;

    try {
      server.run();

      zkClient =
          new SolrZkClient.Builder()
              .withUrl(server.getZkAddress())
              .withTimeout(OverseerTest.DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
              .build();
      ZkController.createClusterZkNodes(zkClient);

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        ZkStateWriter writer =
            new ZkStateWriter(reader, new Stats(), -1, STATE_COMPRESSION_PROVIDER);

        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c1", true);
        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c2", true);

        ClusterState state = reader.getClusterState();

        // create collection 2
        ZkWriteCommand c2 =
            new ZkWriteCommand(
                "c2",
                createDocCollection(
                    "c2",
                    Collections.singletonMap(
                        ZkStateReader.CONFIGNAME_PROP, ConfigSetsHandler.DEFAULT_CONFIGSET_NAME)));
        state = writer.enqueueUpdate(state, Collections.singletonList(c2), null);
        assertFalse(writer.hasPendingUpdates()); // first write is flushed immediately

        int c2Version = state.getCollection("c2").getZNodeVersion();

        // Simulate an external modification to /collections/c2/state.json
        byte[] data = zkClient.getData(DocCollection.getCollectionPath("c2"), null, null, true);
        zkClient.setData(DocCollection.getCollectionPath("c2"), data, true);

        // get the most up-to-date state
        reader.forceUpdateCollection("c2");
        state = reader.getClusterState();

        Stat stat = new Stat();
        zkClient.getData(ZkStateReader.getCollectionPath("c2"), null, stat, false);
        assertEquals(
            Instant.ofEpochMilli(stat.getCtime()), state.getCollection("c2").getCreationTime());

        log.info("Cluster state: {}", state);
        assertTrue(state.hasCollection("c2"));
        assertEquals(c2Version + 1, state.getCollection("c2").getZNodeVersion());

        writer.enqueueUpdate(state, Collections.singletonList(c2), null);
        assertTrue(writer.hasPendingUpdates());

        // get the most up-to-date state
        reader.forceUpdateCollection("c2");
        state = reader.getClusterState();

        // Will trigger flush
        Thread.sleep(Overseer.STATE_UPDATE_DELAY + 100);
        ZkWriteCommand c1 =
            new ZkWriteCommand(
                "c1",
                createDocCollection(
                    "c1",
                    Collections.singletonMap(
                        ZkStateReader.CONFIGNAME_PROP, ConfigSetsHandler.DEFAULT_CONFIGSET_NAME)));

        try {
          writer.enqueueUpdate(state, Collections.singletonList(c1), null);
          fail("Enqueue should not have succeeded");
        } catch (KeeperException.BadVersionException bve) {
          // expected
        }

        try {
          writer.enqueueUpdate(reader.getClusterState(), Collections.singletonList(c2), null);
          fail("enqueueUpdate after BadVersionException should not have succeeded");
        } catch (IllegalStateException e) {
          // expected
        }

        try {
          writer.writePendingUpdates();
          fail("writePendingUpdates after BadVersionException should not have succeeded");
        } catch (IllegalStateException e) {
          // expected
        }
      }
    } finally {
      IOUtils.close(zkClient);
      server.shutdown();
    }
  }

  public void testSingleExternalCollectionCompressedState() throws Exception {
    Path zkDir = createTempDir("testSingleExternalCollection");

    ZkTestServer server = new ZkTestServer(zkDir);

    SolrZkClient zkClient = null;

    Compressor compressor = new ZLibCompressor();

    try {
      server.run();

      zkClient =
          new SolrZkClient.Builder()
              .withUrl(server.getZkAddress())
              .withConnTimeOut(OverseerTest.DEFAULT_CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
              .build();
      ZkController.createClusterZkNodes(zkClient);

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        ZkStateWriter writer = new ZkStateWriter(reader, new Stats(), 500000, compressor);

        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c1", true);

        // create new collection with stateFormat = 2
        ZkWriteCommand c1 = new ZkWriteCommand("c1", createDocCollection("c1", new HashMap<>()));

        writer.enqueueUpdate(reader.getClusterState(), Collections.singletonList(c1), null);
        writer.writePendingUpdates();

        byte[] data =
            zkClient.getData(ZkStateReader.COLLECTIONS_ZKNODE + "/c1/state.json", null, null, true);
        Map<?, ?> map = (Map<?, ?>) Utils.fromJSON(data);
        assertNotNull(map.get("c1"));
      }

      try (ZkStateReader reader = new ZkStateReader(zkClient)) {
        reader.createClusterStateWatchersAndUpdate();

        ZkStateWriter writer = new ZkStateWriter(reader, new Stats(), 500000, compressor);

        zkClient.makePath(ZkStateReader.COLLECTIONS_ZKNODE + "/c2", true);

        // create new collection with stateFormat = 2 that is large enough to exceed the minimum
        // size for compression
        Map<String, Slice> slices = new HashMap<>();
        for (int i = 0; i < 4096; i++) {
          Map<String, Replica> replicas = new HashMap<>();
          Map<String, Object> replicaProps = new HashMap<>();
          replicaProps.put(ZkStateReader.NODE_NAME_PROP, "node1:8983_8983");
          replicaProps.put(ZkStateReader.CORE_NAME_PROP, "coreNode" + i);
          replicaProps.put(ZkStateReader.REPLICA_TYPE, "NRT");
          replicaProps.put(ZkStateReader.BASE_URL_PROP, "http://localhost:8983");
          replicas.put(
              "coreNode" + i, new Replica("coreNode" + i, replicaProps, "c2", "shard" + i));
          slices.put("shard" + i, new Slice("shard" + i, replicas, new HashMap<>(), "c2"));
        }
        ZkWriteCommand c1 =
            new ZkWriteCommand(
                "c2",
                DocCollection.create(
                    "c2", slices, new HashMap<>(), DocRouter.DEFAULT, 0, Instant.now(), null));

        writer.enqueueUpdate(reader.getClusterState(), Collections.singletonList(c1), null);
        writer.writePendingUpdates();

        byte[] dataCompressed =
            zkClient
                .getCuratorFramework()
                .getData()
                .undecompressed()
                .forPath(ZkStateReader.COLLECTIONS_ZKNODE + "/c2/state.json");
        assertTrue(compressor.isCompressedBytes(dataCompressed));
        Map<?, ?> map = (Map<?, ?>) Utils.fromJSON(compressor.decompressBytes(dataCompressed));
        assertNotNull(map.get("c2"));

        byte[] dataDecompressed =
            zkClient
                .getCuratorFramework()
                .getData()
                .forPath(ZkStateReader.COLLECTIONS_ZKNODE + "/c2/state.json");
        assertFalse(compressor.isCompressedBytes(dataDecompressed));
        map = (Map<?, ?>) Utils.fromJSON(dataDecompressed);
        assertNotNull(map.get("c2"));
      }

    } finally {
      IOUtils.close(zkClient);
      server.shutdown();
    }
  }

  private DocCollection createDocCollection(String name, Map<String, Object> props) {
    return DocCollection.create(
        name, new HashMap<>(), props, DocRouter.DEFAULT, 0, Instant.now(), null);
  }
}

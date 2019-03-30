/**
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
package org.apache.iotdb.cluster.utils.hash;

import com.alipay.sofa.jraft.util.OnlyForTest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.iotdb.cluster.config.ClusterConfig;
import org.apache.iotdb.cluster.config.ClusterDescriptor;
import org.apache.iotdb.cluster.exception.ErrorConfigureExecption;

/**
 * Cluster router, it's responsible for hash mapping and routing to specified data groups
 */
public class Router {

  /**
   * Replication number
   */
  private int replicator;

  /**
   * A local cache to store Which nodes do a storage group correspond to
   */
  private Map<String, PhysicalNode[]> sgRouter = new ConcurrentHashMap<>();

  /**
   * Key is the first node of the group, value is all physical node groups which contain this node
   */
  private Map<PhysicalNode, PhysicalNode[][]> dataPartitionCache = new HashMap<>();

  /**
   * Key is the first node of the group, value is group id.
   */
  private Map<PhysicalNode, String> nodeMapGroupIdCache = new HashMap<>();

  /**
   * Key is group id, value is the first node of the group.
   */
  private Map<String, PhysicalNode> groupIdMapNodeCache = new HashMap<>();

  /**
   * Data group name prefix
   */
  public static final String DATA_GROUP_STR = "data-group-";

  private HashFunction hashFunction = new MD5Hash();
  private final SortedMap<Integer, PhysicalNode> physicalRing = new TreeMap<>();
  private final SortedMap<Integer, VirtualNode> virtualRing = new TreeMap<>();

  private static class RouterHolder {

    private static final Router INSTANCE = new Router();
  }

  private Router() {
    init();
  }

  public static final Router getInstance() {
    return RouterHolder.INSTANCE;
  }

  /**
   * Change this method to public for test, you should not invoke this method explicitly.
   */
  public void init() {
    reset();
    ClusterConfig config = ClusterDescriptor.getInstance().getConfig();
    String[] hosts = config.getNodes();
    this.replicator = config.getReplication();
    int numOfVirtualNodes = config.getNumOfVirtulaNodes();
    for (String host : hosts) {
      String[] values = host.split(":");
      PhysicalNode node = new PhysicalNode(values[0], Integer.parseInt(values[1]));
      addNode(node, numOfVirtualNodes);
    }
    PhysicalNode[] nodes = physicalRing.values().toArray(new PhysicalNode[physicalRing.size()]);
    int len = nodes.length;
    if (len == replicator) {
      PhysicalNode first = nodes[0];
      PhysicalNode[][] val = new PhysicalNode[1][len];
      nodeMapGroupIdCache.put(first, DATA_GROUP_STR + "0");
      groupIdMapNodeCache.put(DATA_GROUP_STR + "0", first);
      for (int j = 0; j < len; j++) {
        val[0][j] = nodes[j];
      }
      dataPartitionCache.put(first, val);
    } else {
      for (int i = 0; i < len; i++) {
        PhysicalNode first = nodes[i];
        if (len < replicator) {
          throw new ErrorConfigureExecption(String.format("Replicator number %d is greater "
              + "than cluster number %d", replicator, len));
        } else {
          PhysicalNode[][] val = new PhysicalNode[replicator][replicator];
          nodeMapGroupIdCache.put(first, DATA_GROUP_STR + i);
          groupIdMapNodeCache.put(DATA_GROUP_STR + i, first);
          for (int j = 0; j < replicator; j++) {
            for (int k = 0; k < replicator; k++) {
              val[j][k] = nodes[(i - j + k + len) % len];
            }
          }
          dataPartitionCache.put(first, val);
        }
      }
    }
  }

  /**
   * Calculate the physical nodes corresponding to the replications where a data point is located
   *
   * @param objectKey storage group
   */
  public PhysicalNode[] routeGroup(String objectKey) {
    if (sgRouter.containsKey(objectKey)) {
      return sgRouter.get(objectKey);
    }
    PhysicalNode node = routeNode(objectKey);
    PhysicalNode[] nodes = dataPartitionCache.get(node)[0];
    sgRouter.put(objectKey, nodes);
    return nodes;
  }

  public String getGroupID(PhysicalNode[] nodes) {
    return nodeMapGroupIdCache.get(nodes[0]);
  }

  public PhysicalNode[][] getGroupsNodes(String ip, int port) {
    return this.getGroupsNodes(new PhysicalNode(ip, port));
  }

  /**
   * Add a new node to cluster
   */
  private void addNode(PhysicalNode node, int virtualNum) {
    physicalRing.put(hashFunction.hash(node.getKey()), node);
    for (int i = 0; i < virtualNum; i++) {
      VirtualNode vNode = new VirtualNode(i, node);
      virtualRing.put(hashFunction.hash(vNode.getKey()), vNode);
    }
  }

  /**
   * For a storage group, compute the nearest physical node on the hash ring
   */
  public PhysicalNode routeNode(String objectKey) {
    int hashVal = hashFunction.hash(objectKey);
    SortedMap<Integer, VirtualNode> tailMap = virtualRing.tailMap(hashVal);
    Integer nodeHashVal = !tailMap.isEmpty() ? tailMap.firstKey() : virtualRing.firstKey();
    return virtualRing.get(nodeHashVal).getPhysicalNode();
  }


  /**
   * For a given physical, how many groups does it belong to
   *
   * @param node first node of a group
   */
  private PhysicalNode[][] getGroupsNodes(PhysicalNode node) {
    return dataPartitionCache.get(node);
  }

  private void reset() {
    physicalRing.clear();
    virtualRing.clear();
    sgRouter.clear();
    dataPartitionCache.clear();
    nodeMapGroupIdCache.clear();
  }

  @OnlyForTest
  public void showPhysicalRing() {
    for (Entry<Integer, PhysicalNode> entry : physicalRing.entrySet()) {
      System.out.println(String.format("%d-%s", entry.getKey(), entry.getValue().getKey()));
    }
  }

  @OnlyForTest
  public void showVirtualRing() {
    for (Entry<Integer, VirtualNode> entry : virtualRing.entrySet()) {
      System.out.println(String.format("%d-%s", entry.getKey(), entry.getValue().getKey()));
    }
  }

  public boolean containPhysicalNode(String storageGroup, PhysicalNode node) {
    PhysicalNode[] nodes = routeGroup(storageGroup);
    return Arrays.asList(nodes).contains(node);
  }

  /**
   * Show physical nodes by group id.
   */
  public void showPhysicalNodes(String groupId) {
    PhysicalNode[] physicalPlans = getNodesByGroupId(groupId);
    for (PhysicalNode node : physicalPlans) {
      System.out.println(node);
    }
  }

  /**
   * Get Physical node by group id.
   */
  public PhysicalNode[] getNodesByGroupId(String groupId) {
    PhysicalNode node = groupIdMapNodeCache.get(groupId);
    return dataPartitionCache.get(node)[0];
  }
}

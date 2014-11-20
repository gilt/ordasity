//
// Copyright 2011-2012, Boundary
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.boundary.ordasity.balancing

import com.codahale.metrics.MetricRegistry
import nl.grons.metrics.scala.InstrumentedBuilder
import org.slf4j.LoggerFactory

import collection.JavaConversions._
import com.boundary.ordasity.{ZKUtils, NodeState, ClusterConfig, Cluster}
import java.util.{TimerTask, LinkedList}
import java.util.concurrent.{CountDownLatch, TimeUnit}

/**
 * A balancing policy determines how a node in an Ordasity cluster should claim /
 * unclaim work and rebalance load about the cluster. Currently, there are two
 * implementations: CountBalancingPolicy and MeteredBalancingPolicy.
 */
abstract class BalancingPolicy(cluster: Cluster, config: ClusterConfig)
  extends InstrumentedBuilder {
  override val metricRegistry = new MetricRegistry()
  val log = LoggerFactory.getLogger(getClass)

  // Implementation required
  def claimWork()
  def rebalance()

  // Implementation optional
  def init() : BalancingPolicy = this
  def shutdown() { }
  def onConnect() { }
  def onShutdownWork(workUnit: String) { }

  def activeNodeSize() : Int = {
    cluster.nodes.filter { n =>
      val (nodeName, nodeInfo) = n
      nodeInfo != null && nodeInfo.state == NodeState.Started.toString
    }.size
  }

  /**
   * Returns a set of work units which are unclaimed throughout the cluster.
   */
  def getUnclaimed() : Set[String] = cluster.allWorkUnits.synchronized {
    cluster.allWorkUnits.keys.toSet --
    cluster.workUnitMap.keys.toSet ++
    cluster.handoffRequests.keySet --
    cluster.handoffResults.keys --
    cluster.myWorkUnits
  }

  /**
    * Determines whether or not a given work unit is designated "claimable" by this node.
    * If the ZNode for this work unit is empty, or contains JSON mapping this node to that
    * work unit, it's considered "claimable."
   */
  def isFairGame(workUnit: String) : Boolean = {
    val workUnitData = cluster.allWorkUnits.get(workUnit)
    if (workUnitData == null || workUnitData.size() == 0)
      return true

    try {
      val pegged = workUnitData.get(cluster.name)
      if (pegged == null) {
        return true
      }
      log.debug("Pegged status for %s: %s.".format(workUnit, pegged))
      pegged.asText().equals(cluster.myNodeID)
    } catch {
      case e: Exception =>
        log.error("Error parsing mapping for %s: %s".format(workUnit, workUnitData), e)
        true
    }
  }


  /**
   * Determines whether or not a given work unit is pegged to this instance.
   */
  def isPeggedToMe(workUnitId: String) : Boolean = {
    val zkWorkData = cluster.allWorkUnits.get(workUnitId)
    if (zkWorkData == null || zkWorkData.size() == 0) {
      cluster.workUnitsPeggedToMe.remove(workUnitId)
      return false
    }

    try {
      val pegged = zkWorkData.get(cluster.name)
      val isPegged = pegged != null && pegged.asText().equals(cluster.myNodeID)

      if (isPegged) {
        cluster.workUnitsPeggedToMe.add(workUnitId)
      } else {
        cluster.workUnitsPeggedToMe.remove(workUnitId)
      }
      
      isPegged
    } catch {
      case e: Exception =>
        log.error("Error parsing mapping for %s: %s".format(workUnitId, zkWorkData), e)
        false
    }
  }

  /**
   * Attempts to claim a given work unit by creating an ephemeral node in ZooKeeper
   * with this node's ID. If the claim succeeds, start work. If not, move on.
   */
  def attemptToClaim(workUnit: String, claimForHandoff: Boolean = false) : Boolean = {
    log.debug("Attempting to claim %s. For handoff? %s".format(workUnit, claimForHandoff))

    val path = {
      if (claimForHandoff) "/%s/handoff-result/%s".format(cluster.name, workUnit)
      else cluster.workUnitClaimPath(workUnit)
    }

    val created = ZKUtils.createEphemeral(cluster.zk, path, cluster.myNodeID)

    if (created) {
      if (claimForHandoff) cluster.claimedForHandoff.add(workUnit)
      cluster.startWork(workUnit)
      true
    } else if (isPeggedToMe(workUnit)) {
      claimWorkPeggedToMe(workUnit)
      true
    } else {
      false
    }
  }

  /**
   * Claims a work unit pegged to this node, waiting for the ZNode to become available
   * (i.e., deleted by the node which previously owned it).
   */
  protected def claimWorkPeggedToMe(workUnit: String) {
    val path = cluster.workUnitClaimPath(workUnit)

    while (true) {
      if (ZKUtils.createEphemeral(cluster.zk, path, cluster.myNodeID) || cluster.znodeIsMe(path)) {
        cluster.startWork(workUnit)
        return
      }
      log.warn("Attempting to establish ownership of %s. Retrying in one second...".format(workUnit))
      Thread.sleep(1000)
    }
  }

  /**
   * Drains this node's share of the cluster workload down to a specific number
   * of work units over a period of time specified in the configuration with
   * soft handoff if enabled..
   */
  def drainToCount(targetCount: Int, doShutdown: Boolean = false,
                   useHandoff: Boolean = config.useSoftHandoff,
                   latch: Option[CountDownLatch] = None) {
    val msg = if (useHandoff) " with handoff" else ""
    log.info("Draining %s%s. Target count: %s, Current: %s".format(
      config.workUnitName, msg, targetCount, cluster.myWorkUnits.size))

    if (targetCount >= cluster.myWorkUnits.size) {
      if (!doShutdown)
        return
      else if (targetCount == 0 && doShutdown)
        cluster.completeShutdown()
    }

    val amountToDrain = cluster.myWorkUnits.size - targetCount

    val msgPrefix = if (useHandoff) "Requesting handoff for" else "Shutting down"
    log.info("%s %s of %s %s over %s seconds".format(
      msgPrefix, amountToDrain, cluster.myWorkUnits.size, config.workUnitName, config.drainTime))

    // Build a list of work units to hand off.
    val toHandOff = new LinkedList[String]
    val wuList = new LinkedList[String](cluster.myWorkUnits -- cluster.workUnitsPeggedToMe)
    for (i <- (0 to amountToDrain - 1))
      if (wuList.size - 1 >= i) toHandOff.add(wuList(i))

    val drainInterval = ((config.drainTime.toDouble / toHandOff.size) * 1000).intValue()

    val handoffTask = new TimerTask {
      def run() {
        if (toHandOff.isEmpty) {
          if (targetCount == 0 && doShutdown)  {
            cluster.completeShutdown()
          }
          latch.foreach(l => l.countDown())
          return
        } else {
          val workUnit = toHandOff.poll()
          if (useHandoff && !isPeggedToMe(workUnit)) cluster.requestHandoff(workUnit)
          else cluster.shutdownWork(workUnit)
        }
        cluster.pool.get.schedule(this, drainInterval, TimeUnit.MILLISECONDS)
      }
    }

    log.info("Releasing %s / %s work units over %s seconds: %s".format(
      amountToDrain, cluster.myWorkUnits.size, config.drainTime, toHandOff.mkString(", ")))

    if (!cluster.myWorkUnits.isEmpty)
      cluster.pool.get.schedule(handoffTask, 0, TimeUnit.SECONDS)
  }

}

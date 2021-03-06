/**
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

package net.elodina.mesos.exhibitor

import java.util.Date

import net.elodina.mesos.util.Period
import org.junit.Assert._
import org.junit.{Before, Test}
import play.api.libs.json.Json

import scala.collection.JavaConversions._

class ExhibitorTest extends MesosTestCase {
  var server: Exhibitor = null

  @Before
  override def before() {
    super.before()
    server = new Exhibitor("0")
    server.config.cpus = 0
    server.config.mem = 0
  }

  @Test
  def matchesHostname() {
    assertEquals(None, server.matches(offer(hostname = "master")))
    assertEquals(None, server.matches(offer(hostname = "slave0")))

    // like
    server.constraints.clear()
    server.constraints += "hostname" -> List(Constraint("like:master"))
    assertEquals(None, server.matches(offer(hostname = "master")))
    assertEquals(Some("hostname doesn't match like:master"), server.matches(offer(hostname = "slave0")))

    server.constraints.clear()
    server.constraints += "hostname" -> List(Constraint("like:master.*"))
    assertEquals(None, server.matches(offer(hostname = "master")))
    assertEquals(None, server.matches(offer(hostname = "master-2")))
    assertEquals(Some("hostname doesn't match like:master.*"), server.matches(offer(hostname = "slave0")))

    // unique
    server.constraints.clear()
    server.constraints += "hostname" -> List(Constraint("unique"))
    assertEquals(None, server.matches(offer(hostname = "master")))
    assertEquals(Some("hostname doesn't match unique"), server.matches(offer(hostname = "master"), new Date(), _ => List("master")))
    assertEquals(None, server.matches(offer(hostname = "master"), new Date(), _ => List("slave0")))

    // multiple
    server.constraints.clear()
    server.constraints += "hostname" -> List(Constraint("unique"), Constraint("like:slave.*"))
    assertEquals(None, server.matches(offer(hostname = "slave0")))
    assertEquals(Some("hostname doesn't match unique"), server.matches(offer(hostname = "slave0"), new Date(), _ => List("slave0")))
    assertEquals(Some("hostname doesn't match like:slave.*"), server.matches(offer(hostname = "master")))
    assertEquals(None, server.matches(offer(hostname = "slave0"), new Date(), _ => List("master")))
  }

  @Test
  def matchesAttributes() {
    // like
    server.constraints.clear()
    server.constraints += "rack" -> List(Constraint("like:1-.*"))
    assertEquals(None, server.matches(offer(attributes = "rack=1-1")))
    assertEquals(None, server.matches(offer(attributes = "rack=1-2")))
    assertEquals(Some("rack doesn't match like:1-.*"), server.matches(offer(attributes = "rack=2-1")))

    // unique
    server.constraints.clear()
    server.constraints += "floor" -> List(Constraint("unique"))
    assertEquals(None, server.matches(offer(attributes = "rack=1-1,floor=1")))
    assertEquals(None, server.matches(offer(attributes = "rack=1-1,floor=1"), new Date(), _ => List("2")))
    assertEquals(Some("floor doesn't match unique"), server.matches(offer(attributes = "rack=1-1,floor=1"), new Date(), _ => List("1")))
  }

  @Test
  def idFromTaskId() {
    assertEquals("0", Exhibitor.idFromTaskId(Exhibitor.nextTaskId("0")))
    assertEquals("100", Exhibitor.idFromTaskId(Exhibitor.nextTaskId("100")))
  }

  @Test
  def json() {
    server.state = Exhibitor.Staging
    server.constraints.clear()
    server.constraints += "hostname" -> List(Constraint("unique"))
    server.config.cpus = 1.2
    server.config.mem = 2048
    server.config.hostname = "slave0"
    server.config.sharedConfigChangeBackoff = 5000
    server.config.exhibitorConfig += "zkconfigconnect" -> "192.168.3.1:2181"
    server.config.sharedConfigOverride += "zookeeper-install-directory" -> "/tmp/zookeeper"

    val decoded = Json.toJson(server).as[Exhibitor]
    ExhibitorTest.assertServerEquals(server, decoded)
  }

  @Test
  def newExecutor() {
    val exhibitor = Exhibitor("1")
    exhibitor.config.cpus = 1.5

    val executor = exhibitor.newExecutor("")
    val command = executor.getCommand
    assertTrue(command.getUrisCount > 0)

    val cmd = command.getValue
    assertTrue(cmd, cmd.contains(Executor.getClass.getName.replace("$", "")))
  }

  @Test
  def newTask() {
    val exhibitor = Exhibitor("1")
    exhibitor.config.cpus = 1.5
    exhibitor.config.mem = 1024

    val offer = this.offer(slaveId = "slave0", hostname = "host")
    val reservation = Reservation(1.5, 1024, 4000, 4001, 4002, 4003)

    val task = exhibitor.createTask(offer, reservation)
    assertEquals("slave0", task.getSlaveId.getValue)
    assertNotNull(task.getExecutor)

    val resources = task.getResourcesList.toList.map(res => res.getName -> res).toMap

    val cpuResourceOpt = resources.get("cpus")
    assertNotEquals(None, cpuResourceOpt)
    val cpuResource = cpuResourceOpt.get
    assertEquals(exhibitor.config.cpus, cpuResource.getScalar.getValue, 0.001)

    val memResourceOpt = resources.get("mem")
    assertNotEquals(None, memResourceOpt)
    val memResource = memResourceOpt.get
    assertEquals(exhibitor.config.mem, memResource.getScalar.getValue, 0.001)

    val portsResourceOpt = resources.get("ports")
    assertNotEquals(None, portsResourceOpt)
    val portsResource = portsResourceOpt.get
    assertEquals(4, portsResource.getRanges.getRangeCount)
  }

  @Test
  def acceptOffer() {
    val exhibitor = Exhibitor("1")
    val offer = this.offer(cpus = exhibitor.config.cpus, mem = exhibitor.config.mem.toLong)

    val allServersRunning = Scheduler.acceptOffer(offer)
    assertEquals(allServersRunning, Some("all servers are running"))

    Scheduler.cluster.defaultEnsemble().addServer(exhibitor)
    exhibitor.state = Exhibitor.Stopped
    val accepted = Scheduler.acceptOffer(offer)
    assertEquals(None, accepted)
    assertEquals(1, schedulerDriver.launchedTasks.size())
    assertEquals(0, schedulerDriver.killedTasks.size())
  }

  @Test
  def ports() {
    def port(taskPorts: String, offerPorts: String): Option[Long] = {
      val exhibitor = Exhibitor("0")
      exhibitor.config.ports = Util.Range.parseRanges(taskPorts)
      val offer = this.offer(ports = offerPorts)
      exhibitor.getPort(offer)
    }

    // any port
    assertEquals(Some(31000), port("", "31000..32000"))

    // overlapping single port
    assertEquals(Some(31010), port("31010", "31000..32000"))

    // overlapping port range
    assertEquals(Some(31010), port("31010..31100", "31000..32000"))

    // overlapping second port range
    assertEquals(Some(31020), port("4000..4100,31020..31100", "31000..32000"))

    // no match
    assertEquals(None, port("4000..4100", "31000..32000"))
  }

  @Test
  def stickiness_allowsHostname {
    val stickiness = Stickiness()
    assertTrue(stickiness.allowsHostname("host0", new Date(0)))
    assertTrue(stickiness.allowsHostname("host1", new Date(0)))

    stickiness.registerStart("host0")
    stickiness.registerStop(new Date(0))
    assertTrue(stickiness.allowsHostname("host0", new Date(0)))
    assertFalse(stickiness.allowsHostname("host1", new Date(0)))
    assertTrue(stickiness.allowsHostname("host1", new Date(stickiness.period.ms)))
  }

  @Test
  def stickiness_registerStart_registerStop {
    val stickiness = Stickiness()
    assertTrue(stickiness.hostname.isEmpty)
    assertTrue(stickiness.stopTime.isEmpty)

    stickiness.registerStart("host")
    assertEquals(Some("host"), stickiness.hostname)
    assertTrue(stickiness.stopTime.isEmpty)

    stickiness.registerStop(new Date(0))
    assertEquals(Some("host"), stickiness.hostname)
    assertEquals(Some(new Date(0)), stickiness.stopTime)

    stickiness.registerStart("host1")
    assertEquals(Some("host1"), stickiness.hostname)
    assertTrue(stickiness.stopTime.isEmpty)
  }

  // Failover
  @Test
  def failover_currentDelay {
    val failover = new Failover(new Period("1s"), new Period("5s"))

    failover.failures = 0
    assertEquals(new Period("0s"), failover.currentDelay)

    failover.failures = 1
    assertEquals(new Period("1s"), failover.currentDelay)

    failover.failures = 2
    assertEquals(new Period("2s"), failover.currentDelay)

    failover.failures = 3
    assertEquals(new Period("4s"), failover.currentDelay)

    failover.failures = 4
    assertEquals(new Period("5s"), failover.currentDelay)

    failover.failures = 100
    assertEquals(new Period("5s"), failover.currentDelay)
  }

  @Test
  def failover_delayExpires {
    val failover = new Failover(new Period("1s"))
    assertEquals(new Date(0), failover.delayExpires)

    failover.registerFailure(new Date(0))
    assertEquals(new Date(1000), failover.delayExpires)

    failover.failureTime = Some(new Date(1000))
    assertEquals(new Date(2000), failover.delayExpires)
  }

  @Test
  def failover_isWaitingDelay {
    val failover = new Failover(new Period("1s"))
    assertFalse(failover.isWaitingDelay(new Date(0)))

    failover.registerFailure(new Date(0))

    assertTrue(failover.isWaitingDelay(new Date(0)))
    assertTrue(failover.isWaitingDelay(new Date(500)))
    assertTrue(failover.isWaitingDelay(new Date(999)))
    assertFalse(failover.isWaitingDelay(new Date(1000)))
  }

  @Test
  def failover_isMaxTriesExceeded {
    val failover = new Failover()

    failover.failures = 100
    assertFalse(failover.isMaxTriesExceeded)

    failover.maxTries = Some(50)
    assertTrue(failover.isMaxTriesExceeded)
  }

  @Test
  def failover_registerFailure_resetFailures {
    val failover = new Failover()
    assertEquals(0, failover.failures)
    assertTrue(failover.failureTime.isEmpty)

    failover.registerFailure(new Date(1))
    assertEquals(1, failover.failures)
    assertEquals(new Date(1), failover.failureTime.get)

    failover.registerFailure(new Date(2))
    assertEquals(2, failover.failures)
    assertEquals(new Date(2), failover.failureTime.get)

    failover.resetFailures()
    assertEquals(0, failover.failures)
    assertTrue(failover.failureTime.isEmpty)

    failover.registerFailure()
    assertEquals(1, failover.failures)
  }
}

object ExhibitorTest {
  def assertServerEquals(expected: Exhibitor, actual: Exhibitor) {
    assertEquals(expected.state, actual.state)
    assertEquals(expected.constraints, actual.constraints)
    assertEquals(expected.config.cpus, actual.config.cpus, 0.001)
    assertEquals(expected.config.mem, actual.config.mem, 0.001)
    assertEquals(expected.config.hostname, actual.config.hostname)
    assertEquals(expected.config.sharedConfigChangeBackoff, actual.config.sharedConfigChangeBackoff)
    assertEquals(expected.config.exhibitorConfig, actual.config.exhibitorConfig)
    assertEquals(expected.config.sharedConfigOverride, actual.config.sharedConfigOverride)
  }
}

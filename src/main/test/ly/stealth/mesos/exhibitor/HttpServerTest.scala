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

package ly.stealth.mesos.exhibitor

import ly.stealth.mesos.exhibitor.Cli.sendRequest
import ly.stealth.mesos.exhibitor.Util.parseMap
import org.junit.Assert._
import org.junit.{After, Before, Test}

import scala.collection.mutable

class HttpServerTest extends MesosTestCase {
  @Before
  override def before() {
    super.before()
    Config.api = "http://localhost:8000"
    HttpServer.start(resolveDeps = false)
  }

  @After
  override def after() {
    HttpServer.stop()
    super.after()
  }

  @Test
  def addServer() {
    val responseServer = sendRequest("/add", parseMap("id=0,cpu=0.6,mem=128")).as[ExhibitorServer]
    assertEquals(1, Scheduler.cluster.servers.size)
    val server = Scheduler.cluster.servers.head

    assertEquals("0", server.id)
    assertEquals(0.6, server.config.cpus, 0.001)
    assertEquals(128, server.config.mem, 0.001)

    ExhibitorServerTest.assertServerEquals(server, responseServer)
  }

  @Test
  def configServer() {
    sendRequest("/add", parseMap("id=0"))
    val responseServer = sendRequest("/config", parseMap("id=0,zkconfigconnect=192.168.3.1:2181,zookeeper-install-directory=/tmp/zookeeper")).as[ExhibitorServer]

    val serverOpt = Scheduler.cluster.getServer("0")
    assertNotEquals(None, serverOpt)

    val server = serverOpt.get
    assertEquals("0", server.id)
    assertEquals(mutable.Map("zkconfigconnect" -> "192.168.3.1:2181"), server.config.exhibitorConfig)
    assertEquals(mutable.Map("zookeeper-install-directory" -> "/tmp/zookeeper"), server.config.sharedConfigOverride)

    ExhibitorServerTest.assertServerEquals(server, responseServer)
  }

  @Test
  def clusterStatus() {
    sendRequest("/add", parseMap("id=0"))
    sendRequest("/add", parseMap("id=1"))
    sendRequest("/add", parseMap("id=2"))

    val cluster = sendRequest("/status", Map()).as[List[ExhibitorServer]]
    assertEquals(3, cluster.size)
    assertEquals(3, cluster.map(_.id).distinct.size)
  }

  @Test
  def removeServer() {
    sendRequest("/add", parseMap("id=0"))
    sendRequest("/add", parseMap("id=1"))
    sendRequest("/add", parseMap("id=2"))

    sendRequest("/remove", parseMap("id=1"))
    assertEquals(2, Scheduler.cluster.servers.size)
  }

  @Test
  def startStopServer() {
    sendRequest("/add", parseMap("id=0"))

    val responseServer = sendRequest("/start", parseMap("id=0")).as[ExhibitorServer]
    assertEquals(ExhibitorServer.Stopped, responseServer.state)

    val stoppedServer = sendRequest("/stop", parseMap("id=0")).as[ExhibitorServer]
    assertEquals(ExhibitorServer.Added, stoppedServer.state)
  }
}
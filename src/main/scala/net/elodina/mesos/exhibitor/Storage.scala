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

import java.io.{File, FileWriter}

import org.I0Itec.zkclient.ZkClient
import org.I0Itec.zkclient.exception.ZkNodeExistsException
import org.I0Itec.zkclient.serialize.ZkSerializer
import play.api.libs.json.{Json, Reads, Writes}

import scala.io.Source

trait Storage[T] {
  def save(value: T)(implicit writes: Writes[T])

  def load(implicit reads: Reads[T]): Option[T]
}

case class FileStorage[T](file: String) extends Storage[T] {
  override def save(value: T)(implicit writes: Writes[T]) {
    val writer = new FileWriter(file)
    try {
      writer.write(Json.stringify(Json.toJson(value)))
    } finally {
      writer.close()
    }
  }

  override def load(implicit reads: Reads[T]): Option[T] = {
    if (!new File(file).exists()) None
    else Json.parse(Source.fromFile(file).mkString).asOpt[T]
  }
}

case class ZkStorage[T](zk: String) extends Storage[T] {
  val (zkConnect, path) = zk.span(_ != '/')
  createChrootIfRequired()

  private def createChrootIfRequired() {
    if (path != "") {
      val client = zkClient
      try {
        client.createPersistent(path, true)
      }
      finally {
        client.close()
      }
    }
  }

  private def zkClient: ZkClient = new ZkClient(zkConnect, 30000, 30000, ZKStringSerializer)

  override def save(value: T)(implicit writes: Writes[T]) {
    val client = zkClient
    val json = Json.stringify(Json.toJson(value))
    try {
      client.createPersistent(path, json)
    }
    catch {
      case e: ZkNodeExistsException => client.writeData(path, json)
    }
    finally {
      client.close()
    }
  }

  override def load(implicit reads: Reads[T]): Option[T] = {
    val client = zkClient
    try {
      Option(client.readData(path, true).asInstanceOf[String]).flatMap(Json.parse(_).asOpt[T])
    }
    finally {
      client.close()
    }
  }
}

private object ZKStringSerializer extends ZkSerializer {
  def serialize(data: Object): Array[Byte] = data.asInstanceOf[String].getBytes("UTF-8")

  def deserialize(bytes: Array[Byte]): Object = {
    if (bytes == null) null
    else new String(bytes, "UTF-8")
  }
}
package net.elodina.mesos.exhibitor

import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.collection.mutable

case class TaskConfig(exhibitorConfig: mutable.Map[String, String], sharedConfigOverride: mutable.Map[String, String], id: String, var hostname: String = "", var sharedConfigChangeBackoff: Long = 10000, var cpus: Double = 0.2, var mem: Double = 256, var ports: List[Util.Range] = Nil)

object TaskConfig {
  implicit val reader = (
    (__ \ 'exhibitorConfig).read[Map[String, String]].map(m => mutable.Map(m.toSeq: _*)) and
      (__ \ 'sharedConfigOverride).read[Map[String, String]].map(m => mutable.Map(m.toSeq: _*)) and
      (__ \ 'id).read[String] and
      (__ \ 'hostname).read[String] and
      (__ \ 'sharedConfigChangeBackoff).read[Long] and
      (__ \ 'cpu).read[Double] and
      (__ \ 'mem).read[Double] and
      (__ \ 'ports).read[String].map(Util.Range.parseRanges)) (TaskConfig.apply _)

  implicit val writer = new Writes[TaskConfig] {
    def writes(tc: TaskConfig): JsValue = {
      Json.obj(
        "exhibitorConfig" -> tc.exhibitorConfig.toMap[String, String],
        "sharedConfigOverride" -> tc.sharedConfigOverride.toMap[String, String],
        "id" -> tc.id,
        "hostname" -> tc.hostname,
        "cpu" -> tc.cpus,
        "mem" -> tc.mem,
        "sharedConfigChangeBackoff" -> tc.sharedConfigChangeBackoff,
        "ports" -> tc.ports.mkString(",")
      )
    }
  }
}
/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.projection

import akka.annotation.ApiMayChange
import com.lightbend.lagom.internal.projection.ProjectionRegistryActor.WorkerCoordinates

import scala.util.control.NoStackTrace

@ApiMayChange
// TODO: provide serialisers (all the ADT is on LWWMaps so must be serializable)
sealed trait Status
sealed trait Stopped extends Status
case object Stopped  extends Stopped
sealed trait Started extends Status
case object Started  extends Started

@ApiMayChange
case class Worker(tagName: String, key: String, requestedStatus: Status, observedStatus: Status)

@ApiMayChange
case class Projection(name: String, workers: Seq[Worker])

@ApiMayChange
case class State(projections: Seq[Projection]) {
  def findProjection(projectionName: String): Option[Projection] =
    projections.find(_.name == projectionName)

  def findWorker(workerKey: String): Option[Worker] =
    projections.flatMap(_.workers).find(_.key == workerKey)
}

object State {

  type WorkerKey = String
  private[lagom] def fromReplicatedData(
      nameIndex: Map[WorkerKey, WorkerCoordinates],
      requestedStatusLocalCopy: Map[WorkerKey, Status],
      observedStatusLocalCopy: Map[WorkerKey, Status]
  )(defaultRequested: Status, defaultObserved: Status = Stopped): State = {

    val workers: Map[String, Seq[Worker]] = nameIndex
      .map {
        case (workerKey, coordinates) =>
          val w = Worker(
            coordinates.tagName,
            coordinates.asKey,
            requestedStatusLocalCopy.getOrElse(workerKey, defaultRequested),
            observedStatusLocalCopy.getOrElse(workerKey, defaultObserved)
          )
          w -> coordinates.projectionName
      }
      .groupBy(_._2)
      .mapValues(_.keys.toSeq)
      .toMap

    val projections = workers.map {
      case (projectionName, ws) => Projection(projectionName, ws)
    }.toSeq
    State(projections)
  }

}

@ApiMayChange
case class ProjectionNotFound(projectionName: String)
    extends RuntimeException(s"Projection $projectionName is not registered")
    with NoStackTrace
@ApiMayChange
case class ProjectionWorkerNotFound(workerName: String)
    extends RuntimeException(s"Projection $workerName is not registered")
    with NoStackTrace

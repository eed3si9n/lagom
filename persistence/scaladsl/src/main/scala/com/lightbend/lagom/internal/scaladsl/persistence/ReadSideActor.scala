/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package com.lightbend.lagom.internal.scaladsl.persistence

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Status
import akka.persistence.query.Offset
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.RestartSource
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.KillSwitch
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.util.Timeout
import akka.Done
import akka.NotUsed
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.internal.persistence.ReadSideConfig
import com.lightbend.lagom.internal.persistence.cluster.ClusterStartupTask
import com.lightbend.lagom.scaladsl.persistence._

import scala.concurrent.Future

private[lagom] object ReadSideActor {

  def props[Event <: AggregateEvent[Event]](
      tagName: String,
      config: ReadSideConfig,
      clazz: Class[Event],
      globalPrepareTask: ClusterStartupTask,
      eventStreamFactory: (AggregateEventTag[Event], Offset) => Source[EventStreamElement[Event], NotUsed],
      processor: () => ReadSideProcessor[Event]
  )(implicit mat: Materializer) =
    Props(
      new ReadSideActor[Event](
        tagName,
        config,
        clazz,
        globalPrepareTask,
        eventStreamFactory,
        processor
      )
    )

  case object Prepare
  case object Start

}

/**
 * Read side actor
 */
private[lagom] class ReadSideActor[Event <: AggregateEvent[Event]](
    tagName: String,
    config: ReadSideConfig,
    clazz: Class[Event],
    globalPrepareTask: ClusterStartupTask,
    eventStreamFactory: (AggregateEventTag[Event], Offset) => Source[EventStreamElement[Event], NotUsed],
    processor: () => ReadSideProcessor[Event]
)(implicit mat: Materializer)
    extends Actor
    with ActorLogging {

  import ReadSideActor._
  import akka.pattern.pipe
  import context.dispatcher

  /** Switch used to terminate the on-going stream when this actor is stopped.*/
  private var shutdown: Option[KillSwitch] = None

  override def postStop: Unit = {
    shutdown.foreach(_.shutdown())
  }

  override def preStart(): Unit = {
    super.preStart()
    implicit val timeout: Timeout = Timeout(config.globalPrepareTimeout)
    globalPrepareTask
      .askExecute()
      .map { _ =>
        Start
      }
      .pipeTo(self)
  }

  def receive: Receive = {
    case Start =>
      val tag = new AggregateEventTag(clazz, tagName)
      val backOffSource: Source[Done, NotUsed] =
        RestartSource.withBackoff(
          config.minBackoff,
          config.maxBackoff,
          config.randomBackoffFactor
        ) { () =>
          val handler: ReadSideProcessor.ReadSideHandler[Event] = processor().buildHandler()
          val futureOffset: Future[Offset]                      = handler.prepare(tag)

          Source
            .fromFuture(futureOffset)
            .initialTimeout(config.offsetTimeout)
            .flatMapConcat { offset =>
              val eventStreamSource = eventStreamFactory(tag, offset)
              val usersFlow         = handler.handle()
              eventStreamSource.via(usersFlow)
            }

        }

      val (killSwitch, streamDone) = backOffSource
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
        .run()

      shutdown = Some(killSwitch)
      streamDone.pipeTo(self)

    case Done =>
      // This `Done` is materialization of the `Sink.ignore` above.
      throw new IllegalStateException("Stream terminated when it shouldn't")

    case Status.Failure(cause) =>
      // Crash if the globalPrepareTask or the event stream fail
      // This actor will be restarted by WorkerHolderActor
      throw cause

  }

}

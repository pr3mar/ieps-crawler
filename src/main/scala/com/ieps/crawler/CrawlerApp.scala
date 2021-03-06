package com.ieps.crawler

import akka.actor.CoordinatedShutdown.JvmExitReason
import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown, Kill, PoisonPill}
import com.ieps.crawler.actors.FrontierManagerActor
import com.ieps.crawler.actors.FrontierManagerActor.InitializeFrontier
import com.typesafe.scalalogging.StrictLogging
import org.joda.time.{DateTime, DateTimeZone, Minutes}
import slick.jdbc.PostgresProfile.api.Database
import sun.misc.{Signal, SignalHandler}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}
import scala.language.postfixOps

object CrawlerApp extends App with StrictLogging {

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  val actorSystem: ActorSystem = ActorSystem("ieps-crawler")
  val dbConnection = Database.forConfig("local")
  val timeStart = DateTime.now(DateTimeZone.UTC)

  val frontierManager: ActorRef = actorSystem.actorOf(
    FrontierManagerActor.props(dbConnection).withDispatcher("thread-pool-dispatcher")
  )
  frontierManager ! InitializeFrontier(List(
    "http://www.evem.gov.si/",
    "https://e-uprava.gov.si/",
    "https://podatki.gov.si/",
    "http://www.e-prostor.gov.si/",
    "http://www.evode.gov.si/",
    "http://www.fu.gov.si/",
    "http://www.mo.gov.si/",
    "http://www.arso.gov.si/",
    "http://www.mirs.gov.si/"
  ))

  Signal.handle(new Signal("INT"), new SignalHandler() {
    def handle(sig: Signal) {
      logger.info(s"Shutting down. Time spent working: ${Minutes.minutesBetween(timeStart, DateTime.now(DateTimeZone.UTC)).getMinutes} minutes")
      frontierManager ! PoisonPill
      dbConnection.close()
      Await.result(CoordinatedShutdown(actorSystem).run(JvmExitReason), 30 seconds)
    }
  })
}

package org.tronscan.cache

import akka.actor.Actor
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import javax.inject.Inject
import org.tronscan.actions.{RepresentativeListReader, StatsOverview, VoteList}
import play.api.Logger
import play.api.cache.NamedCache
import play.api.cache.redis.CacheAsyncApi

import scala.concurrent.duration._

class CacheWarmer @Inject() (
  @NamedCache("redis") redisCache: CacheAsyncApi,
  representativeListReader: RepresentativeListReader,
  statsOverview: StatsOverview,
  voteList: VoteList) extends Actor {

  val decider: Supervision.Decider = {
    case exc =>
      Logger.error("CACHE WARMER ERROR", exc)
      Supervision.Resume
  }

  implicit val materializer = ActorMaterializer(
    ActorMaterializerSettings(context.system)
      .withSupervisionStrategy(decider))(context)

  import context.dispatcher

  def startWitnessReader() = {
    Source.tick(1.second, 15.seconds, "refresh")
      .mapAsyncUnordered(1)(_ => representativeListReader.execute)
      .runWith(writeToKey("witness.list", 1.minute))
  }

  def startVoteListWarmer() = {
    Source.tick(3.second, 13.seconds, "refresh")
      .mapAsyncUnordered(1)(_ => voteList.execute)
      .runWith(writeToKey("votes.candidates_total", 1.minute))
  }

  def startStatsOverview() = {
    Source.tick(0.second, 30.minutes, "refresh")
      .mapAsyncUnordered(1)(_ => statsOverview.execute)
      .runWith(writeToKey("stats.overview", 1.hour))
  }

  def writeToKey[T](name: String, duration: Duration = Duration.Inf) = {
    Sink.foreachParallel[T](1)(x => redisCache.set(name, x, duration))
  }

  override def preStart(): Unit = {
    startWitnessReader()
    startVoteListWarmer()
    startStatsOverview()
  }

  def receive = {
    case x =>
  }
}

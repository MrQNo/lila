package lila.core

import akka.actor.*
import com.softwaremill.macwire.*
import com.typesafe.config.Config
import play.api.Configuration

object hub:

  import lila.common.Bus

  final class RemoteService(channel: String):
    def register(f: PartialFunction[Matchable, Unit]): Unit = Bus.subscribeFun(channel)(f)
    def ask[A](using Executor, Scheduler)                   = Bus.ask[A](channel)

  val renderer = RemoteService("rs-renderer")
  val captcher = RemoteService("rs-captcher")

object actors:
  trait Actor:
    val actor: ActorSelection
    val ! = actor.!
  final class GameSearch(val actor: ActorSelection) extends Actor
  final class Fishnet(val actor: ActorSelection)    extends Actor
  final class Bookmark(val actor: ActorSelection)   extends Actor
  final class Shutup(val actor: ActorSelection)     extends Actor
  final class Timeline(val actor: ActorSelection)   extends Actor
  final class Report(val actor: ActorSelection)     extends Actor

@Module
final class Env(
    appConfig: Configuration,
    system: ActorSystem
):

  import actors.*

  private val config = appConfig.get[Config]("hub")

  val gameSearch = GameSearch(select("actor.game.search"))
  val fishnet    = Fishnet(select("actor.fishnet"))
  val timeline   = Timeline(select("actor.timeline.user"))
  val bookmark   = Bookmark(select("actor.bookmark"))
  val shutup     = Shutup(select("actor.shutup"))

  private def select(name: String) =
    system.actorSelection("/user/" + config.getString(name))
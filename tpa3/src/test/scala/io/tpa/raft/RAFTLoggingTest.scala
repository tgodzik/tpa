package io.tpa.raft

import akka.actor.{ActorRef, ActorSystem, PoisonPill}
import akka.routing.BroadcastGroup
import akka.testkit.{ImplicitSender, TestKit}
import io.tpa.raft.actor.ServerStateActor
import io.tpa.raft.model.{FullLog, GetFullLog, Log, LogAck}
import org.scalatest.{FlatSpecLike, Matchers}
import scala.concurrent.duration._

class RAFTLoggingTest
  extends TestKit(ActorSystem("RAFTLoggingTest"))
    with ImplicitSender
    with FlatSpecLike
    with Matchers {

  behavior of classOf[ServerStateActor].getSimpleName

  def retryMessageUntilAck(ref: ActorRef, msg: Any, response: Any) = {
    awaitAssert({
      ref ! msg
      expectMsg(100.millis, response)
    },
      3.seconds,
      1.second
    )
  }

  def startActors(maxActors: Int): (ActorRef, Seq[ActorRef]) = {
    val names = (0 until maxActors).map(name => s"a$name")
    val paths = names.map {
      name =>
        (system / name).toString
    }
    val router = system.actorOf(BroadcastGroup(paths).props(), "router")
    val routees = names.map(name => system.actorOf(ServerStateActor.props(maxActors, router), name))
    (router, routees)
  }

  it should "log messages and respond" in {
    val (all, _) = startActors(5)
    retryMessageUntilAck(all, Log("first", 1l), LogAck(1l))
    expectNoMsg()
  }

  it should "recover from leader failure" in {

    val (all, _) = startActors(5)
    retryMessageUntilAck(all, Log("first", 1l), LogAck(1l))
    val leader = lastSender
    expectNoMsg()

    leader ! PoisonPill

    retryMessageUntilAck(all, Log("second", 2l), LogAck(2l))
    expectNoMsg()

    retryMessageUntilAck(all,GetFullLog, FullLog(Seq("first", "second")))
    expectNoMsg()
  }

  it should "recover from leader failure after maximum two simulatanious failures" in {

    val (all, routees) = startActors(5)
    retryMessageUntilAck(all, Log("first", 1l), LogAck(1l))

    val leader = lastSender
    val other = routees.find(ref => ref != leader).get
    expectNoMsg()

    other ! PoisonPill
    leader ! PoisonPill

    retryMessageUntilAck(all, Log("second", 2l), LogAck(2l))
    expectNoMsg()

    retryMessageUntilAck(all,GetFullLog, FullLog(Seq("first", "second")))
    expectNoMsg()
  }

  it should "not recover from leader failure after more than two simulatanious failures" in {
    val (all, routees) = startActors(5)
    retryMessageUntilAck(all, Log("first", 1l), LogAck(1l))
    val leader = lastSender
    val other = routees.find(ref => ref != leader).get
    val last = routees.find(ref => ref != leader && ref != other).get
    expectNoMsg()

    other ! PoisonPill
    last ! PoisonPill
    leader ! PoisonPill

    all ! Log("second", 2l)
    expectNoMsg()

    all ! GetFullLog
    expectNoMsg()
  }
}

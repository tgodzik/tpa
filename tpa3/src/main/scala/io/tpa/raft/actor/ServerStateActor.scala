package io.tpa.raft.actor

import akka.actor.{Actor, ActorRef, FSM, Props}
import io.tpa.raft.model._

import scala.concurrent.duration._

/**
  * FSM actor representing a server participating in a cluster. It's task is just simple log collection.
  * Treat the code already written as a suggestion, but a couple of hints:
  * - actor should handle all RPC messages and client requests (and acknowledge them)
  * - use timeout on state to simulate electionTimeout
  * - use in transition to act on certain conditions
  * - to change state : "goto(Follower) using newStateData"
  * - to remain in state use "stay"
  * All needed info is in http://doc.akka.io/docs/akka/snapshot/scala/fsm.html
  */
class ServerStateActor(maxNumberOfServers: Int, allServers : ActorRef) extends Actor with FSM[ServerState, ServerData] {

  /**
    * Should be random, not a consistent duration.
    */
  val electionTimeout = 5.seconds

  startWith(Follower, FollowerData.empty)

  when(Follower, stateTimeout = electionTimeout) {
    case Event(StateTimeout, stateData) => ???
    case Event(message, stateData) => ???
  }

  when(Candidate, stateTimeout = electionTimeout) {
    case Event(StateTimeout, stateData) => ???
    case Event(message, stateData) => ???
  }

  when(Leader) {
    case Event(message, stateData) => ???
  }

  onTransition {
    case Follower -> Candidate =>
      ???
  }

  initialize()

}

object ServerStateActor{

  def props(maxNumberOfServers: Int, allServers : ActorRef) = Props(classOf[ServerStateActor], maxNumberOfServers, allServers)

}
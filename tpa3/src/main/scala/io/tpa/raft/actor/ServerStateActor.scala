package io.tpa.raft.actor

import akka.actor.{Actor, ActorRef, FSM, Props}
import io.tpa.raft.model._

import scala.concurrent.duration._
import scala.util.Random

trait RaftUtilities {

  def appendLog(log: Seq[LogEntry], newEntries: Seq[LogEntry], prevLogIndex: Int): Seq[LogEntry] = {
    val replaced = log
      .slice(prevLogIndex + 1, log.size)
      .zip(newEntries)
      .foldLeft((Seq.empty[LogEntry], false)) {
        case ((acc, replace), (e1, e2)) =>
          if (replace) {
            (acc :+ e2, replace)
          } else if (e1.term != e2.term) {
            (acc :+ e2, true)
          } else {
            (acc :+ e1, false)
          }
      }._1

    log.slice(0, prevLogIndex + 1) ++
      replaced ++
      newEntries.slice(replaced.size, newEntries.size)
  }

  def commitIndex(leaderCommit: Int, commitIndex: Int, newLogSize: Int) = {
    if (leaderCommit > commitIndex) {
      Math.min(leaderCommit, newLogSize - 1)
    } else {
      commitIndex
    }
  }
}

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
class ServerStateActor(maxNumberOfServers: Int, allServers: ActorRef) extends Actor with FSM[ServerState, StateData] with RaftUtilities {

  val random = new Random

  /**
    * Should be random, not a consistent duration.
    */
  def electionTimeout = (150 + random.nextInt(150)).millis

  startWith(Follower, FollowerStateData.empty)

  /**
    * FOLLOWER
    */
  when(Follower, stateTimeout = electionTimeout) {

    // election timeout elapsed
    case Event(StateTimeout, followerData: FollowerStateData) =>
      goto(Candidate) using followerData.toCandidate.nextTerm.votedFor(self).oneVote

    // heartbeat from leader
    case Event(appendRequest: AppendEntries, followerData: FollowerStateData)
      if appendRequest.entries.isEmpty =>

      stay() using followerData.withTerm(appendRequest.term).withNoVotes.withLeader(sender())

    // wrong term
    case Event(appendRequest: AppendEntries, followerData: FollowerStateData)
      if followerData.currentTerm > appendRequest.term =>

      sender() ! AppendEntriesResponse(followerData.currentTerm, success = false)
      stay()

    // inconsistent log, need to redo
    case Event(appendRequest: AppendEntries, followerData: FollowerStateData)
      if followerData.log(appendRequest.prevLogIndex).term != appendRequest.prevLogTerm =>

      sender() ! AppendEntriesResponse(followerData.currentTerm, success = false)
      stay()

    // just apply data
    case Event(appendRequest: AppendEntries, followerData: FollowerStateData) =>

      val newLog = appendLog(followerData.log, appendRequest.entries, appendRequest.prevLogIndex)
      val newCommitIndex = commitIndex(appendRequest.leaderCommit, followerData.commitIndex, newLog.size)
      val newApplied = if (newCommitIndex > followerData.lastApplied) {
        newCommitIndex
      } else {
        followerData.lastApplied
      }
      sender() ! AppendEntriesResponse(followerData.currentTerm, success = true)
      stay() using followerData.updateLog(newLog).commit(newCommitIndex).applyIndex(newApplied).withTerm(appendRequest.term)

    // wrong term
    case Event(request: RequestVote, followerData: FollowerStateData)
      if followerData.currentTerm > request.term =>

      sender() ! RequestVoteResponse(followerData.currentTerm, voteGranted = false)
      stay()

    // grant vote
    case Event(request: RequestVote, followerData: FollowerStateData) if
    (followerData.votedFor.isEmpty || followerData.votedFor.contains(request.candidateId)) &&
      request.lastLogIndex >= followerData.lastApplied =>

      sender() ! RequestVoteResponse(followerData.currentTerm, voteGranted = true)
      stay() using followerData.withTerm(request.term)

    // forward any message from client to leader
    case Event(message: ClientMessage, stateData: FollowerStateData) =>
      stateData.leader.foreach(leader => leader forward message)
      stay()
  }

  /**
    * CANDIDATE
    */
  when(Candidate, stateTimeout = electionTimeout) {
    case Event(StateTimeout, stateData: CandidateStateData) =>
      goto(Candidate) using stateData.nextTerm.votedFor(self).oneVote

    // heartbeat from leader
    case Event(appendRequest: AppendEntries, candidateData: CandidateStateData)
      if appendRequest.entries.isEmpty =>

      goto(Follower) using candidateData.toFollower.withTerm(appendRequest.term).withNoVotes.withLeader(sender())

    // old term, revert to follower
    case Event(RequestVoteResponse(term, voteGranted), candidateData: CandidateStateData)
      if term > candidateData.currentTerm =>
      goto(Follower) using candidateData.toFollower.withNoVotes

    // increase vote, become leader?
    case Event(RequestVoteResponse(term, voteGranted), candidateData: CandidateStateData)
      if voteGranted =>
      ???

  }

  when(Leader) {
    case Event(message, stateData) => ???
  }

  onTransition {
    // request votes
    case _ -> Candidate =>
      stateData match {
        case data: FollowerStateData =>
          allServers ! RequestVote(data.currentTerm, self, data.log.size - 1, data.log.last.term)
      }

    // send heartbeat
    case _ -> Leader =>

  }

  initialize()

}

object ServerStateActor {

  def props(maxNumberOfServers: Int, allServers: ActorRef) = Props(classOf[ServerStateActor], maxNumberOfServers, allServers)

}
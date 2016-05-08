package io.tpa.raft.actor

import akka.actor.{Actor, ActorLogging, ActorRef, FSM, Props}
import io.tpa.raft.model._

import scala.concurrent.duration._
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global

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

  def updateCommitIndex(leaderCommit: Int, commitIndex: Int, newLogSize: Int) = {
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
class ServerStateActor(maxNumberOfServers: Int, allServers: ActorRef) extends Actor with FSM[ServerState, StateData] with RaftUtilities  with ActorLogging{

  val random = new Random

  case object Heartbeat

  /**
    * Should be random, not a consistent duration.
    */
  def electionTimeout = (150 + random.nextInt(150)).millis

  startWith(Follower, FollowerStateData.empty, Some(electionTimeout))

  /**
    * FOLLOWER
    */
  when(Follower) {

    // election timeout elapsed
    case Event(StateTimeout, followerData: FollowerStateData) =>
      allServers ! RequestVote(stateData.currentTerm, self, stateData.stateLog.size - 1, stateData.stateLog.last.term)
      goto(Candidate) using followerData.toCandidate(self)

    // wrong term
    case Event(appendRequest: AppendEntries, followerData: FollowerStateData)
      if followerData.currentTerm > appendRequest.term =>

      sender() ! AppendEntriesResponse(followerData.currentTerm, success = false)
      stay() using followerData.withTerm(appendRequest.term)

    // inconsistent log, need to redo
    case Event(appendRequest: AppendEntries, followerData: FollowerStateData)
      if followerData.stateLog.size < appendRequest.prevLogIndex - 1 ||
        followerData.stateLog(appendRequest.prevLogIndex).term != appendRequest.prevLogTerm =>

      sender() ! AppendEntriesResponse(followerData.currentTerm, success = false)
      stay()

    // heartbeat from leader
    case Event(appendRequest: AppendEntries, followerData: FollowerStateData)
      if appendRequest.entries.isEmpty =>
      stay() using followerData.withNewLeader(sender(), appendRequest.term)

    // just apply data
    case Event(appendRequest: AppendEntries, followerData: FollowerStateData) =>

      val updatedStateLog = appendLog(followerData.stateLog, appendRequest.entries, appendRequest.prevLogIndex)
      val updatedCommitIndex = updateCommitIndex(appendRequest.leaderCommit, followerData.commitIndex, updatedStateLog.size)
      val updatedApplied = if (updatedCommitIndex > (followerData.lastApplied + 1)) {
        updatedCommitIndex
      } else {
        followerData.lastApplied + 1
      }
      sender() ! AppendEntriesResponse(followerData.currentTerm, success = true, Some(updatedApplied))
      stay() using followerData.withNewLogEntries(updatedStateLog, updatedCommitIndex, updatedApplied)

    // grant vote
    case Event(request: RequestVote, followerData: FollowerStateData) if
    (followerData.votedFor.isEmpty || followerData.votedFor.contains(request.candidateId)) &&
      request.lastLogIndex >= followerData.lastApplied => {
      log.info("Received call for vote from " + sender().path)
      sender() ! RequestVoteResponse(followerData.currentTerm, voteGranted = true)
      stay()
    }
    // forward any message from client to leader
    case Event(message: ClientMessage, stateData: FollowerStateData) =>
      stateData.leader.foreach(leader => leader forward message)
      stay()
  }

  /**
    * CANDIDATE
    */
  when(Candidate) {
    case Event(StateTimeout, stateData: CandidateStateData) =>
      allServers ! RequestVote(stateData.currentTerm, self, stateData.stateLog.size - 1, stateData.stateLog.last.term)
      goto(Candidate) using stateData.toCandidate(self)

    // old term, revert to follower
    case Event(message: RaftMessage, candidateData: CandidateStateData)
      if message.term > candidateData.currentTerm =>
      goto(Follower) using candidateData.toFollower(message.term)

    // heartbeat from leader
    case Event(appendRequest: AppendEntries, candidateData: CandidateStateData) =>
      self forward appendRequest
      goto(Follower) using candidateData.toFollower(appendRequest.term).withLeader(sender())

    // increase vote, become leader?
    case Event(RequestVoteResponse(term, voteGranted), candidateData: CandidateStateData)
      if voteGranted && ((candidateData.votesReceived + 1) >= (maxNumberOfServers / 2 + 1)) =>
      log.info("Becoming leader.")
      val schedule = context.system.scheduler.schedule(50.millis, 50.millis, self, Heartbeat)
      goto(Leader) using candidateData.toLeader(schedule)

    case Event(RequestVoteResponse(term, true), candidateData: CandidateStateData) =>
      stay() using candidateData.withOneMoreVote

    case Event(request: RequestVote, _) =>
      sender() ! RequestVoteResponse(request.term, voteGranted = false)
      stay()

  }

  /**
    * LEADER
    */
  when(Leader) {
    case Event(message: RaftMessage, leaderData: LeaderStateData)
      if message.term > leaderData.currentTerm =>

      val updatedStateData = leaderData.toFollower(message.term)
      goto(Follower) using updatedStateData

    // send heartbeat to all followers
    case Event(Heartbeat, leaderStateData: LeaderStateData) =>
      val stateLog = leaderStateData.stateLog
      val currentTerm = leaderStateData.currentTerm
      val commitIndex = leaderStateData.commitIndex
      val appendEntries = AppendEntries(currentTerm, self, stateLog.size - 1, stateLog.last.term, Seq.empty, commitIndex)
      allServers ! appendEntries
      stay()

    case Event(AppendEntriesResponse(term, false, _), leaderData: LeaderStateData)
      if term < leaderData.currentTerm =>
      val nextIndex = leaderData.nextIndex
      val stateLog = leaderData.stateLog
      if (nextIndex.contains(sender)) {
        val index = nextIndex(sender)
        val msg = leaderData.messageForIndex(index)
        sender() ! msg
        stay()
      } else {
        val msg = leaderData.messageForIndex(stateLog.size)
        sender() ! msg
        stay() using leaderData.withNewDefaultNextIndex(sender())
      }

    case Event(AppendEntriesResponse(term, false, _), leaderData: LeaderStateData) =>
      val nextIndex = leaderData.nextIndex
      val stateLog = leaderData.stateLog
      if (nextIndex.contains(sender)) {
        val index = nextIndex(sender) - 1
        val msg = leaderData.messageForIndex(index)
        sender() ! msg
        stay() using leaderData.withPreviousNextIndex(sender())
      } else {
        val msg = leaderData.messageForIndex(stateLog.size - 1)
        sender() ! msg
        stay() using leaderData.withNewNextIndex(sender(), stateLog.size - 1)
      }

    case Event(AppendEntriesResponse(term, true, Some(followerApplied)), leaderData: LeaderStateData) =>

      val waitingList = leaderData.waitingList
      val nextIndex = leaderData.nextIndex
      val matchIndex = leaderData.matchIndex
      val updatedNextIndex = nextIndex + (sender() -> (followerApplied + 1))
      val updatedMatchIndex = matchIndex + (sender() -> followerApplied)

      val appliedInFollowers = updatedMatchIndex.count {
        case (_, ind) =>
          ind >= followerApplied
      }

      val updatedCommitIndex =
        if (appliedInFollowers >= maxNumberOfServers / 2) {
          followerApplied
        } else {
          leaderData.commitIndex
        }

      val (respondTo, updatedWaitingList) = waitingList.partition(item => item.index <= updatedCommitIndex)

      respondTo.foreach {
        item =>
          item.client ! LogAck(item.seq)
      }

      val updatedLeaderStateData = leaderData.copy(
        nextIndex = updatedNextIndex,
        matchIndex = updatedMatchIndex,
        commitIndex = updatedCommitIndex,
        waitingList = updatedWaitingList
      )

      stay() using updatedLeaderStateData

    case Event(GetFullLog, leaderData: LeaderStateData) =>
      sender() ! FullLog(leaderData.stateLog.slice(1, leaderData.commitIndex + 1).map(_.message))
      stay()

    case Event(Log(message, seq), leaderStateData: LeaderStateData) =>
      import leaderStateData._

      val newLogEntry = LogEntry(message, currentTerm)
      val updatedLog = stateLog :+ LogEntry(message, currentTerm)
      val updatedApplied = lastApplied + 1
      val updatedWaitingList = waitingList :+ WaitingItem(updatedApplied, seq, sender())

      val updatedStateData = leaderStateData.copy(
        stateLog = updatedLog,
        lastApplied = updatedApplied,
        waitingList = updatedWaitingList
      )

      val appendEntries = AppendEntries(currentTerm, self, stateLog.size - 1, stateLog.last.term, Seq(newLogEntry), commitIndex)
      allServers ! appendEntries

      stay() using updatedStateData

    case Event(ev : AppendEntries, leaderStateData: LeaderStateData) =>
      stay()
  }

  onTransition {
    // request votes
    case _ -> Candidate =>
      setStateTimeout(Candidate, timeout = Some(electionTimeout))

    case _ -> Follower =>
      setStateTimeout(Follower, timeout = Some(electionTimeout))

    case Leader -> _ =>
      stateData match {
        case data: LeaderStateData =>
          data.schedule.cancel()
        case _ =>
      }
  }

  initialize()

}

object ServerStateActor {

  def props(maxNumberOfServers: Int, allServers: ActorRef) = Props(classOf[ServerStateActor], maxNumberOfServers, allServers)

}
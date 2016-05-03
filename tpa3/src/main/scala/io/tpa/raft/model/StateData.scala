package io.tpa.raft.model

import akka.actor.ActorRef

/**
  * Log entry for each server.
 *
  * @param message command for state machine (does not have to be string)
  * @param term term in which this entry was recorded
  */
case class LogEntry(message : String, term : Term)

sealed trait ServerState

case object Candidate extends ServerState
case object Follower extends ServerState
case object Leader extends ServerState

sealed abstract class StateData {

  def currentTerm: Term

  def votedFor: Option[ActorRef]

  def log: Seq[LogEntry]

  def nextTerm : StateData

  def votedFor(votedRef : ActorRef) : StateData

}


/**
  * Following state for RAFT
 *
  * @param currentTerm latest term server has seen (initialized to 0 on first boot, increases monotonically)
  * @param votedFor candidateId that received vote in current term
  * @param log log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1)
  */
case class FollowerStateData(
  currentTerm: Term,
  votedFor: Option[ActorRef],
  log: Seq[LogEntry],
  commitIndex : Int,
  lastApplied : Int,
  leader : Option[ActorRef]
) extends StateData {

  def nextTerm = this.copy(currentTerm = currentTerm.next())

  def votedFor(votedRef : ActorRef) = this.copy(votedFor = Some(votedRef))

  def withNoVotes = this.copy(votedFor = None)

  def toCandidate = CandidateStateData(currentTerm, votedFor, log, 0, commitIndex, lastApplied)

  def updateLog(newLog : Seq[LogEntry]) = this.copy(log = newLog)

  def commit(newCommitIndex : Int) = this.copy(commitIndex = newCommitIndex)

  def applyIndex(newApplyIndex : Int) = this.copy(lastApplied = newApplyIndex)

  def withTerm(newTerm : Term) = this.copy(currentTerm = newTerm)

  def withLeader(leader : ActorRef) = this.copy(leader = Some(leader))
}

object FollowerStateData {
  def empty = FollowerStateData(Term(), None, Seq(LogEntry("ServerStarted", Term())), 0, 0, None)
}

/**
  * Following state for RAFT
  *
  * @param currentTerm latest term server has seen (initialized to 0 on first boot, increases monotonically)
  * @param votedFor candidateId that received vote in current term
  * @param log log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1)
  */
case class CandidateStateData(
                              currentTerm: Term,
                              votedFor: Option[ActorRef],
                              log: Seq[LogEntry],
                              votesReceived : Int,
                              commitIndex : Int,
                              lastApplied : Int
                            ) extends StateData {

  def nextTerm = this.copy(currentTerm = currentTerm.next())

  def votedFor(votedRef : ActorRef) = this.copy(votedFor = Some(votedRef))

  def oneVote = this.copy(votesReceived = 1)

  def oneMoreVote = this.copy(votesReceived = this.votesReceived + 1)

  def toFollower = FollowerStateData(currentTerm, None, log, commitIndex, lastApplied, None)
}

/**
  * Leader state for RAFT
 *
  * @param currentTerm latest term server has seen (initialized to 0 on first boot, increases monotonically)
  * @param votedFor candidateId that received vote in current term
  * @param log log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1)
  * @param nextIndex for each server, index of the next log entry to send to that server (initialized to leader last log index + 1)
  * @param matchIndex  for each server, index of highest log entry known to be replicated on server (initialized to 0, increases monotonically)
  */
case class LeaderStateData(
   currentTerm: Term,
   votedFor: Option[ActorRef],
   log: Seq[LogEntry],
   nextIndex : Map[ActorRef, Long],
   matchIndex : Map[ActorRef, Long],
   commitIndex : Int = 0,
   lastApplied : Int = 0
                     ) extends StateData{

  def nextTerm = this.copy(currentTerm = currentTerm.next())

  def votedFor(votedRef : ActorRef) = this.copy(votedFor = Some(votedRef))

}
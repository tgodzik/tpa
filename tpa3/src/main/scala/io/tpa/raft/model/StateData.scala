package io.tpa.raft.model

import akka.actor.{ActorRef, Cancellable}

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

  def stateLog: Seq[LogEntry]

}


/**
  * Following state for RAFT
 *
  * @param currentTerm latest term server has seen (initialized to 0 on first boot, increases monotonically)
  * @param votedFor candidateId that received vote in current term
  * @param stateLog log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1)
  */
case class FollowerStateData(
                              currentTerm: Term,
                              votedFor: Option[ActorRef],
                              stateLog: Seq[LogEntry],
                              commitIndex : Int,
                              lastApplied : Int,
                              leader : Option[ActorRef]
) extends StateData {

  def withNewLeader(newLeader : ActorRef, term : Term) = {
    this.copy(currentTerm = term, votedFor = None, leader = Some(newLeader))
  }

  def withNewLogEntries(
    updatedStateLog : Seq[LogEntry],
    updatedCommitIndex : Int,
    updatedLastApplied : Int) = {
    this.copy(stateLog = updatedStateLog, commitIndex = updatedCommitIndex, lastApplied = updatedLastApplied)
  }

  def toCandidate(self: ActorRef) = {
    CandidateStateData(currentTerm.next(), Some(self), stateLog, 1, commitIndex, lastApplied)
  }

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
  * @param stateLog log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1)
  */
case class CandidateStateData(
                               currentTerm: Term,
                               votedFor: Option[ActorRef],
                               stateLog: Seq[LogEntry],
                               votesReceived : Int,
                               commitIndex : Int,
                               lastApplied : Int
                            ) extends StateData {

  def withOneMoreVote = this.copy(votesReceived = this.votesReceived + 1)

  def toCandidate(self: ActorRef) = {
    CandidateStateData(currentTerm.next(), Some(self), stateLog, 1, commitIndex, lastApplied)
  }

  def toFollower(term : Term) = {

    FollowerStateData(term, None, stateLog, commitIndex, lastApplied, None)
  }

  def toLeader(schedule : Cancellable) = LeaderStateData(
    currentTerm,
    votedFor,
    stateLog,
    Map.empty,
    Map.empty,
    schedule,
    commitIndex,
    lastApplied,
    Seq.empty
  )
}

/**
  * Leader state for RAFT
 *
  * @param currentTerm latest term server has seen (initialized to 0 on first boot, increases monotonically)
  * @param votedFor candidateId that received vote in current term
  * @param stateLog log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1)
  * @param nextIndex for each server, index of the next log entry to send to that server (initialized to leader last log index + 1)
  * @param matchIndex  for each server, index of highest log entry known to be replicated on server (initialized to 0, increases monotonically)
  */
case class LeaderStateData(
                            currentTerm: Term,
                            votedFor: Option[ActorRef],
                            stateLog: Seq[LogEntry],
                            nextIndex : Map[ActorRef, Int],
                            matchIndex : Map[ActorRef, Int],
                            schedule : Cancellable,
                            commitIndex : Int,
                            lastApplied : Int,
                            waitingList : Seq[WaitingItem]
 ) extends StateData{

  def toFollower(newTerm : Term) = FollowerStateData(newTerm, None, stateLog, commitIndex, lastApplied, None)

  def withNewDefaultNextIndex(actor : ActorRef) = {
    this.copy( nextIndex = nextIndex + (actor -> lastApplied), matchIndex = matchIndex + (actor -> commitIndex))
  }
  def withNewNextIndex(actor : ActorRef, index : Int) = this.copy( nextIndex = nextIndex + (actor -> index))

  def withPreviousNextIndex(actor : ActorRef) = this.copy( nextIndex = nextIndex + (actor -> (nextIndex(actor) - 1)))

  def withFollowingNextIndex (ref : ActorRef) = this.copy(nextIndex = nextIndex + (ref -> nextIndex(ref)))

  def messageForIndex(index : Int) =
    AppendEntries(currentTerm, votedFor.get, index - 1, stateLog(index - 1).term, stateLog.slice(index, stateLog.size), commitIndex)

}

case class WaitingItem(index : Int, seq : Long, client : ActorRef)
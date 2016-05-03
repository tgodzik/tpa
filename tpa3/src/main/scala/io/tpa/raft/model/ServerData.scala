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

sealed abstract class ServerData {

  def currentTerm: Term

  def votedFor: Option[ActorRef]

  def log: Seq[LogEntry]
}

/**
  * Candidate state for RAFT
 *
  * @param currentTerm latest term server has seen (initialized to 0 on first boot, increases monotonically)
  * @param votedFor candidateId that received vote in current term
  * @param log log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1)
  */
case class CandidateData(
  currentTerm: Term,
  votedFor: Option[ActorRef],
  log: Seq[LogEntry],
  commitIndex : Int,
  lastApplied : Int
) extends ServerData

/**
  * Following state for RAFT
 *
  * @param currentTerm latest term server has seen (initialized to 0 on first boot, increases monotonically)
  * @param votedFor candidateId that received vote in current term
  * @param log log entries; each entry contains command for state machine, and term when entry was received by leader (first index is 1)
  */
case class FollowerData(
  currentTerm: Term,
  votedFor: Option[ActorRef],
  log: Seq[LogEntry],
  commitIndex : Int,
  lastApplied : Int
) extends ServerData

object FollowerData {
  def empty = FollowerData(Term(), None, Seq.empty, 0, 0)
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
case class LeaderData(
   currentTerm: Term,
   votedFor: Option[ActorRef],
   log: Seq[LogEntry],
   nextIndex : Map[ActorRef, Long],
   matchIndex : Map[ActorRef, Long],
   commitIndex : Int,
   lastApplied : Int
) extends ServerData
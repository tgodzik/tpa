package io.tpa.raft.model

import akka.actor.ActorRef


sealed trait RaftMessage

/**
  * AppendEntries RPC message
  * @param term leader’s term
  * @param leaderId so follower can redirect clients
  * @param prevLogIndex index of log entry immediately preceding new ones
  * @param prevLogTerm term of prevLogIndex entry
  * @param entries log entries to store (empty for heartbeat; may send more than one for efficiency)
  * @param leaderCommit leader’s commitIndex
  */
case class AppendEntries(
  term: Term,
  leaderId: ActorRef,
  prevLogIndex: Long,
  prevLogTerm: Term,
  entries: Seq[String],
  leaderCommit: Long
) extends RaftMessage

/**
  * AppendEntries RPC response
  * @param term currentTerm, for leader to update itself
  * @param success true if follower contained entry matching prevLogIndex and prevLogTerm
  */
case class AppendEntriesResponse(
  term: Term,
  success: Boolean
) extends RaftMessage

/**
  * RequestVote RPC message
  * @param term candidate’s term
  * @param candidateId candidate requesting vote
  * @param lastLogIndex index of candidate’s last log entry
  * @param lastLogTerm term of candidate’s last log entry
  */
case class RequestVote(
  term: Term,
  candidateId: ActorRef,
  lastLogIndex: Long,
  lastLogTerm: Term
) extends RaftMessage

/**
  * RequestVote RPC response
  * @param term currentTerm, for candidate to update itself
  * @param voteGranted true means candidate received vote
  */
case class RequestVoteResponse(
  term: Term,
  voteGranted: Boolean
) extends  RaftMessage
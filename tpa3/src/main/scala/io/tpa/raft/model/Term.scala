package io.tpa.raft.model

/**
  * Terms are durations of arbitrary length based on leader election.
  * @param termId integer number representing term number
  */
case class Term(termId : Long = 0l) {

  def next() = Term(termId + 1)

}

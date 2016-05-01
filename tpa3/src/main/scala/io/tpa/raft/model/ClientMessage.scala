package io.tpa.raft.model

sealed trait ClientMessage

/**
  * Simple logging message. Server should commit it and respond to the client.
  * @param message log message to be saved
  * @param sequence sequence number representing this log message
  */
case class Log(message: String, sequence: Long) extends ClientMessage

/**
  * Message acknowledging client log message.
  * @param sequence sequence number representing this log message
  */
case class LogAck(sequence: Long) extends ClientMessage

/**
  * Get entire log from the server.
  */
case object GetFullLog extends ClientMessage

/**
  * Return the entire log from the server.
  * @param log full log
  */
case class FullLog(log: Seq[String]) extends ClientMessage

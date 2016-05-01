# tpa
_Towarzystwo Przyjaciół Algorytmów_

This project gives a basic template for creating and testing RAFT based log system.
This system's role is to create a log that can be distributed to other actors, but within those actors there should just be one leader. So whenever a group of actors is asked something by the client, this request should be redirected to leader,

The main implementation of the algorithm needs to be added to the ServerStateActor, which is based on Akka FSM.
All needed messages are already created. Comments in each message explain exactly what each one does.

Most of the needed information about raft can be found in this [document](https://www.usenix.org/system/files/conference/atc14/atc14-paper-ongaro.pdf).
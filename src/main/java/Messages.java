import akka.actor.ActorRef;
import java.util.List;

// this gives the full list of peer references
class SetPeersMessage {
    public final List<ActorRef> peers;
    public SetPeersMessage(List<ActorRef> peers) {
        this.peers = peers;
    }
}

// start the algorithm 
class LaunchMessage {}


// sent to f rand processes, processes are crashable with prob alpha
class CrashMessage {}

/* Sent by Main to every non-leader process after the leader-election timeout.
 * On receipt the process stops issuing new propose rounds (it keeps responding to others).
 */
class HoldMessage {}


class ReadMessage {
    public final int ballot;
    public ReadMessage(int ballot) { this.ballot = ballot; }
}

class GatherMessage {
    public final int ballot;
    public final int imposeballot;
    public final int estimate;       // Integer.MIN_VALUE encodes "nil"
    public GatherMessage(int ballot, int imposeballot, int estimate) {
        this.ballot       = ballot;
        this.imposeballot = imposeballot;
        this.estimate     = estimate;
    }
}

class AbortMessage {
    public final int ballot;
    public AbortMessage(int ballot) { this.ballot = ballot; }
}

class ImposeMessage {
    public final int ballot;
    public final int value;
    public ImposeMessage(int ballot, int value) {
        this.ballot = ballot;
        this.value  = value;
    }
}

class AckMessage {
    public final int ballot;
    public AckMessage(int ballot) { this.ballot = ballot; }
}

class DecideMessage {
    public final int value;
    public DecideMessage(int value) { this.value = value; }
}

// message for the collection of the results
class DecisionEvent {
    public final int  processId;
    public final int  value;
    public final long latencyMs;   // time from LaunchMessage to this decision
    public DecisionEvent(int processId, int value, long latencyMs) {
        this.processId = processId;
        this.value     = value;
        this.latencyMs = latencyMs;
    }
}

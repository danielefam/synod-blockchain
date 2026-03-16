import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import java.util.ArrayList;
import java.util.List;


public class Process extends AbstractActor {

    public static Props props(int id, int n, ActorRef collector, double alpha) {
        return Props.create(Process.class, () -> new Process(id, n, collector, alpha));
    }

    private static final int NIL = Integer.MIN_VALUE;
    private final int      id;
    private final int      n;
    private final ActorRef collector;
    private final double   alpha;
    private List<ActorRef> peers;

    private int ballot;
    private int proposal;
    private int readballot;
    private int imposeballot;
    private int estimate;

    private int[] statesEstBallot; //second index of states in pseudocode
    private int[] statesEst; // first index
    private int   gatherCount;
    private int   ackCount;

    // flags
    private boolean decided;
    private boolean crashed;
    private boolean faultProne;
    private boolean held;

    private long launchTimeNs;

    private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

    public Process(int id, int n, ActorRef collector, double alpha) {
        this.id = id;
        this.n = n;
        this.collector = collector; // we send it the results
        this.alpha = alpha;

        // lines 2-3
        ballot = id - n;
        proposal = NIL;
        readballot = 0;
        imposeballot = id - n;
        estimate = NIL;

        statesEstBallot = new int[n + 1];   // 1-indexed
        statesEst = new int[n + 1];
        resetStates();

        gatherCount = 0;
        ackCount = 0;
        decided = false;
        crashed = false;
        faultProne = false;
        held = false;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
            .match(SetPeersMessage.class, this::onSetPeers)
            .match(LaunchMessage.class,   this::onLaunch)
            .match(CrashMessage.class,    this::onCrash)
            .match(HoldMessage.class,     this::onHold)
            .match(ReadMessage.class,     this::onRead)
            .match(AbortMessage.class,    this::onAbort)
            .match(GatherMessage.class,   this::onGather)
            .match(ImposeMessage.class,   this::onImpose)
            .match(AckMessage.class,      this::onAck)
            .match(DecideMessage.class,   this::onDecide)
            .build();
    }

    // set references
    private void onSetPeers(SetPeersMessage msg) {
        peers = new ArrayList<>(msg.peers);
    }

    /**
     * LAUNCH — start the algorithm.
     * Pick a random proposal in {0,1} and begin the first propose round.
     */
    private void onLaunch(LaunchMessage msg) {
        if (maybeCrash()) return;

        launchTimeNs = System.nanoTime();
        proposal = (int) (Math.random() * 2);
        log.info("[P{}] LAUNCH  proposal={}", id, proposal);
        invokeProposeRound();
    }

    // enter fault prone mode
    private void onCrash(CrashMessage msg) {
        faultProne = true;
        log.info("[P{}] fault-prone mode enabled  alpha={}", id, alpha);
    }

    /**
     * HOLD — stop acting as a proposer (leader election).
     * The process continues to respond to READ/IMPOSE from the elected leader.
     */
    private void onHold(HoldMessage msg) {
        if (maybeCrash()) return;
        held = true;
        log.info("[P{}] HOLD received - no more propose rounds", id);
    }

    //lines 7-12
    private void onRead(ReadMessage msg) {
        if (crashed) return;
        if (maybeCrash()) return;

        int b = msg.ballot;
        if (readballot > b || imposeballot > b) // line 9: reject, we have seen a higher ballot
            getSender().tell(new AbortMessage(b), getSelf());
        else {
            // line 11-12: accept, adopt ballot and reply with our estimate
            readballot = b;
            getSender().tell(new GatherMessage(b, imposeballot, estimate), getSelf());
        }
    }

    // lines 13-14
    private void onAbort(AbortMessage msg) {
        if (crashed) return;
        if (maybeCrash()) return;
        if (msg.ballot != ballot) return; 

        log.info("[P{}] ABORT  ballot={}", id, msg.ballot);
        if (!decided && !held) {
            invokeProposeRound();
        }
    }

    // lines 15-22
    private void onGather(GatherMessage msg) {
        if (crashed) return;
        if (held) return;
        if (maybeCrash()) return;
        if (msg.ballot != ballot) return; 

        int pj = indexOf(getSender());
        if (pj == -1) return;

        // line 16: record sender's estimate info
        statesEstBallot[pj] = msg.imposeballot;
        statesEst[pj] = msg.estimate;
        gatherCount++;

        //line 17 upon received a majority of responses
        if (gatherCount > n / 2) {
            gatherCount = Integer.MIN_VALUE; // prevent re-trigger of the if

            // lines 18-20: adopt the value with the highest impose ballot, if any
            int highestEstBallot = 0;
            int chosenEst        = NIL;
            for (int k = 1; k <= n; k++) {
                if (statesEstBallot[k] > highestEstBallot) {
                    highestEstBallot = statesEstBallot[k];
                    chosenEst        = statesEst[k];
                }
            }
            if (highestEstBallot > 0 && chosenEst != NIL) {
                proposal = chosenEst;   // line 20
            }

            // line 21
            resetStates();
            ackCount = 0;

            // line 22: broadcast IMPOSE
            ImposeMessage impose = new ImposeMessage(ballot, proposal);
            for (ActorRef p : peers) {
                p.tell(impose, getSelf());
            }
        }
    }

    // lines 23-28
    private void onImpose(ImposeMessage msg) {
        if (crashed) return;
        if (maybeCrash()) return;

        int b = msg.ballot;
        int v = msg.value;
        if (readballot > b || imposeballot > b) {
            // line 25: reject
            getSender().tell(new AbortMessage(b), getSelf());
        } else {
            // lines 27-28: adopt and acknowledge
            estimate = v;
            imposeballot = b;
            getSender().tell(new AckMessage(b), getSelf());
        }
    }

    // lines 29-30
    private void onAck(AckMessage msg) {
        if (crashed) return;
        if (held) return;
        if (maybeCrash()) return;
        if (msg.ballot != ballot) return;

        ackCount++;
        if (ackCount > n / 2) {
            ackCount = Integer.MIN_VALUE;

            // line 30: broadcast DECIDE
            DecideMessage decide = new DecideMessage(proposal);
            for (ActorRef p : peers) {
                p.tell(decide, getSelf());
            }
        }
    }

    //lines 31-33
    private void onDecide(DecideMessage msg) {
        if (crashed) return;
        if (maybeCrash()) return;

        if (!decided) {
            decided = true;

            // line 32: gossip
            DecideMessage fwd = new DecideMessage(msg.value);
            for (ActorRef p : peers) {
                p.tell(fwd, getSelf());
            }

            long latencyUs = (System.nanoTime() - launchTimeNs)/1000;
            log.info("[P{}] DECIDED  value={}  latencyUs={}ms", id, msg.value, latencyUs);

            // report to the result collector
            collector.tell(new DecisionEvent(id, msg.value, latencyUs), getSelf());
        }
    }

    //---------------- utilities -------------------

    // line 3
    private void resetStates() {
        for (int k = 1; k <= n; k++) {
            statesEstBallot[k] = 0;
            statesEst[k]       = NIL;
        }
    }

    // map actor ref to its 1-based index in the refs list 
    // to avoid confusion with process name
    private int indexOf(ActorRef ref) {
        for (int k = 0; k < peers.size(); k++) {
            if (peers.get(k).equals(ref)) return k + 1;
        }
        return -1;
    }

    //possibly crash if in fault-prone mode with prob alpha
    private boolean maybeCrash() {
        if (faultProne && !crashed && Math.random() < alpha) {
            // Math.random() < alpha to simulate the bernoulli
            crashed = true;
            log.info("[P{}] CRASHED", id);
            return true;
        }
        return false;
    }

    // lines 5-6
    private void invokeProposeRound() {
        if (decided || crashed || held) return;

        ballot += n;        // line 5
        resetStates();
        gatherCount = 0;
        ackCount = 0;

        log.info("[P{}] propose  value={}  ballot={}", id, proposal, ballot);

        ReadMessage read = new ReadMessage(ballot);
        for (ActorRef p : peers) {
            p.tell(read, getSelf());    // line 6
        }
    }
}

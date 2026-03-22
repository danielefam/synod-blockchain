import akka.actor.AbstractActor;
import akka.actor.Props;

import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;

public class ResultCollector extends AbstractActor {

    public static Props props(int n, RunResult result) {
        return Props.create(ResultCollector.class, () -> new ResultCollector(n, result));
    }

    static class FlushMessage {}

    private final int n;
    private final RunResult result;
    private final List<DecisionEvent> events = new ArrayList<>();
    private boolean flushed = false;

    public ResultCollector(int n, RunResult result) {
        this.n = n;
        this.result = result;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(DecisionEvent.class, this::onDecisionEvent)
                .match(FlushMessage.class,  this::onFlush)
                .build();
    }

    private void onDecisionEvent(DecisionEvent evt) {
        if (flushed) return;
        events.add(evt);
        if (events.size() == n) {
            flush();
        }
    }

    private void onFlush(FlushMessage msg) {
        if (!flushed) flush();
    }

    private void flush() {
        flushed = true;

        result.decisions = events.size();

        if (!events.isEmpty()) {
            result.decidedValue = events.get(0).value;
            result.agreement = events.stream().allMatch(e -> e.value == result.decidedValue);

            LongSummaryStatistics stats =
                    events.stream().mapToLong(e -> e.latencyUs).summaryStatistics();

            result.firstLatency = stats.getMin();
            result.avgLatency = (long) stats.getAverage();
        }
    }
}
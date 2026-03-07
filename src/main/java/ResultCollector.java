import akka.actor.AbstractActor;
import akka.actor.Props;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;

public class ResultCollector extends AbstractActor {

    public static Props props(int n, int runId, int repetition, ExperimentConfig cfg) {
        return Props.create(ResultCollector.class,
                () -> new ResultCollector(n, runId, repetition, cfg));
    }

    static class FlushMessage {}

    private final int n;
    private final int runId;
    private final int repetition;
    private final ExperimentConfig cfg;
    private final List<DecisionEvent> events = new ArrayList<>();
    private boolean flushed = false;

    public ResultCollector(int n, int runId, int repetition, ExperimentConfig cfg) {
        this.n          = n;
        this.runId      = runId;
        this.repetition = repetition;
        this.cfg        = cfg;
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

        int decisions     = events.size();
        long firstLatency = -1;
        long avgLatency   = -1;
        int decidedValue  = -1;
        boolean agreement = false;

        if (!events.isEmpty()) {
            decidedValue = events.get(0).value;

            agreement = events.stream().allMatch(e -> e.value == events.get(0).value);

            LongSummaryStatistics stats =
                events.stream().mapToLong(e -> e.latencyMs).summaryStatistics();

            firstLatency = stats.getMin();
            avgLatency   = (long) stats.getAverage();
        }

        System.out.printf(
                "[Run %3d | rep %d] N=%3d f=%2d tle=%4dms alpha=%.1f" +
                " decisions=%3d firstLatency=%4dms avgLatency=%4dms agreement=%s%n",
                runId, repetition, cfg.n, cfg.f, cfg.tleMs, cfg.alpha,
                decisions, firstLatency, avgLatency, agreement ? "OK" : "VIOLATED!");

        // append one row to the CSV
        try (PrintWriter pw = new PrintWriter(new FileWriter("results/benchmark.csv", true))) {
            pw.printf("%d,%d,%d,%d,%d,%.1f,%d,%d,%d,%d,%b%n",
                    runId, repetition, cfg.n, cfg.f, cfg.tleMs, cfg.alpha,
                    decisions, firstLatency, avgLatency, decidedValue, agreement);
        } catch (IOException e) {
            System.err.println("cannot write CSV row: " + e.getMessage());
        }
    }
}
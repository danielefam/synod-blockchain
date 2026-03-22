import akka.actor.ActorRef;
import akka.actor.ActorSystem;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

public class Main {

    private static long graceMs(int n) {
        return 200 + 5L * n;
    }

    private static final int  REPETITIONS = 5;
    private static final String CSV_RUNS = "results/runs.csv";
    private static final String CSV_SUMMARY = "results/summary.csv";

    public static void main(String[] args) throws InterruptedException {

        int[] sizes = {3, 10, 30, 70, 100};
        // int[] sizes = {3, 10, 100};
        double[] alphas = {0.0, 0.1, 1.0};
        // double[] alphas = {0.0, 0.1, 1};
        long[] tles = {1000, 200, 100, 10, 1};
        // long[] tles = {100, 20};

        // create output dir and write header
        try (PrintWriter pw = new PrintWriter(new FileWriter(CSV_RUNS, false))) {
            pw.println("config_id,repetition,n,f,tle_ms,alpha," +
                       "decisions,first_latency_us,avg_latency_us,decided_value,agreement_ok");
        } catch (IOException e) {
            System.err.println("Cannot create runs CSV: " + e.getMessage());
            return;
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(CSV_SUMMARY, false))) {
            pw.println("config_id,n,f,tle_ms,alpha," +
                       "avg_first_latency_us,avg_decisions,no_decision_reps,agreement_violated");
        } catch (IOException e) {
            System.err.println("Cannot create summary CSV: " + e.getMessage());
            return;
        }
 
        int configId = 0;

        for (int n : sizes) {
            int f = (n - 1) / 2;
            for (double alpha : alphas) {
                for (long tle : tles) {
                    configId++;
                    ExperimentConfig cfg = new ExperimentConfig(n, f, tle, alpha);
 
                    // collect firstLatency from each of the 5 repetitions
                    List<Long> firstLatencies = new ArrayList<>();
                    List<Integer> decisionCounts = new ArrayList<>();
                    int noDecisionReps  = 0;    // reps where decisions == 0
                    boolean agreementViolated = false; // true only on real disagreement (shouldn't happens)
 
                    for (int rep = 1; rep <= REPETITIONS; rep++) {
                        RunResult result = runExperiment(cfg);

                        String status;
                        if (result.decisions == 0) {
                            status = "NO_DECISION";
                        } else if (!result.agreement) {
                            status = "VIOLATED!";
                        } else {
                            status = "OK";
                        }
 
                        // print and save individual run
                        System.out.printf(
                            "[cfg %3d | rep %d] N=%3d f=%2d tle=%4dms alpha=%.1f" +
                            " decisions=%3d firstLatency=%4dus avgLatency=%4dus %s%n",
                            configId, rep, cfg.n, cfg.f, cfg.tleMs, cfg.alpha,
                            result.decisions, result.firstLatency, result.avgLatency, status);
 
                        writeRunRow(configId, rep, cfg, result);
 
                        // accumulate for summary (only count runs where a decision was made)
                        if (result.decisions == 0) {
                            noDecisionReps++;
                        } else {
                            firstLatencies.add(result.firstLatency);
                            if (!result.agreement) agreementViolated = true;
                        }
                        decisionCounts.add(result.decisions);
                    }

                    // if some runs don't reach agreement they don't count right?
                    // So the average is from 3 or 4 instead of 5

                    // compute averages over the 5 repetitions
                    long avgConsensusLatency = firstLatencies.isEmpty() ? -1
                            : (long) firstLatencies.stream()
                                .mapToLong(Long::longValue).average().orElse(-1);
 
                    double avgDecisions = decisionCounts.stream()
                            .mapToInt(Integer::intValue).average().orElse(0);
 
                    // print and save summary row
                    System.out.printf(
                        "[cfg %3d SUMMARY  ] N=%3d f=%2d tle=%4dms alpha=%.1f" +
                        " avgLatency=%6dus avgDecisions=%.1f noDecisionReps=%d agreementViolated=%b%n%n",
                        configId, cfg.n, cfg.f, cfg.tleMs, cfg.alpha,
                        avgConsensusLatency, avgDecisions, noDecisionReps, agreementViolated);
 
                    writeSummaryRow(configId, cfg, avgConsensusLatency,
                                    avgDecisions, noDecisionReps, agreementViolated);
                }
            }
        }
 
        System.out.println("---------DONE--------");
        System.out.println("Per-run results: " + CSV_RUNS);
        System.out.println("Summary results: " + CSV_SUMMARY);
        System.out.println("---------FINISH SUMMARY--------");
    }

    private static RunResult runExperiment(ExperimentConfig cfg)
            throws InterruptedException {
 
        RunResult result = new RunResult();
        ActorSystem system = ActorSystem.create("synod");
 
        try {
            int n = cfg.n;
            int f = cfg.f;
            long tleMs = cfg.tleMs;
            double alpha = cfg.alpha;
 
            ActorRef collector = system.actorOf(
                    ResultCollector.props(n, result), "collector");
 
            List<ActorRef> processes = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) {
                ActorRef p = system.actorOf(
                        Process.props(i, n, collector, alpha), "process-" + i);
                processes.add(p);
            }
 
            SetPeersMessage setPeers = new SetPeersMessage(processes);
            for (ActorRef p : processes) {
                p.tell(setPeers, ActorRef.noSender());
            }
 
            List<ActorRef> shuffled   = new ArrayList<>(processes);
            Collections.shuffle(shuffled);
            List<ActorRef> faultProne = new ArrayList<>(shuffled.subList(0, f));
            for (ActorRef p : faultProne) {
                p.tell(new CrashMessage(), ActorRef.noSender());
            }
 
            for (ActorRef p : processes) {
                p.tell(new LaunchMessage(), ActorRef.noSender());
            }

            system.scheduler().scheduleOnce(
                scala.concurrent.duration.Duration.create(tleMs, TimeUnit.MILLISECONDS),
                () -> {
                    ActorRef leader = null;
                    for (ActorRef p : processes) {
                        if (!faultProne.contains(p)) {
                            leader = p;
                            break;
                        }
                    }
                    for (ActorRef p : processes) {
                        if (!p.equals(leader)) {
                            p.tell(new HoldMessage(), ActorRef.noSender());
                        }
                    }
                },
                system.dispatcher()
            );
 
            Thread.sleep(graceMs(n));
            collector.tell(new ResultCollector.FlushMessage(), ActorRef.noSender());
            Thread.sleep(100);
 
        } finally {
            system.terminate();
            try {
                scala.concurrent.Await.result(
                        system.whenTerminated(),
                        scala.concurrent.duration.Duration.apply(10, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                System.err.println("ActorSystem termination timed out.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
 
        return result;
    }
 
    // --------------- CSV helpers ------------------
    // maybe put it as another class??
 
    private static void writeRunRow(int configId, int rep,
                                    ExperimentConfig cfg, RunResult r) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(CSV_RUNS, true))) {
            pw.printf("%d,%d,%d,%d,%d,%.1f,%d,%d,%d,%d,%b%n",
                    configId, rep, cfg.n, cfg.f, cfg.tleMs, cfg.alpha,
                    r.decisions, r.firstLatency, r.avgLatency,
                    r.decidedValue, r.agreement);
        } catch (IOException e) {
            System.err.println("Could not write run row: " + e.getMessage());
        }
    }
 
    private static void writeSummaryRow(int configId, ExperimentConfig cfg,
                                        long avgConsensusLatency, double avgDecisions,
                                        int noDecisionReps, boolean agreementViolated) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(CSV_SUMMARY, true))) {
            pw.printf("%d,%d,%d,%d,%.1f,%d,%.1f,%d,%b%n",
                    configId, cfg.n, cfg.f, cfg.tleMs, cfg.alpha,
                    avgConsensusLatency, avgDecisions, noDecisionReps, agreementViolated);
        } catch (IOException e) {
            System.err.println("Could not write summary row: " + e.getMessage());
        }
    }
}
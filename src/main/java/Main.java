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

    private static final int  REPETITIONS = 5;
    private static final long GRACE_MS    = 300;

    public static void main(String[] args) throws InterruptedException {

        // int[] sizes = {3, 10, 30, 70, 100};
        int[] sizes = {100};
        // double[] alphas = {0.0, 0.1, 1.0};
        double[] alphas = {0.0};
        // long[] tles = {2000, 1000, 200, 100};
        long[] tles = {0};

        // create output dir and write header
        new java.io.File("results").mkdirs();
        try (PrintWriter pw = new PrintWriter(new FileWriter("results/benchmark.csv", false))) {
            pw.println("run_id,repetition,n,f,tle_ms,alpha,decisions," +
                       "first_latency_ms,avg_latency_ms,decided_value,agreement_ok");
        } catch (IOException e) {
            System.err.println("Cannot create CSV: " + e.getMessage());
            return;
        }

        int runId = 0;

        for (int n : sizes) {
            int f = (n - 1) / 2;
            for (double alpha : alphas) {
                for (long tle : tles) {
                    ExperimentConfig cfg = new ExperimentConfig(n, f, tle, alpha);
                    for (int rep = 1; rep <= REPETITIONS; rep++) {
                        runId++;
                        runExperiment(runId, rep, cfg);
                    }
                }
            }
        }

        System.out.println("results saved to results/benchmark.csv");
    }

    private static void runExperiment(int runId, int repetition, ExperimentConfig cfg)
            throws InterruptedException {

        ActorSystem system = ActorSystem.create("synod-run-" + runId);

        try {
            int n = cfg.n;
            int f = cfg.f;
            long tleMs = cfg.tleMs;
            double alpha = cfg.alpha;

            ActorRef collector = system.actorOf(
                    ResultCollector.props(n, runId, repetition, cfg), "collector");

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

            // leader election after tle ms
            Thread.sleep(tleMs);

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

            Thread.sleep(GRACE_MS);
            collector.tell(new ResultCollector.FlushMessage(), ActorRef.noSender());
            Thread.sleep(100);

        } finally {
            system.terminate();
            try {
                scala.concurrent.Await.result(
                        system.whenTerminated(),
                        scala.concurrent.duration.Duration.apply(10, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                System.err.println("[Run " + runId + "] termination timed out.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
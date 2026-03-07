public class ExperimentConfig {
    public final int n;         // total number of processes
    public final int f;         // number of fault-prone processes  (f < n/2)
    public final long tleMs;    // leader-election timeout in milliseconds
    public final double alpha;  // per-event crash probability for fault-prone processes

    public ExperimentConfig(int n, int f, long tleMs, double alpha) {
        this.n = n;
        this.f = f;
        this.tleMs = tleMs;
        this.alpha = alpha;
    }

    @Override
    public String toString() {
        return String.format("ExperimentConfig{n=%d, f=%d, tleMs=%d, alpha=%.1f}", n, f, tleMs, alpha);
    }
}

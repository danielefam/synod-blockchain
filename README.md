# Synod OFC — Obstruction-Free Consensus

## Project structure

```
synod/
├── pom.xml
├── src/
│   └── main/
│       ├── java/
│       │   ├── Main.java               # entry point, runs all experiments
│       │   ├── Process.java            # Synod algorithm actor
│       │   ├── ResultCollector.java    # collects DecisionEvents per run
│       │   ├── RunResult.java          # plain result object filled by the collector
│       │   ├── Messages.java           # all message classes
│       │   └── ExperimentConfig.java   # experiment parameters (n, f, tle, alpha)
│       └── resources/
│           └── logback.xml             # logging configuration
└── .github/
    └── workflows/
        └── build.yml                   # CI workflow (mvn compile on every push)
```

Results are written to:
```
results/
├── runs.csv       # one row per individual repetition
└── summary.csv    # one row per configuration (averaged over 5 repetitions)
```

## How to run

```bash
mvn compile exec:exec
```

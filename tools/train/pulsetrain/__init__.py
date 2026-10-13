"""PulseStages offline tooling (see tools/train/README.md): dataset preparation, training and LOSO validation.

Python prepares data, fits the emission classifier / HSMM-HMM statistics and writes reports; features and decoders
live only in Kotlin (:core-algo) and are always computed through :algo-cli.
"""

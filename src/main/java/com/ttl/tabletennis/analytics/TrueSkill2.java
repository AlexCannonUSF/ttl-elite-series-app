package com.ttl.tabletennis.analytics;

/**
 * Two-player TrueSkill-2 style rating update for drawless table-tennis matches.
 */
public final class TrueSkill2 {

    private static final double SQRT_2_PI = Math.sqrt(2.0 * Math.PI);
    private static final double MIN_CDF = 1e-12;

    private TrueSkill2() {
    }

    public static Update updateWinner(Rating winner, Rating loser, Parameters parameters) {
        if (winner == null || loser == null) {
            throw new IllegalArgumentException("winner and loser ratings are required");
        }
        Parameters p = parameters.canonical();

        Rating w0 = applyDynamics(winner, p);
        Rating l0 = applyDynamics(loser, p);
        double c = performanceVariance(w0, l0, p.beta());
        double t = (w0.mu() - l0.mu()) / c;
        double v = v(t);
        double w = w(t, v);

        double winnerVariance = w0.sigma() * w0.sigma();
        double loserVariance = l0.sigma() * l0.sigma();
        double cSquared = c * c;

        double winnerMu = w0.mu() + (winnerVariance / c) * v;
        double loserMu = l0.mu() - (loserVariance / c) * v;

        double winnerSigma = Math.sqrt(Math.max(
                p.sigmaFloor() * p.sigmaFloor(),
                winnerVariance * Math.max(0.0001, 1.0 - (winnerVariance / cSquared) * w)
        ));
        double loserSigma = Math.sqrt(Math.max(
                p.sigmaFloor() * p.sigmaFloor(),
                loserVariance * Math.max(0.0001, 1.0 - (loserVariance / cSquared) * w)
        ));

        return new Update(
                new Rating(winnerMu, Math.min(winnerSigma, p.sigmaCeiling())),
                new Rating(loserMu, Math.min(loserSigma, p.sigmaCeiling())),
                winProbability(winner, loser, p)
        );
    }

    public static Rating applyDynamics(Rating rating, Parameters parameters) {
        if (rating == null) {
            throw new IllegalArgumentException("rating is required");
        }
        Parameters p = parameters.canonical();
        double sigma = Math.sqrt(rating.sigma() * rating.sigma() + p.dynamicFactor() * p.dynamicFactor());
        return new Rating(rating.mu(), Math.min(Math.max(sigma, p.sigmaFloor()), p.sigmaCeiling()));
    }

    public static double winProbability(Rating player, Rating opponent, Parameters parameters) {
        if (player == null || opponent == null) {
            throw new IllegalArgumentException("player and opponent ratings are required");
        }
        Parameters p = parameters.canonical();
        double c = performanceVariance(player, opponent, p.beta());
        return clampProbability(normalCdf((player.mu() - opponent.mu()) / c));
    }

    public static double conservativeSkill(Rating rating) {
        if (rating == null) {
            throw new IllegalArgumentException("rating is required");
        }
        return rating.mu() - 3.0 * rating.sigma();
    }

    private static double performanceVariance(Rating a, Rating b, double beta) {
        return Math.sqrt(2.0 * beta * beta + a.sigma() * a.sigma() + b.sigma() * b.sigma());
    }

    private static double v(double t) {
        return normalPdf(t) / Math.max(MIN_CDF, normalCdf(t));
    }

    private static double w(double t, double v) {
        return v * (v + t);
    }

    private static double normalPdf(double x) {
        return Math.exp(-0.5 * x * x) / SQRT_2_PI;
    }

    private static double normalCdf(double x) {
        double sign = x < 0.0 ? -1.0 : 1.0;
        double z = Math.abs(x) / Math.sqrt(2.0);
        double erf = 1.0 - (((((1.061405429 * t(z) - 1.453152027) * t(z)) + 1.421413741)
                * t(z) - 0.284496736) * t(z) + 0.254829592) * t(z) * Math.exp(-z * z);
        return 0.5 * (1.0 + sign * erf);
    }

    private static double t(double z) {
        return 1.0 / (1.0 + 0.3275911 * z);
    }

    private static double clampProbability(double probability) {
        if (probability < 1e-6) return 1e-6;
        if (probability > 1.0 - 1e-6) return 1.0 - 1e-6;
        return probability;
    }

    public record Rating(double mu, double sigma) {
        public Rating {
            if (!Double.isFinite(mu)) {
                throw new IllegalArgumentException("mu must be finite");
            }
            if (!Double.isFinite(sigma) || sigma <= 0.0) {
                throw new IllegalArgumentException("sigma must be > 0");
            }
        }
    }

    public record Parameters(double beta,
                             double dynamicFactor,
                             double sigmaFloor,
                             double sigmaCeiling) {
        public Parameters canonical() {
            if (!Double.isFinite(beta) || beta <= 0.0) {
                throw new IllegalArgumentException("beta must be > 0");
            }
            if (!Double.isFinite(dynamicFactor) || dynamicFactor < 0.0) {
                throw new IllegalArgumentException("dynamicFactor must be >= 0");
            }
            if (!Double.isFinite(sigmaFloor) || sigmaFloor <= 0.0) {
                throw new IllegalArgumentException("sigmaFloor must be > 0");
            }
            if (!Double.isFinite(sigmaCeiling) || sigmaCeiling < sigmaFloor) {
                throw new IllegalArgumentException("sigmaCeiling must be >= sigmaFloor");
            }
            return this;
        }
    }

    public record Update(Rating winner,
                         Rating loser,
                         double winnerProbabilityBefore) {
    }
}

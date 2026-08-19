package com.ttl.tabletennis.analytics;

/**
 * Weng-Lin style Bayesian online rating update for drawless two-player matches.
 */
public final class WengLin {

    private WengLin() {
    }

    public static Update updateWinner(Rating winner, Rating loser, Parameters parameters) {
        if (winner == null || loser == null) {
            throw new IllegalArgumentException("winner and loser ratings are required");
        }
        Parameters p = parameters.canonical();

        Rating w0 = applyDynamics(winner, p);
        Rating l0 = applyDynamics(loser, p);
        double probability = winProbability(w0, l0, p);
        double surprise = 1.0 - probability;
        double performanceVariance = performanceVariance(w0, l0, p.beta());
        double performanceScale = Math.sqrt(performanceVariance);

        double winnerVariance = w0.uncertainty() * w0.uncertainty();
        double loserVariance = l0.uncertainty() * l0.uncertainty();
        double winnerRating = w0.rating() + p.learningRate() * (winnerVariance / performanceScale) * surprise;
        double loserRating = l0.rating() - p.learningRate() * (loserVariance / performanceScale) * surprise;

        double information = probability * (1.0 - probability);
        double winnerUncertainty = shrinkUncertainty(winnerVariance, performanceVariance, information, p);
        double loserUncertainty = shrinkUncertainty(loserVariance, performanceVariance, information, p);

        return new Update(
                new Rating(winnerRating, winnerUncertainty),
                new Rating(loserRating, loserUncertainty),
                probability
        );
    }

    public static Rating applyDynamics(Rating rating, Parameters parameters) {
        if (rating == null) {
            throw new IllegalArgumentException("rating is required");
        }
        Parameters p = parameters.canonical();
        double uncertainty = Math.sqrt(rating.uncertainty() * rating.uncertainty()
                + p.dynamicFactor() * p.dynamicFactor());
        return new Rating(rating.rating(), clampUncertainty(uncertainty, p));
    }

    public static double winProbability(Rating player, Rating opponent, Parameters parameters) {
        if (player == null || opponent == null) {
            throw new IllegalArgumentException("player and opponent ratings are required");
        }
        Parameters p = parameters.canonical();
        double scale = Math.sqrt(performanceVariance(player, opponent, p.beta()));
        double z = (player.rating() - opponent.rating()) / scale;
        return clampProbability(logistic(z));
    }

    public static double conservativeRating(Rating rating) {
        if (rating == null) {
            throw new IllegalArgumentException("rating is required");
        }
        return rating.rating() - 2.0 * rating.uncertainty();
    }

    private static double shrinkUncertainty(double ratingVariance,
                                            double performanceVariance,
                                            double information,
                                            Parameters parameters) {
        double retained = 1.0 - parameters.learningRate() * (ratingVariance / performanceVariance) * information;
        retained = Math.max(0.0001, Math.min(1.0, retained));
        double next = Math.sqrt(Math.max(parameters.uncertaintyFloor() * parameters.uncertaintyFloor(),
                ratingVariance * retained));
        return clampUncertainty(next, parameters);
    }

    private static double performanceVariance(Rating a, Rating b, double beta) {
        return 2.0 * beta * beta
                + a.uncertainty() * a.uncertainty()
                + b.uncertainty() * b.uncertainty();
    }

    private static double logistic(double x) {
        if (x >= 0.0) {
            double exp = Math.exp(-x);
            return 1.0 / (1.0 + exp);
        }
        double exp = Math.exp(x);
        return exp / (1.0 + exp);
    }

    private static double clampProbability(double probability) {
        if (probability < 1e-6) return 1e-6;
        if (probability > 1.0 - 1e-6) return 1.0 - 1e-6;
        return probability;
    }

    private static double clampUncertainty(double uncertainty, Parameters parameters) {
        return Math.min(Math.max(uncertainty, parameters.uncertaintyFloor()), parameters.uncertaintyCeiling());
    }

    public record Rating(double rating, double uncertainty) {
        public Rating {
            if (!Double.isFinite(rating)) {
                throw new IllegalArgumentException("rating must be finite");
            }
            if (!Double.isFinite(uncertainty) || uncertainty <= 0.0) {
                throw new IllegalArgumentException("uncertainty must be > 0");
            }
        }
    }

    public record Parameters(double beta,
                             double dynamicFactor,
                             double uncertaintyFloor,
                             double uncertaintyCeiling,
                             double learningRate) {
        public Parameters canonical() {
            if (!Double.isFinite(beta) || beta <= 0.0) {
                throw new IllegalArgumentException("beta must be > 0");
            }
            if (!Double.isFinite(dynamicFactor) || dynamicFactor < 0.0) {
                throw new IllegalArgumentException("dynamicFactor must be >= 0");
            }
            if (!Double.isFinite(uncertaintyFloor) || uncertaintyFloor <= 0.0) {
                throw new IllegalArgumentException("uncertaintyFloor must be > 0");
            }
            if (!Double.isFinite(uncertaintyCeiling) || uncertaintyCeiling < uncertaintyFloor) {
                throw new IllegalArgumentException("uncertaintyCeiling must be >= uncertaintyFloor");
            }
            if (!Double.isFinite(learningRate) || learningRate <= 0.0 || learningRate > 2.0) {
                throw new IllegalArgumentException("learningRate must be in (0, 2]");
            }
            return this;
        }
    }

    public record Update(Rating winner,
                         Rating loser,
                         double winnerProbabilityBefore) {
    }
}

package com.ttl.tabletennis.analytics;

import java.util.List;

/**
 * Mark Glickman's Glicko-2 update algorithm.
 */
public final class Glicko2 {

    private static final double RATING_SCALE = 173.7178;
    private static final double EPSILON = 0.000001;

    private Glicko2() {
    }

    public static Rating update(Rating current, List<OpponentResult> results, double tau) {
        if (current == null) {
            throw new IllegalArgumentException("current rating is required");
        }
        if (tau <= 0) {
            throw new IllegalArgumentException("tau must be > 0");
        }

        double mu = toMu(current.rating());
        double phi = toPhi(current.ratingDeviation());
        double sigma = current.volatility();

        if (results == null || results.isEmpty()) {
            double phiPrimeNoGames = Math.sqrt(phi * phi + sigma * sigma);
            return new Rating(fromMu(mu), fromPhi(clampPhi(phiPrimeNoGames)), sigma);
        }

        double vInv = 0.0;
        double deltaSum = 0.0;
        for (OpponentResult result : results) {
            double muJ = toMu(result.opponentRating());
            double phiJ = toPhi(result.opponentRatingDeviation());
            double g = g(phiJ);
            double e = e(mu, muJ, phiJ);
            vInv += g * g * e * (1.0 - e);
            deltaSum += g * (result.score() - e);
        }
        if (vInv <= 0.0) {
            double phiPrimeNoGames = Math.sqrt(phi * phi + sigma * sigma);
            return new Rating(fromMu(mu), fromPhi(clampPhi(phiPrimeNoGames)), sigma);
        }

        double v = 1.0 / vInv;
        double delta = v * deltaSum;
        double sigmaPrime = solveSigmaPrime(phi, sigma, v, delta, tau);

        double phiStar = Math.sqrt(phi * phi + sigmaPrime * sigmaPrime);
        double phiPrime = 1.0 / Math.sqrt((1.0 / (phiStar * phiStar)) + (1.0 / v));
        double muPrime = mu + (phiPrime * phiPrime * deltaSum);

        return new Rating(fromMu(muPrime), fromPhi(clampPhi(phiPrime)), sigmaPrime);
    }

    public static double expectedScore(double ratingA, double rdA, double ratingB, double rdB) {
        double muA = toMu(ratingA);
        double muB = toMu(ratingB);
        double phiB = toPhi(Math.max(30.0, rdB));
        double g = g(phiB);
        return 1.0 / (1.0 + Math.exp(-g * (muA - muB)));
    }

    private static double solveSigmaPrime(double phi, double sigma, double v, double delta, double tau) {
        double a = Math.log(sigma * sigma);
        double A = a;
        double B;

        if ((delta * delta) > (phi * phi + v)) {
            B = Math.log(delta * delta - phi * phi - v);
        } else {
            int k = 1;
            while (f(a - k * tau, delta, phi, v, a, tau) < 0.0) {
                k++;
            }
            B = a - k * tau;
        }

        double fA = f(A, delta, phi, v, a, tau);
        double fB = f(B, delta, phi, v, a, tau);

        while (Math.abs(B - A) > EPSILON) {
            double C = A + (A - B) * fA / (fB - fA);
            double fC = f(C, delta, phi, v, a, tau);
            if (fC * fB < 0.0) {
                A = B;
                fA = fB;
            } else {
                fA = fA / 2.0;
            }
            B = C;
            fB = fC;
        }

        return Math.exp(A / 2.0);
    }

    private static double f(double x, double delta, double phi, double v, double a, double tau) {
        double ex = Math.exp(x);
        double num = ex * (delta * delta - phi * phi - v - ex);
        double den = 2.0 * Math.pow(phi * phi + v + ex, 2.0);
        return (num / den) - ((x - a) / (tau * tau));
    }

    private static double g(double phi) {
        return 1.0 / Math.sqrt(1.0 + (3.0 * phi * phi) / (Math.PI * Math.PI));
    }

    private static double e(double mu, double muJ, double phiJ) {
        return 1.0 / (1.0 + Math.exp(-g(phiJ) * (mu - muJ)));
    }

    private static double clampPhi(double phi) {
        double min = toPhi(30.0);
        double max = toPhi(350.0);
        if (phi < min) return min;
        if (phi > max) return max;
        return phi;
    }

    private static double toMu(double rating) {
        return (rating - 1500.0) / RATING_SCALE;
    }

    private static double fromMu(double mu) {
        return mu * RATING_SCALE + 1500.0;
    }

    private static double toPhi(double rd) {
        return rd / RATING_SCALE;
    }

    private static double fromPhi(double phi) {
        return phi * RATING_SCALE;
    }

    public record Rating(double rating, double ratingDeviation, double volatility) {
    }

    public record OpponentResult(double opponentRating,
                                 double opponentRatingDeviation,
                                 double score) {
    }
}

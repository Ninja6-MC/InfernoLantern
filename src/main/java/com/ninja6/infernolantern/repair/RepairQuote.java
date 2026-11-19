package com.ninja6.infernolantern.repair;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Whole-item quote derived from the same decimal inputs at preview and durable admission. */
public record RepairQuote(int items, double resultingHp, BigDecimal hpGain, boolean partialFinalItem) {
    public static Optional<RepairQuote> calculate(double currentHp, double maximumHp, double rate, int offered) {
        if (!Double.isFinite(currentHp) || currentHp < 0 || !Double.isFinite(maximumHp) || maximumHp <= 0
                || currentHp > maximumHp || !Double.isFinite(rate) || rate <= 0 || offered < 1) {
            throw new IllegalArgumentException("Repair quote bounds");
        }
        if (currentHp == maximumHp) return Optional.empty();

        BigDecimal current = BigDecimal.valueOf(currentHp);
        BigDecimal missing = BigDecimal.valueOf(maximumHp).subtract(current);
        BigDecimal unit = BigDecimal.valueOf(rate);
        BigDecimal available = unit.multiply(BigDecimal.valueOf(offered));
        int used = available.compareTo(missing) < 0 ? offered : missing.divide(unit, 0, RoundingMode.CEILING).intValueExact();
        BigDecimal nominal = unit.multiply(BigDecimal.valueOf(used));
        double resultingHp = current.add(missing.min(nominal)).doubleValue();
        if (resultingHp <= currentHp) return Optional.empty();
        return Optional.of(new RepairQuote(used, resultingHp,
                BigDecimal.valueOf(resultingHp).subtract(current), nominal.compareTo(missing) > 0));
    }
}

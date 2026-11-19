package com.ninja6.infernolantern.repair;

import java.math.BigDecimal;
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
        BigDecimal maximum = BigDecimal.valueOf(maximumHp);
        BigDecimal unit = BigDecimal.valueOf(rate);
        double resultingHp = resultingHp(current, maximum, unit, offered);
        if (resultingHp <= currentHp) return Optional.empty();

        // HP is stored as a double. Find the fewest items that produce the same durable result.
        int minimum = 1;
        int maximumItems = offered;
        while (minimum < maximumItems) {
            int middle = minimum + (maximumItems - minimum) / 2;
            if (resultingHp(current, maximum, unit, middle) < resultingHp) minimum = middle + 1;
            else maximumItems = middle;
        }
        BigDecimal nominal = unit.multiply(BigDecimal.valueOf(minimum));
        return Optional.of(new RepairQuote(minimum, resultingHp, BigDecimal.valueOf(resultingHp).subtract(current),
                current.add(nominal).compareTo(maximum) > 0));
    }

    private static double resultingHp(BigDecimal current, BigDecimal maximum, BigDecimal rate, int items) {
        return current.add(rate.multiply(BigDecimal.valueOf(items))).min(maximum).doubleValue();
    }
}

<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * How a loan is repaid (MAG-522), which decides its schedule.
 *
 * `amortizing_fixed`: a constant payment eats into the capital (the default,
 * and every loan entered before the types). `amortizing_variable`: the same at
 * the current rate, bounded by the cap and the floor. `in_fine`: interest only,
 * the capital at the last payment. `stepped`: the payment of each step.
 * `deferred_partial`: interest only during the deferral, then amortized.
 * `deferred_total`: nothing paid during the deferral, the interest added to the
 * capital. `bridge`: a short in fine, waiting for a sale.
 */
enum LoanType: string
{
    case AmortizingFixed = 'amortizing_fixed';
    case AmortizingVariable = 'amortizing_variable';
    case InFine = 'in_fine';
    case Stepped = 'stepped';
    case DeferredPartial = 'deferred_partial';
    case DeferredTotal = 'deferred_total';
    case Bridge = 'bridge';

    /** The capital is repaid in one go at the last payment: the end must be known. */
    public function isInterestOnly(): bool
    {
        return self::InFine === $this || self::Bridge === $this;
    }

    public function isDeferred(): bool
    {
        return self::DeferredPartial === $this || self::DeferredTotal === $this;
    }

    /**
     * Whether `monthlyPaymentCents` must cover the interest on the capital owed
     * today. An in fine pays only the interest, a deferred total nothing yet, a
     * stepped loan the payment of its step.
     */
    public function mustAmortiseNow(): bool
    {
        return self::AmortizingFixed === $this
            || self::AmortizingVariable === $this
            || self::DeferredPartial === $this;
    }
}

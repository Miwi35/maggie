<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * « Régularisé » (settled true) or « Annuler » (false) on a rejected payment,
 * named by either of its legs (MAG-424).
 */
final readonly class SettleRejectionCommand
{
    public function __construct(
        public string $transactionId,
        public bool $settled,
    ) {
    }
}

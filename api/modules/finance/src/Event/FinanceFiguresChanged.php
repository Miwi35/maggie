<?php

declare(strict_types=1);

namespace Maggie\Finance\Event;

/**
 * A row the Finance Dashboard is made of was stored, changed or removed for
 * this user (MAG-545): their dashboard read model is out of date. Identifier
 * only; whatever rebuilds it listens here.
 */
final readonly class FinanceFiguresChanged
{
    public function __construct(
        public string $userId,
    ) {
    }
}

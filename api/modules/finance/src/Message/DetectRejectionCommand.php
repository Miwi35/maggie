<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class DetectRejectionCommand
{
    public function __construct(
        public string $transactionId,
    ) {
    }
}

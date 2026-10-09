<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class DetectInternalTransferCommand
{
    public function __construct(
        public string $transactionId,
    ) {
    }
}

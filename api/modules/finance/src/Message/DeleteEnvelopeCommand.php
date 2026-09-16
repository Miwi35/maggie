<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

final readonly class DeleteEnvelopeCommand
{
    public function __construct(
        public string $envelopeId,
    ) {
    }
}

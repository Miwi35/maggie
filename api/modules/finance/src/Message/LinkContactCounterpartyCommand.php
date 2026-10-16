<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/** « This label on my statements is this contact »: attaches the transfers it recognises. */
final readonly class LinkContactCounterpartyCommand
{
    public function __construct(
        public string $userId,
        public string $contactId,
        public string $label,
    ) {
    }
}

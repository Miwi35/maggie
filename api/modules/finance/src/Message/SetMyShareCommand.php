<?php

declare(strict_types=1);

namespace Maggie\Finance\Message;

/**
 * « Ma part du gîte est de 200 €, le reste Julie, Tom et Inès me le doivent »:
 * sets the part the owner kept of a spend and the créance the rest becomes.
 */
final readonly class SetMyShareCommand
{
    /**
     * @param list<array{contactId: string, amountCents: int|null}> $shares an amount left null on every share splits the rest equally
     */
    public function __construct(
        public string $userId,
        public string $transactionId,
        public int $myShareCents,
        public array $shares,
        public ?string $object = null,
        public ?string $notes = null,
    ) {
    }
}

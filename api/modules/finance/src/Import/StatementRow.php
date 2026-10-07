<?php

declare(strict_types=1);

namespace Maggie\Finance\Import;

/**
 * One movement as read from a bank export, before it becomes a Transaction.
 * Amounts are already signed integer cents: negative out, positive in.
 */
final readonly class StatementRow
{
    public function __construct(
        public \DateTimeImmutable $bookedAt,
        public string $label,
        public int $amountCents,
        public string $currency,
        /** The line as it stood in the file, for error reporting. */
        public int $lineNumber,
        /** Who the bank says the other party is; null when the export only has a label. */
        public ?string $counterpartyName = null,
        /**
         * Labels an earlier sync stored this same movement under, so a change
         * in how the label is built does not read as a new movement.
         *
         * @var list<string>
         */
        public array $knownAs = [],
    ) {
    }

    /**
     * What makes two rows the same movement. Banks reissue exports with the
     * same lines, so an import has to recognise what it has already seen —
     * and two identical coffees on the same day are genuinely two movements,
     * which is why the position in the file takes part.
     */
    public function fingerprint(): string
    {
        return sha1(implode('|', [
            $this->bookedAt->format('Y-m-d'),
            mb_strtolower(trim($this->label)),
            (string) $this->amountCents,
            $this->currency,
        ]));
    }
}

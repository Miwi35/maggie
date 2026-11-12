<?php

declare(strict_types=1);

namespace Maggie\Finance\Bank\EnableBanking;

/** The provider answered a request with a 4xx or 5xx, and said why. */
class BankRefusedException extends \RuntimeException
{
    public function __construct(private readonly int $status, private readonly string $bankMessage, string $message)
    {
        parent::__construct($message);
    }

    public function getBankMessage(): string
    {
        return $this->bankMessage;
    }

    /** A request the provider found invalid: for a statement, the period asked for. */
    public function isAboutThePeriod(): bool
    {
        return 400 === $this->status || 422 === $this->status;
    }
}

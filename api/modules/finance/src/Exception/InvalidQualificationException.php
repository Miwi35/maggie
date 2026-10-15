<?php

declare(strict_types=1);

namespace Maggie\Finance\Exception;

/** A qualification refused as given — partial, or of an unknown kind — with the one in place. */
final class InvalidQualificationException extends \DomainException
{
    /** @param array<string, mixed>|null $current the qualification in place, null on a creation */
    public function __construct(string $message, public readonly ?array $current = null)
    {
        parent::__construct($message);
    }

    /** @return array<string, mixed> the body a tool answers with */
    public function toResponse(): array
    {
        return null === $this->current
            ? ['error' => $this->getMessage()]
            : ['error' => $this->getMessage(), 'currentQualification' => $this->current];
    }
}

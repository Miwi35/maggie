<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** A health journal value refused (MAG-750): the message names the field, and so does {@see self::$field}. */
class InvalidHealthDayException extends \DomainException
{
    public function __construct(public readonly ?string $field, string $message)
    {
        parent::__construct(null === $field ? $message : "{$field}: {$message}");
    }
}

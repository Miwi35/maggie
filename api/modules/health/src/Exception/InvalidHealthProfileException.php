<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** A profile value that cannot be taken as is (MAG-707): the message names the field, and so does {@see self::$field}. */
class InvalidHealthProfileException extends \DomainException
{
    public function __construct(public readonly string $field, string $message)
    {
        parent::__construct("{$field}: {$message}");
    }
}

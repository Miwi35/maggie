<?php

declare(strict_types=1);

namespace Maggie\Calendar\Exception;

/** A trip that cannot be asked for as is (MAG-653): the message names the field, and so does {@see self::$field}. */
final class InvalidTripException extends \DomainException
{
    public function __construct(public readonly string $field, string $message)
    {
        parent::__construct("{$field}: {$message}");
    }
}

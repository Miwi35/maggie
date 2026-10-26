<?php

declare(strict_types=1);

namespace Maggie\Calendar\Exception;

/** A place refused (MAG-652): the message names the field, and so does {@see self::$field}. */
final class InvalidPlaceException extends \DomainException
{
    public function __construct(public readonly string $field, string $message)
    {
        parent::__construct("{$field}: {$message}");
    }
}

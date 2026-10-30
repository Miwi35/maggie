<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** A workout that cannot be taken as is (MAG-705): the message names the field, and so does {@see self::$field}. */
class InvalidWorkoutException extends \DomainException
{
    public function __construct(public readonly ?string $field, string $message)
    {
        parent::__construct(null === $field ? $message : "{$field}: {$message}");
    }
}

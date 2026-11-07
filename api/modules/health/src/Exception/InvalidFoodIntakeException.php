<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** A food intake that cannot be recorded as is (MAG-748): the message names the field, and so does {@see self::$field}. */
class InvalidFoodIntakeException extends \DomainException
{
    public function __construct(public readonly ?string $field, string $message)
    {
        parent::__construct(null === $field ? $message : "{$field}: {$message}");
    }
}

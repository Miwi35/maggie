<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** A body measurement that cannot be taken as is (MAG-706): the message names the field, and so does {@see self::$field}. */
class InvalidBodyMeasurementException extends \DomainException
{
    public function __construct(public readonly ?string $field, string $message)
    {
        parent::__construct(null === $field ? $message : "{$field}: {$message}");
    }
}

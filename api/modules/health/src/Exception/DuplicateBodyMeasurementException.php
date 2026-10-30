<?php

declare(strict_types=1);

namespace Maggie\Health\Exception;

/** The same user already has a measurement with this externalId: a 422 for the API, as the validator answers for a PATCH. */
final class DuplicateBodyMeasurementException extends InvalidBodyMeasurementException
{
    public function __construct()
    {
        parent::__construct('externalId', 'This externalId is already used by another measurement.');
    }
}

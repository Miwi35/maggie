<?php

declare(strict_types=1);

namespace Maggie\Finance\Exception;

/** A spend attributed to an envelope that is not a one-off one, or that is not the owner's. */
class OneOffAttributionException extends \DomainException
{
}

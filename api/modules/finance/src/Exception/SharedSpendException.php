<?php

declare(strict_types=1);

namespace Maggie\Finance\Exception;

/** A change a shared spend, or a contact with a share in a debt, cannot take while the share stands. */
class SharedSpendException extends \DomainException
{
}

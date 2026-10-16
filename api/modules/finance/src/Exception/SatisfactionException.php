<?php

declare(strict_types=1);

namespace Maggie\Finance\Exception;

/** A satisfaction given to a line that is no spend: an income or a movement between the owner's own accounts. */
class SatisfactionException extends \DomainException
{
}

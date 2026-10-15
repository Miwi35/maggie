<?php

declare(strict_types=1);

namespace Maggie\Finance\Exception;

/**
 * A split cannot be applied: an envelope that is not a daughter of the mother
 * for the same period, or amounts that add up to more than the mother holds.
 * A refused request, 400 — nothing is changed.
 */
class EnvelopeSplitException extends \DomainException
{
}

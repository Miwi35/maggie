<?php

declare(strict_types=1);

namespace Maggie\Grocery\Exception;

/**
 * A packaging that cannot be computed with: a size without its unit, or a
 * size that is not positive. The API answers it with a 400.
 */
final class InvalidPackagingException extends \DomainException
{
}

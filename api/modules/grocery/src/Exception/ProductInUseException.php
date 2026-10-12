<?php

declare(strict_types=1);

namespace Maggie\Grocery\Exception;

/** A product something else still depends on cannot be deleted. The API answers it with a 409. */
final class ProductInUseException extends \DomainException
{
}

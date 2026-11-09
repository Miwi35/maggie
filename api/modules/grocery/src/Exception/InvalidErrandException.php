<?php

declare(strict_types=1);

namespace Maggie\Grocery\Exception;

/** An errand that cannot end as asked: a paid amount below zero, or one with no store to pay it in. The API answers it with a 400. */
final class InvalidErrandException extends \DomainException
{
}

<?php

declare(strict_types=1);

namespace Maggie\Finance\Exception;

/** The category's nature (income or not) contradicts the sign of the amount. */
class IncompatibleCategoryException extends \DomainException
{
}

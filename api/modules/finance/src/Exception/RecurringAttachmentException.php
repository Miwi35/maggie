<?php

declare(strict_types=1);

namespace Maggie\Finance\Exception;

/**
 * A transaction cannot be this occurrence of this recurring operation: the
 * occurrence is settled by another line, the date is not a due date, the
 * account, currency or sign differ. A refused request, 422 — said before the
 * unique constraint would answer 500.
 */
class RecurringAttachmentException extends \DomainException
{
}

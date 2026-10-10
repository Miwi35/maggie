<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** What the owner sees a rejected payment as: the word the incident list shows. */
enum RejectedPaymentKind: string
{
    case DirectDebit = 'direct_debit';
    case Transfer = 'transfer';
}

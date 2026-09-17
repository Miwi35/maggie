<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/** Where a bank connection stands in its life. */
enum BankConnectionStatus: string
{
    /** The consent journey has started but the bank has not answered yet. */
    case Pending = 'pending';
    case Active = 'active';
    /** The consent ran out: the bank stops answering until it is renewed. */
    case Expired = 'expired';
    /** Taken back by the user, here or at the bank. */
    case Revoked = 'revoked';
}

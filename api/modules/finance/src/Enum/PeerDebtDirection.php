<?php

declare(strict_types=1);

namespace Maggie\Finance\Enum;

/**
 * Which way a debt between the owner and their contacts goes: a créance
 * (someone owes the owner) or a dette (the owner owes someone, MAG-452).
 */
enum PeerDebtDirection: string
{
    case OwedToMe = 'owed_to_me';
    case IOwe = 'i_owe';
}

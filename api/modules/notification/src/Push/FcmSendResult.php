<?php

declare(strict_types=1);

namespace Maggie\Notification\Push;

enum FcmSendResult
{
    case Sent;
    /** Google no longer knows the token: the app was uninstalled or the token replaced. */
    case UnknownToken;
    /** Google refused the message itself; sending it again would fail the same way. */
    case Rejected;
}

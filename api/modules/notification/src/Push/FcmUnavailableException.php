<?php

declare(strict_types=1);

namespace Maggie\Notification\Push;

/** FCM could not take the message now (quota, outage, expired credentials): worth retrying. */
final class FcmUnavailableException extends \RuntimeException
{
}

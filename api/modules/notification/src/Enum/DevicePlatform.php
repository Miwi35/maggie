<?php

declare(strict_types=1);

namespace Maggie\Notification\Enum;

/** What kind of device a push token was issued to. */
enum DevicePlatform: string
{
    case Android = 'android';
    case Ios = 'ios';
    case Web = 'web';
}

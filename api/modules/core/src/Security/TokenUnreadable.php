<?php

declare(strict_types=1);

namespace Maggie\Core\Security;

/** A sealed token does not open: unknown format, altered, or sealed for another record, owner, usage or key. Never carries the token. */
final class TokenUnreadable extends \RuntimeException
{
}

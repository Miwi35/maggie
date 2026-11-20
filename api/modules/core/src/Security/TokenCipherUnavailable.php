<?php

declare(strict_types=1);

namespace Maggie\Core\Security;

/** The key that seals connected-service tokens is missing or malformed: nothing can be sealed or opened. Never carries the token. */
final class TokenCipherUnavailable extends \RuntimeException
{
}

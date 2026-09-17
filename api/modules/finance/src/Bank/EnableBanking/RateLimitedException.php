<?php

declare(strict_types=1);

namespace Maggie\Finance\Bank\EnableBanking;

/**
 * The bank declined another fetch for the time being.
 *
 * Its own doing, not the provider's: most cap unattended fetches at four a
 * day. Distinct from a plain failure because the answer is to wait, never to
 * retry harder.
 */
class RateLimitedException extends \RuntimeException
{
}

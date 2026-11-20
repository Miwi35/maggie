<?php

declare(strict_types=1);

namespace Maggie\Core\Security;

/** A connected-service token is stored but could not be opened: the service is never called with a wrong or missing token. Never carries the token. */
final class ConnectedServiceTokenUnavailable extends \RuntimeException
{
}

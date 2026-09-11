<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp;

final class MissingMcpUserException extends \RuntimeException
{
    public const MESSAGE = 'No user bound to this MCP call. Send a user JWT, or the service token with an X-Maggie-User-Id header.';

    public function __construct()
    {
        parent::__construct(self::MESSAGE);
    }
}

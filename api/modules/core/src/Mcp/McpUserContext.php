<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp;

use Maggie\Core\Entity\User;
use Symfony\Bundle\SecurityBundle\Security;

/**
 * Resolves the user an MCP tool acts for.
 *
 * The user is authenticated by McpAccessListener (JWT subject, or the
 * X-Maggie-User-Id header when the caller presents the service token) and
 * stored in the security token storage, so tests only need loginUser().
 */
class McpUserContext
{
    public function __construct(
        private readonly Security $security,
    ) {
    }

    public function getUser(): ?User
    {
        $user = $this->security->getUser();

        return $user instanceof User ? $user : null;
    }

    /**
     * @throws MissingMcpUserException when no user is bound to the current call
     */
    public function requireUser(): User
    {
        return $this->getUser() ?? throw new MissingMcpUserException();
    }
}

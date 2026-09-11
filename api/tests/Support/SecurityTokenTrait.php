<?php

namespace App\Tests\Support;

use Maggie\Core\Entity\User;
use Symfony\Component\Security\Core\Authentication\Token\UsernamePasswordToken;

trait SecurityTokenTrait
{
    protected function loginUser(User $user): void
    {
        $tokenStorage = self::getContainer()->get('security.token_storage');
        $token = new UsernamePasswordToken($user, 'main', $user->getRoles());
        $tokenStorage->setToken($token);
    }

    /**
     * Binds the fixture user to the security context, the way McpAccessListener
     * does for a real MCP call. Requires FixtureLoaderTrait.
     */
    protected function loginFixtureUser(string $ref = 'test_user'): User
    {
        $user = $this->getFixture($ref);

        if (!$user instanceof User) {
            throw new \InvalidArgumentException(sprintf('Fixture "%s" is not a User.', $ref));
        }

        $this->loginUser($user);

        return $user;
    }
}

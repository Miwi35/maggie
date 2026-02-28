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
}

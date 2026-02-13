<?php

namespace App\Tests\Support;

use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Entity\User;

trait AuthenticatedTestTrait
{
    private User $testUser;
    private string $testToken;

    protected function authenticateAsTestUser(): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->testUser = new User();
        $this->testUser->setEmail('test@example.com');
        $this->testUser->setGoogleId('google-test-id');
        $this->testUser->setName('Test User');

        $em->persist($this->testUser);
        $em->flush();

        $jwtManager = self::getContainer()->get(JWTTokenManagerInterface::class);
        $this->testToken = $jwtManager->create($this->testUser);
    }

    /**
     * @return array<string, string>
     */
    protected function authHeaders(): array
    {
        return ['HTTP_AUTHORIZATION' => 'Bearer ' . $this->testToken];
    }
}

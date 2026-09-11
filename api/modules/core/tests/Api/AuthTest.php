<?php

namespace Maggie\Core\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Gesdinet\JWTRefreshTokenBundle\Generator\RefreshTokenGeneratorInterface;
use Gesdinet\JWTRefreshTokenBundle\Model\RefreshTokenManagerInterface;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class AuthTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/events', [], [], [
            'HTTP_ACCEPT' => 'application/ld+json',
        ]);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAuthGoogleMissingIdTokenReturns400(): void
    {
        $this->client->request('POST', '/api/auth/google', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode([], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(400);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Missing idToken', $data['error']);
    }

    public function testAuthenticatedRequestReturns200(): void
    {
        $this->purgeDatabase();
        $this->authenticateAsTestUser();

        $this->client->request('GET', '/api/events', [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful();
    }

    public function testRefreshTokenReturnsNewJwt(): void
    {
        $refreshToken = $this->createRefreshTokenForUser();

        $this->client->request('POST', '/api/token/refresh', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['refresh_token' => $refreshToken], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('token', $data);
        self::assertNotEmpty($data['token']);
        // single_use rotation: a fresh refresh token is returned.
        self::assertArrayHasKey('refresh_token', $data);
        self::assertNotSame($refreshToken, $data['refresh_token']);
    }

    public function testRefreshTokenWithInvalidTokenReturns401(): void
    {
        $this->client->request('POST', '/api/token/refresh', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['refresh_token' => 'not-a-valid-refresh-token'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    private function createRefreshTokenForUser(): string
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $user = new User();
        $user->setEmail('refresh@example.com');
        $user->setGoogleId('google-refresh-id');
        $user->setName('Refresh User');
        $em->persist($user);
        $em->flush();

        $generator = self::getContainer()->get(RefreshTokenGeneratorInterface::class);
        $manager = self::getContainer()->get(RefreshTokenManagerInterface::class);

        $refreshToken = $generator->createForUserWithTtl($user, 2592000);
        $manager->save($refreshToken);

        return $refreshToken->getRefreshToken();
    }
}

<?php

namespace App\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
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
        $this->loadFixtures('EventApiTest.yaml');
        $this->authenticateAsTestUser();

        $this->client->request('GET', '/api/events', [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful();
    }
}

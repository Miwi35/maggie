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

    public function testRefreshTokenReturnsFreshMercureToken(): void
    {
        $refreshToken = $this->createRefreshTokenForUser('mercure-refresh@example.com');

        $this->client->request('POST', '/api/token/refresh', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['refresh_token' => $refreshToken], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('mercureToken', $data, 'a refresh that does not renew the Mercure token silences real-time once the login one expires');

        $claims = json_decode((string) base64_decode(strtr(explode('.', $data['mercureToken'])[1], '-_', '+/')), true, 512, JSON_THROW_ON_ERROR);
        $user = self::getContainer()->get('doctrine.orm.entity_manager')
            ->getRepository(User::class)->findOneBy(['email' => 'mercure-refresh@example.com']);
        self::assertSame((string) $user->getId(), $claims['sub']);
        self::assertGreaterThan(time() + 3600, $claims['exp']);
        self::assertContains('/users/'.$user->getId().'/*', array_column($claims['authorization_details'][0]['topics'], 'match'));
    }

    public function testRefreshTokenWithInvalidTokenReturns401(): void
    {
        $this->client->request('POST', '/api/token/refresh', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], json_encode(['refresh_token' => 'not-a-valid-refresh-token'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testRefreshTokenReadFromTheCookieRenewsTheSession(): void
    {
        $refreshToken = $this->createRefreshTokenForUser();
        $this->client->getCookieJar()->set(new \Symfony\Component\BrowserKit\Cookie('refresh_token', $refreshToken, null, '/api/token'));

        // No body: the web admin cannot read the httpOnly cookie, so it sends none.
        $this->client->request('POST', '/api/token/refresh', [], [], [
            'CONTENT_TYPE' => 'application/json',
        ], '{}');

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertNotEmpty($data['token']);

        $cookies = $this->responseCookies();

        // The refresh token is rotated into a new httpOnly cookie that only the token routes receive.
        self::assertArrayHasKey('refresh_token', $cookies);
        self::assertNotSame($refreshToken, $cookies['refresh_token']->getValue());
        self::assertSame($data['refresh_token'], $cookies['refresh_token']->getValue());
        self::assertTrue($cookies['refresh_token']->isHttpOnly());
        self::assertSame('/api/token', $cookies['refresh_token']->getPath());
        self::assertSame('strict', $cookies['refresh_token']->getSameSite());

        // The Mercure subscriber cookie is renewed with the access token, so real time does not outlive the session.
        self::assertArrayHasKey('mercureAuthorization', $cookies);
        self::assertSame('/.well-known/mercure', $cookies['mercureAuthorization']->getPath());
        self::assertTrue($cookies['mercureAuthorization']->isHttpOnly());
    }

    public function testConsumedRefreshTokenCannotBeReplayed(): void
    {
        $refreshToken = $this->createRefreshTokenForUser();

        for ($attempt = 0; $attempt < 2; ++$attempt) {
            $this->client->getCookieJar()->clear();
            $this->client->getCookieJar()->set(new \Symfony\Component\BrowserKit\Cookie('refresh_token', $refreshToken, null, '/api/token'));
            $this->client->request('POST', '/api/token/refresh', [], [], ['CONTENT_TYPE' => 'application/json'], '{}');
        }

        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidateDeletesTheRefreshTokenAndClearsTheCookie(): void
    {
        $refreshToken = $this->createRefreshTokenForUser();
        $this->client->getCookieJar()->set(new \Symfony\Component\BrowserKit\Cookie('refresh_token', $refreshToken, null, '/api/token'));

        $this->client->request('POST', '/api/token/invalidate');

        self::assertResponseIsSuccessful();
        self::assertNull(
            self::getContainer()->get(RefreshTokenManagerInterface::class)->get($refreshToken),
            'Signing out must delete the refresh token server-side, not only forget it in the browser.',
        );

        $cleared = $this->responseCookies()['refresh_token'] ?? null;
        self::assertNotNull($cleared);
        self::assertTrue($cleared->isCleared());
        self::assertSame('/api/token', $cleared->getPath());

        // The deleted token no longer renews anything.
        $this->client->getCookieJar()->set(new \Symfony\Component\BrowserKit\Cookie('refresh_token', $refreshToken, null, '/api/token'));
        $this->client->request('POST', '/api/token/refresh', [], [], ['CONTENT_TYPE' => 'application/json'], '{}');
        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidateWithoutATokenReturns400(): void
    {
        $this->client->request('POST', '/api/token/invalidate');

        self::assertResponseStatusCodeSame(400);
    }

    /**
     * @return array<string, \Symfony\Component\HttpFoundation\Cookie>
     */
    private function responseCookies(): array
    {
        $cookies = [];
        foreach ($this->client->getResponse()->headers->getCookies() as $cookie) {
            $cookies[$cookie->getName()] = $cookie;
        }

        return $cookies;
    }

    private function createRefreshTokenForUser(string $email = 'refresh@example.com'): string
    {
        $this->purgeDatabase();
        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        $user = new User();
        $user->setEmail($email);
        $user->setGoogleId('google-'.$email);
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

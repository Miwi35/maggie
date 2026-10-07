<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\Controller;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Gesdinet\JWTRefreshTokenBundle\Generator\RefreshTokenGeneratorInterface;
use Gesdinet\JWTRefreshTokenBundle\Model\RefreshTokenManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Controller\GoogleAuthController;
use Maggie\Core\Entity\RefreshToken;
use Maggie\Core\Mercure\MercureAccessToken;
use Maggie\Core\Mercure\MercureSubscriberTokenFactory;
use Maggie\Core\Repository\UserRepository;
use Maggie\Core\Security\RefreshTokenCookieFactory;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpClient\MockHttpClient;
use Symfony\Component\HttpClient\Response\MockResponse;
use Symfony\Component\HttpFoundation\Cookie;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\Messenger\MessageBus;

/**
 * The web sign-in: what the browser is handed when Google sends the user back.
 *
 * Google itself is replaced by a mock HTTP client; everything else is the real
 * service graph, and what is asserted is observable: the redirect, the cookies
 * and the rows left in the database.
 */
final class GoogleAuthControllerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->purgeDatabase();
    }

    public function testCallbackHandsTheBrowserAnHttpOnlyRefreshCookieBackedByAStoredToken(): void
    {
        $response = $this->controller()->callback(Request::create('/api/auth/google/callback', 'GET', ['code' => 'a-code']));

        self::assertSame(302, $response->getStatusCode());
        self::assertStringStartsWith('https://admin.test?token=', (string) $response->headers->get('Location'));

        $cookies = [];
        foreach ($response->headers->getCookies() as $cookie) {
            $cookies[$cookie->getName()] = $cookie;
        }

        self::assertSame(['mercureAuthorization', 'refresh_token'], array_keys($cookies));

        /** @var Cookie $refresh */
        $refresh = $cookies['refresh_token'];
        self::assertTrue($refresh->isHttpOnly());
        self::assertTrue($refresh->isSecure());
        self::assertSame('/api/token', $refresh->getPath());
        self::assertSame('strict', $refresh->getSameSite());
        self::assertGreaterThan(time() + 86400, $refresh->getExpiresTime(), 'The cookie must outlive the 24 h access token.');

        $stored = $this->entityManager()->getRepository(RefreshToken::class)->findOneBy(['refreshToken' => $refresh->getValue()]);
        self::assertNotNull($stored, 'The cookie must carry a token the API can actually redeem.');
        self::assertSame('google-callback@example.com', $stored->getUsername());
    }

    public function testRefreshTokenIsNeverPutInTheRedirectUrl(): void
    {
        $response = $this->controller()->callback(Request::create('/api/auth/google/callback', 'GET', ['code' => 'a-code']));

        self::assertStringNotContainsString('refresh', (string) $response->headers->get('Location'));
    }

    public function testFailedExchangeLeavesNoCookieAndNoToken(): void
    {
        $response = $this->controller(failExchange: true)->callback(Request::create('/api/auth/google/callback', 'GET', ['code' => 'a-code']));

        self::assertSame('https://admin.test?auth_error=token_exchange_failed', $response->headers->get('Location'));
        self::assertSame([], $response->headers->getCookies());
        self::assertSame(0, $this->entityManager()->getRepository(RefreshToken::class)->count([]));
    }

    private function controller(bool $failExchange = false): GoogleAuthController
    {
        $container = self::getContainer();

        $idToken = 'the-google-id-token';
        $httpClient = new MockHttpClient(static function (string $method, string $url) use ($failExchange, $idToken): MockResponse {
            if (str_ends_with($url, '/token')) {
                return $failExchange
                    ? new MockResponse('{}', ['http_code' => 400])
                    : new MockResponse(json_encode(['id_token' => $idToken, 'access_token' => 'at', 'expires_in' => 3600], JSON_THROW_ON_ERROR));
            }

            return new MockResponse(json_encode([
                'aud' => 'client-id',
                'sub' => 'google-callback-sub',
                'email' => 'google-callback@example.com',
                'name' => 'Callback User',
            ], JSON_THROW_ON_ERROR));
        });

        return new GoogleAuthController(
            $httpClient,
            $container->get(UserRepository::class),
            $this->entityManager(),
            $container->get(JWTTokenManagerInterface::class),
            new MessageBus(),
            $container->get(RefreshTokenGeneratorInterface::class),
            $container->get(RefreshTokenManagerInterface::class),
            new MercureSubscriberTokenFactory(new MercureAccessToken('a-mercure-secret-of-at-least-32-bytes', 'http://localhost/.well-known/mercure')),
            new RefreshTokenCookieFactory(
                ['path' => '/api/token', 'same_site' => 'strict', 'http_only' => true, 'secure' => true],
                2592000,
                'refresh_token',
            ),
            'client-id',
            'client-secret',
            'https://api.test/api/auth/google/callback',
            'https://admin.test',
            2592000,
        );
    }

    private function entityManager(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}

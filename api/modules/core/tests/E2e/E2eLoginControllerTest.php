<?php

declare(strict_types=1);

namespace Maggie\Core\Tests\E2e;

use App\Tests\Support\FixtureLoaderTrait;
use Gesdinet\JWTRefreshTokenBundle\Generator\RefreshTokenGeneratorInterface;
use Gesdinet\JWTRefreshTokenBundle\Model\RefreshTokenManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\E2e\Controller\E2eLoginController;
use Maggie\Core\Entity\RefreshToken;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureSubscriberTokenFactory;
use Maggie\Core\Repository\UserRepository;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;

/**
 * Behaviour of the e2e test login.
 *
 * The controller is deliberately absent from the `test` container — that
 * absence is the point of E2eSurfaceAbsenceTest — so these cases build it
 * by hand from real collaborators pulled out of the container, and drive
 * `__invoke` directly. Everything asserted here is observable state: status
 * codes, the decoded JWT, rows in the database.
 */
final class E2eLoginControllerTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private const TOKEN = 'the-e2e-login-token';

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('user.yaml');
    }

    public function testMissingTokenReturns401(): void
    {
        $response = $this->controller()(
            $this->request(['email' => 'e2e@maggie.local'], token: null)
        );

        self::assertSame(Response::HTTP_UNAUTHORIZED, $response->getStatusCode());
        self::assertSame(0, $this->countRefreshTokens(), 'A rejected login must not leave a refresh token behind.');
    }

    public function testWrongTokenReturns401(): void
    {
        $response = $this->controller()(
            $this->request(['email' => 'e2e@maggie.local'], token: 'not-the-token')
        );

        self::assertSame(Response::HTTP_UNAUTHORIZED, $response->getStatusCode());
        self::assertSame(0, $this->countRefreshTokens());
    }

    public function testEmptyConfiguredTokenRejectsEvenAMatchingHeader(): void
    {
        // An unset E2E_LOGIN_TOKEN must fail closed. Failing open would turn a
        // misconfigured stack into an unauthenticated login for any account.
        $response = $this->controller(configuredToken: '')(
            $this->request(['email' => 'e2e@maggie.local'], token: '')
        );

        self::assertSame(Response::HTTP_UNAUTHORIZED, $response->getStatusCode());
    }

    public function testMissingEmailReturns400(): void
    {
        $response = $this->controller()($this->request([]));

        self::assertSame(Response::HTTP_BAD_REQUEST, $response->getStatusCode());
    }

    public function testMalformedBodyReturns400(): void
    {
        $response = $this->controller()($this->request(rawBody: 'not json'));

        self::assertSame(Response::HTTP_BAD_REQUEST, $response->getStatusCode());
    }

    public function testUnknownEmailReturns404(): void
    {
        $response = $this->controller()($this->request(['email' => 'nobody@maggie.local']));

        self::assertSame(Response::HTTP_NOT_FOUND, $response->getStatusCode());
        self::assertSame(
            0,
            $this->entityManager()->getRepository(User::class)->count(['email' => 'nobody@maggie.local']),
            'The test login must never create the user it failed to find.',
        );
    }

    public function testOutsideE2eItIsNotFound(): void
    {
        $this->expectException(NotFoundHttpException::class);

        $this->controller(environment: 'prod')($this->request(['email' => 'e2e@maggie.local']));
    }

    public function testHappyPathReturnsAUsableTokenSet(): void
    {
        /** @var User $user */
        $user = $this->getFixture('test_user');

        $response = $this->controller()($this->request(['email' => 'e2e@maggie.local']));

        self::assertSame(Response::HTTP_OK, $response->getStatusCode());

        $payload = json_decode((string) $response->getContent(), true, 512, JSON_THROW_ON_ERROR);

        self::assertNotEmpty($payload['token']);
        self::assertNotEmpty($payload['refreshToken']);
        self::assertNotEmpty($payload['mercureToken']);
        self::assertSame((string) $user->getId(), $payload['user']['id']);
        self::assertSame('e2e@maggie.local', $payload['user']['email']);
        self::assertSame('E2E User', $payload['user']['name']);

        // The JWT identifies the seeded user, which is what every later
        // request in a journey depends on.
        $jwtManager = self::getContainer()->get(JWTTokenManagerInterface::class);
        self::assertSame('e2e@maggie.local', $jwtManager->parse($payload['token'])['username'] ?? null);

        // The refresh token is persisted, so a journey can outlive the JWT TTL.
        $stored = $this->entityManager()->getRepository(RefreshToken::class)
            ->findOneBy(['refreshToken' => $payload['refreshToken']]);
        self::assertNotNull($stored);
        self::assertSame('e2e@maggie.local', $stored->getUsername());
    }

    public function testMercureTokenSubscribesToTheUsersOwnTopicsOnly(): void
    {
        /** @var User $user */
        $user = $this->getFixture('test_user');

        $response = $this->controller()($this->request(['email' => 'e2e@maggie.local']));
        $payload = json_decode((string) $response->getContent(), true, 512, JSON_THROW_ON_ERROR);

        $claims = json_decode(
            (string) base64_decode(strtr(explode('.', $payload['mercureToken'])[1], '-_', '+/'), true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertSame(
            ['/users/'.$user->getId().'/{+topic}'],
            array_slice($claims['mercure']['subscribe'], 0, 1),
        );
        foreach ($claims['mercure']['subscribe'] as $selector) {
            self::assertStringContainsString((string) $user->getId(), $selector);
        }
    }

    public function testCookieIsNotSecureOverPlainHttp(): void
    {
        // The e2e stack serves plain HTTP; a Secure cookie would be dropped by
        // the browser and every real-time assertion in a journey would fail.
        $response = $this->controller()($this->request(['email' => 'e2e@maggie.local']));

        $cookies = $response->headers->getCookies();
        self::assertCount(1, $cookies);
        self::assertSame('mercureAuthorization', $cookies[0]->getName());
        self::assertFalse($cookies[0]->isSecure());
    }

    private function controller(string $environment = 'e2e', string $configuredToken = self::TOKEN): E2eLoginController
    {
        $container = self::getContainer();

        return new E2eLoginController(
            $container->get(UserRepository::class),
            $container->get(JWTTokenManagerInterface::class),
            $container->get(RefreshTokenGeneratorInterface::class),
            $container->get(RefreshTokenManagerInterface::class),
            new MercureSubscriberTokenFactory('a-mercure-secret'),
            $environment,
            $configuredToken,
            2592000,
        );
    }

    /** @param array<string, mixed>|null $body */
    private function request(?array $body = null, ?string $token = self::TOKEN, ?string $rawBody = null): Request
    {
        $headers = null === $token ? [] : ['HTTP_X_E2E_TOKEN' => $token];

        return Request::create(
            '/api/auth/e2e/login',
            'POST',
            [],
            [],
            [],
            $headers,
            $rawBody ?? json_encode($body ?? [], JSON_THROW_ON_ERROR),
        );
    }

    private function countRefreshTokens(): int
    {
        return $this->entityManager()->getRepository(RefreshToken::class)->count([]);
    }

    private function entityManager(): \Doctrine\ORM\EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}

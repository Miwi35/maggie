<?php

namespace Maggie\Core\Tests\Mcp;

use Lexik\Bundle\JWTAuthenticationBundle\Encoder\JWTEncoderInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Exception\JWTDecodeFailureException;
use Maggie\Core\Entity\User;
use Maggie\Core\Mcp\McpAccessListener;
use Maggie\Core\Repository\UserRepository;
use PHPUnit\Framework\TestCase;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\HttpKernelInterface;
use Symfony\Component\Security\Core\Authentication\Token\Storage\TokenStorage;
use Symfony\Component\Uid\Ulid;

class McpAccessListenerTest extends TestCase
{
    private const SERVICE_TOKEN = 'service-token';

    private TokenStorage $tokenStorage;
    private UserRepository $userRepository;
    private JWTEncoderInterface $jwtEncoder;
    private User $user;

    protected function setUp(): void
    {
        $this->tokenStorage = new TokenStorage();
        $this->userRepository = $this->createMock(UserRepository::class);
        $this->jwtEncoder = $this->createMock(JWTEncoderInterface::class);

        $this->user = new User();
        $this->user->setEmail('test@example.com');
        $this->user->setGoogleId('google-test-id');
        $this->user->setName('Test User');
    }

    public function testRequestWithoutTokenIsRejected(): void
    {
        $event = $this->dispatch('/_mcp');

        self::assertNotNull($event->getResponse());
        self::assertSame(Response::HTTP_UNAUTHORIZED, $event->getResponse()->getStatusCode());
        self::assertNull($this->tokenStorage->getToken());
    }

    public function testRequestWithUnknownTokenIsRejected(): void
    {
        $this->jwtEncoder->method('decode')->willThrowException(new JWTDecodeFailureException('invalid', 'Invalid JWT'));

        $event = $this->dispatch('/_mcp', ['HTTP_AUTHORIZATION' => 'Bearer nope']);

        self::assertNotNull($event->getResponse());
        self::assertSame(Response::HTTP_UNAUTHORIZED, $event->getResponse()->getStatusCode());
    }

    public function testOtherPathsAreUntouched(): void
    {
        $event = $this->dispatch('/api/accounts');

        self::assertNull($event->getResponse());
    }

    public function testServiceTokenAloneAuthenticatesWithoutUser(): void
    {
        $event = $this->dispatch('/_mcp', ['HTTP_AUTHORIZATION' => 'Bearer ' . self::SERVICE_TOKEN]);

        self::assertNull($event->getResponse());
        self::assertNull($this->tokenStorage->getToken());
    }

    public function testServiceTokenBindsTheImpersonatedUser(): void
    {
        $id = new Ulid();
        $this->userRepository->method('find')->willReturn($this->user);

        $event = $this->dispatch('/_mcp', [
            'HTTP_AUTHORIZATION' => 'Bearer ' . self::SERVICE_TOKEN,
            'HTTP_X_MAGGIE_USER_ID' => (string) $id,
        ]);

        self::assertNull($event->getResponse());
        self::assertSame($this->user, $this->tokenStorage->getToken()?->getUser());
    }

    public function testJwtSubjectBindsTheUser(): void
    {
        $id = new Ulid();
        $this->jwtEncoder->method('decode')->willReturn(['sub' => (string) $id]);
        $this->userRepository->method('find')->willReturn($this->user);

        $event = $this->dispatch('/_mcp', ['HTTP_AUTHORIZATION' => 'Bearer a.jwt.token']);

        self::assertNull($event->getResponse());
        self::assertSame($this->user, $this->tokenStorage->getToken()?->getUser());
    }

    public function testJwtForUnknownUserIsRejected(): void
    {
        $this->jwtEncoder->method('decode')->willReturn(['sub' => (string) new Ulid()]);
        $this->userRepository->method('find')->willReturn(null);

        $event = $this->dispatch('/_mcp', ['HTTP_AUTHORIZATION' => 'Bearer a.jwt.token']);

        self::assertNotNull($event->getResponse());
        self::assertSame(Response::HTTP_UNAUTHORIZED, $event->getResponse()->getStatusCode());
    }

    /**
     * @param array<string, string> $server
     */
    private function dispatch(string $path, array $server = []): RequestEvent
    {
        $listener = new McpAccessListener(
            $this->tokenStorage,
            $this->userRepository,
            $this->jwtEncoder,
            self::SERVICE_TOKEN,
        );

        $event = new RequestEvent(
            $this->createMock(HttpKernelInterface::class),
            Request::create($path, 'POST', [], [], [], $server),
            HttpKernelInterface::MAIN_REQUEST,
        );

        $listener($event);

        return $event;
    }
}

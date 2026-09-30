<?php

declare(strict_types=1);

namespace Maggie\Core\Mcp;

use Lexik\Bundle\JWTAuthenticationBundle\Encoder\JWTEncoderInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Exception\JWTDecodeFailureException;
use Maggie\Core\Entity\User;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\EventDispatcher\Attribute\AsEventListener;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Event\RequestEvent;
use Symfony\Component\HttpKernel\KernelEvents;
use Symfony\Component\Security\Core\Authentication\Token\PreAuthenticatedToken;
use Symfony\Component\Security\Core\Authentication\Token\Storage\TokenStorageInterface;
use Symfony\Component\Uid\Ulid;

/**
 * Guards the MCP endpoint, which sits outside the API firewall.
 *
 * Two credentials are accepted on the Authorization header:
 * - a user JWT — the tools then act for the token subject;
 * - the service token — reserved for internal callers (the agent hub), which
 *   name the user they act for with the X-Maggie-User-Id header.
 *
 * The service token alone authenticates the caller without binding a user, so
 * `initialize` and `tools/list` keep working at agent startup; tools needing a
 * user fail explicitly through McpUserContext::requireUser().
 */
#[AsEventListener(event: KernelEvents::REQUEST, priority: 7)]
final class McpAccessListener
{
    public const USER_HEADER = 'X-Maggie-User-Id';

    public function __construct(
        private readonly TokenStorageInterface $tokenStorage,
        private readonly UserRepository $userRepository,
        private readonly JWTEncoderInterface $jwtEncoder,
        private readonly string $serviceToken,
    ) {
    }

    public function __invoke(RequestEvent $event): void
    {
        if (!$event->isMainRequest()) {
            return;
        }

        $request = $event->getRequest();

        if (!str_starts_with($request->getPathInfo(), '/_mcp')) {
            return;
        }

        $credentials = $this->extractBearer($request);

        if (null === $credentials) {
            $event->setResponse($this->unauthorized('Missing bearer token.'));

            return;
        }

        if ($this->isServiceToken($credentials)) {
            $user = $this->resolveImpersonatedUser($request);

            if (null !== $user) {
                $this->authenticate($user);
            }

            return;
        }

        $user = $this->resolveJwtUser($credentials);

        if (null === $user) {
            $event->setResponse($this->unauthorized('Invalid bearer token.'));

            return;
        }

        $this->authenticate($user);
    }

    private function extractBearer(Request $request): ?string
    {
        $header = $request->headers->get('Authorization', '');

        if (!str_starts_with($header, 'Bearer ')) {
            return null;
        }

        $token = trim(substr($header, 7));

        return '' === $token ? null : $token;
    }

    private function isServiceToken(string $credentials): bool
    {
        return '' !== $this->serviceToken && hash_equals($this->serviceToken, $credentials);
    }

    private function resolveImpersonatedUser(Request $request): ?User
    {
        $id = $this->toUlid($request->headers->get(self::USER_HEADER));

        return null !== $id ? $this->userRepository->find($id) : null;
    }

    private function resolveJwtUser(string $credentials): ?User
    {
        try {
            $payload = $this->jwtEncoder->decode($credentials);
        } catch (JWTDecodeFailureException) {
            return null;
        }

        $subject = $this->toUlid($payload['sub'] ?? null);

        if (null !== $subject) {
            return $this->userRepository->find($subject);
        }

        $username = $payload['username'] ?? null;

        return is_string($username) ? $this->userRepository->findOneBy(['email' => $username]) : null;
    }

    /** Accepts both ULID spellings: base32 (26 chars) and RFC 4122. */
    private function toUlid(mixed $value): ?Ulid
    {
        if (!is_string($value) || '' === $value) {
            return null;
        }

        try {
            return Ulid::fromString($value);
        } catch (\InvalidArgumentException) {
            return null;
        }
    }

    private function authenticate(User $user): void
    {
        $this->tokenStorage->setToken(new PreAuthenticatedToken($user, 'mcp', $user->getRoles()));
    }

    private function unauthorized(string $message): JsonResponse
    {
        return new JsonResponse([
            'jsonrpc' => '2.0',
            'id' => null,
            'error' => [
                'code' => -32001,
                'message' => $message,
            ],
        ], Response::HTTP_UNAUTHORIZED);
    }
}

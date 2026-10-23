<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Controller;

use Gesdinet\JWTRefreshTokenBundle\Generator\RefreshTokenGeneratorInterface;
use Gesdinet\JWTRefreshTokenBundle\Model\RefreshTokenManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureSubscriberTokenFactory;
use Maggie\Core\Security\RefreshTokenCookieFactory;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;

/** What signing a user in answers: the Google callback's payload and cookies, for an account the e2e doors name. */
final class E2eSessionFactory
{
    public function __construct(
        private readonly JWTTokenManagerInterface $jwtManager,
        private readonly RefreshTokenGeneratorInterface $refreshTokenGenerator,
        private readonly RefreshTokenManagerInterface $refreshTokenManager,
        private readonly MercureSubscriberTokenFactory $mercureSubscriberTokenFactory,
        private readonly RefreshTokenCookieFactory $refreshTokenCookieFactory,
        private readonly int $refreshTokenTtl,
    ) {
    }

    /**
     * @param array<string, mixed> $extra more keys for the body, after the session's own
     */
    public function open(User $user, Request $request, array $extra = []): JsonResponse
    {
        $refreshToken = $this->refreshTokenGenerator->createForUserWithTtl($user, $this->refreshTokenTtl);
        $this->refreshTokenManager->save($refreshToken);

        $response = new JsonResponse([
            'token' => $this->jwtManager->create($user),
            'refreshToken' => $refreshToken->getRefreshToken(),
            'mercureToken' => $this->mercureSubscriberTokenFactory->createForUser($user),
            'user' => [
                'id' => (string) $user->getId(),
                'email' => $user->getEmail(),
                'name' => $user->getName(),
                'avatar' => $user->getAvatar(),
            ],
            ...$extra,
        ]);

        // Same shape and same cookie as the Google callback, so the admin takes
        // no e2e-only branch. Secure follows the request: the e2e stack is
        // plain HTTP and a Secure cookie would be dropped.
        $response->headers->setCookie(
            $this->mercureSubscriberTokenFactory->createCookieForUser($user, $request->isSecure())
        );
        $response->headers->setCookie($this->refreshTokenCookieFactory->create($refreshToken->getRefreshToken()));

        return $response;
    }
}

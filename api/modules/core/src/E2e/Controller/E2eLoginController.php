<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\Controller;

use Gesdinet\JWTRefreshTokenBundle\Generator\RefreshTokenGeneratorInterface;
use Gesdinet\JWTRefreshTokenBundle\Model\RefreshTokenManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Mercure\MercureSubscriberTokenFactory;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\HttpKernel\Exception\NotFoundHttpException;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Signs a seeded user in without Google.
 *
 * Google's OAuth consent screen cannot be driven by Playwright or Maestro, so
 * the e2e journeys need a door of their own. That door is dangerous by nature,
 * so it is shut three times over:
 *
 * 1. the service is only registered under `when@e2e`, and routes come from
 *    registered controller services (`resource: routing.controllers`), so in
 *    any other environment the route does not exist at all;
 * 2. this method still refuses to run outside `e2e`, which survives someone
 *    importing the service definition by mistake;
 * 3. the caller must present E2E_LOGIN_TOKEN, so a stack booted in `e2e` on a
 *    reachable host is not an open door to every account.
 *
 * Guarantee 1 is what E2eSurfaceAbsenceTest asserts, per MAG-94.
 */
final class E2eLoginController
{
    public function __construct(
        private readonly UserRepository $userRepository,
        private readonly JWTTokenManagerInterface $jwtManager,
        private readonly RefreshTokenGeneratorInterface $refreshTokenGenerator,
        private readonly RefreshTokenManagerInterface $refreshTokenManager,
        private readonly MercureSubscriberTokenFactory $mercureSubscriberTokenFactory,
        private readonly string $environment,
        private readonly string $e2eLoginToken,
        private readonly int $refreshTokenTtl,
    ) {
    }

    #[Route('/api/auth/e2e/login', name: 'auth_e2e_login', methods: ['POST'])]
    public function __invoke(Request $request): JsonResponse
    {
        if ($this->environment !== 'e2e') {
            throw new NotFoundHttpException();
        }

        if ($this->e2eLoginToken === '' || !hash_equals($this->e2eLoginToken, (string) $request->headers->get('X-E2E-Token'))) {
            return new JsonResponse(['error' => 'Invalid e2e token'], Response::HTTP_UNAUTHORIZED);
        }

        $data = json_decode((string) $request->getContent(), true);
        $email = \is_array($data) ? ($data['email'] ?? null) : null;

        if (!\is_string($email) || $email === '') {
            return new JsonResponse(['error' => 'Missing email'], Response::HTTP_BAD_REQUEST);
        }

        $user = $this->userRepository->findOneBy(['email' => $email]);

        if (!$user) {
            // Deliberately not "find or create": a journey that logs in as a
            // user the seed never made is a broken journey, and inventing one
            // would hide that behind a green step.
            return new JsonResponse(['error' => 'Unknown user'], Response::HTTP_NOT_FOUND);
        }

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
        ]);

        // Same shape and same cookie as the Google callback, so the admin takes
        // no e2e-only branch. Secure follows the request: the e2e stack is
        // plain HTTP and a Secure cookie would be dropped.
        $response->headers->setCookie(
            $this->mercureSubscriberTokenFactory->createCookieForUser($user, $request->isSecure())
        );

        return $response;
    }
}

<?php

namespace Maggie\Core\Controller;

use Doctrine\ORM\EntityManagerInterface;
use Gesdinet\JWTRefreshTokenBundle\Generator\RefreshTokenGeneratorInterface;
use Gesdinet\JWTRefreshTokenBundle\Model\RefreshTokenManagerInterface;
use Lexik\Bundle\JWTAuthenticationBundle\Services\JWTTokenManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\MercureSubscriberTokenFactory;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\RedirectResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Contracts\HttpClient\HttpClientInterface;

final class GoogleAuthController
{
    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly JWTTokenManagerInterface $jwtManager,
        private readonly MessageBusInterface $messageBus,
        private readonly RefreshTokenGeneratorInterface $refreshTokenGenerator,
        private readonly RefreshTokenManagerInterface $refreshTokenManager,
        private readonly MercureSubscriberTokenFactory $mercureSubscriberTokenFactory,
        private readonly string $googleClientId,
        private readonly string $googleClientSecret,
        private readonly string $googleRedirectUri,
        private readonly string $adminUrl,
        private readonly int $refreshTokenTtl,
    ) {
    }

    private function adminRedirect(string $query = ''): RedirectResponse
    {
        $url = rtrim($this->adminUrl, '/');
        if ($query) {
            $url .= '?' . $query;
        }

        return new RedirectResponse($url);
    }

    /**
     * Mobile flow: exchange Google ID token for JWT.
     */
    #[Route('/api/auth/google', name: 'auth_google', methods: ['POST'])]
    public function token(Request $request): JsonResponse
    {
        $data = json_decode($request->getContent(), true);
        $idToken = $data['idToken'] ?? null;

        if (!$idToken) {
            return new JsonResponse(['error' => 'Missing idToken'], Response::HTTP_BAD_REQUEST);
        }

        $payload = $this->validateGoogleToken($idToken);

        if (!$payload) {
            return new JsonResponse(['error' => 'Invalid Google token'], Response::HTTP_UNAUTHORIZED);
        }

        if ($payload['aud'] !== $this->googleClientId) {
            return new JsonResponse(['error' => 'Invalid audience'], Response::HTTP_UNAUTHORIZED);
        }

        $user = $this->findOrCreateUser($payload);
        $jwt = $this->jwtManager->create($user);
        $refreshToken = $this->refreshTokenGenerator->createForUserWithTtl($user, $this->refreshTokenTtl);
        $this->refreshTokenManager->save($refreshToken);

        return new JsonResponse([
            'token' => $jwt,
            'refreshToken' => $refreshToken->getRefreshToken(),
            'mercureToken' => $this->mercureSubscriberTokenFactory->createForUser($user),
            'user' => [
                'id' => (string) $user->getId(),
                'email' => $user->getEmail(),
                'name' => $user->getName(),
                'avatar' => $user->getAvatar(),
            ],
        ]);
    }

    /**
     * Web flow step 1: redirect to Google OAuth.
     */
    #[Route('/api/auth/google/redirect', name: 'auth_google_redirect', methods: ['GET'])]
    public function redirect(): RedirectResponse
    {
        $params = http_build_query([
            'client_id' => $this->googleClientId,
            'redirect_uri' => $this->googleRedirectUri,
            'response_type' => 'code',
            'scope' => 'openid email profile https://www.googleapis.com/auth/calendar https://www.googleapis.com/auth/tasks',
            'access_type' => 'offline',
            'prompt' => 'consent',
        ]);

        return new RedirectResponse('https://accounts.google.com/o/oauth2/v2/auth?' . $params);
    }

    /**
     * Web flow step 2: handle Google callback, exchange code for JWT.
     */
    #[Route('/api/auth/google/callback', name: 'auth_google_callback', methods: ['GET'])]
    public function callback(Request $request): RedirectResponse
    {
        $code = $request->query->get('code');
        $error = $request->query->get('error');

        if ($error || !$code) {
            return $this->adminRedirect('auth_error=' . ($error ?? 'missing_code'));
        }

        // Exchange authorization code for tokens
        $tokenResponse = $this->httpClient->request('POST', 'https://oauth2.googleapis.com/token', [
            'body' => [
                'code' => $code,
                'client_id' => $this->googleClientId,
                'client_secret' => $this->googleClientSecret,
                'redirect_uri' => $this->googleRedirectUri,
                'grant_type' => 'authorization_code',
            ],
        ]);

        if ($tokenResponse->getStatusCode() !== 200) {
            return $this->adminRedirect('auth_error=token_exchange_failed');
        }

        $tokens = $tokenResponse->toArray();
        $idToken = $tokens['id_token'] ?? null;

        if (!$idToken) {
            return $this->adminRedirect('auth_error=no_id_token');
        }

        $payload = $this->validateGoogleToken($idToken);

        if (!$payload || $payload['aud'] !== $this->googleClientId) {
            return $this->adminRedirect('auth_error=invalid_token');
        }

        $user = $this->findOrCreateUser($payload);

        // Store Google OAuth tokens for Calendar API access
        if (isset($tokens['access_token'])) {
            $user->setGoogleAccessToken($tokens['access_token']);
        }
        if (isset($tokens['refresh_token'])) {
            $user->setGoogleRefreshToken($tokens['refresh_token']);
        }
        if (isset($tokens['expires_in'])) {
            $user->setGoogleTokenExpiresAt(
                new \DateTimeImmutable('+' . $tokens['expires_in'] . ' seconds')
            );
        }
        $this->entityManager->flush();

        // Auto-import Google Tasks (async — will auto-detect the default list)
        $this->messageBus->dispatch(new \Maggie\Calendar\Message\PullTasksFromGoogleCommand(
            userId: (string) $user->getId(),
        ));

        $jwt = $this->jwtManager->create($user);

        $params = http_build_query([
            'token' => $jwt,
            'user' => json_encode([
                'id' => (string) $user->getId(),
                'email' => $user->getEmail(),
                'name' => $user->getName(),
                'avatar' => $user->getAvatar(),
            ]),
        ]);

        $response = $this->adminRedirect($params);
        $response->headers->setCookie($this->mercureSubscriberTokenFactory->createCookieForUser($user));

        return $response;
    }

    /**
     * @return array<string, mixed>|null
     */
    private function validateGoogleToken(string $idToken): ?array
    {
        $response = $this->httpClient->request('GET', 'https://oauth2.googleapis.com/tokeninfo', [
            'query' => ['id_token' => $idToken],
        ]);

        if ($response->getStatusCode() !== 200) {
            return null;
        }

        return $response->toArray();
    }

    /**
     * @param array<string, mixed> $payload
     */
    private function findOrCreateUser(array $payload): User
    {
        $googleId = $payload['sub'];
        $user = $this->userRepository->findByGoogleId($googleId);

        if (!$user) {
            $user = new User();
            $user->setGoogleId($googleId);
            $user->setEmail($payload['email']);
            $this->entityManager->persist($user);
        }

        $user->setName($payload['name'] ?? $payload['email']);
        $user->setAvatar($payload['picture'] ?? null);

        $this->entityManager->flush();

        return $user;
    }

}

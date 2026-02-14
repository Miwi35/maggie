<?php

namespace Maggie\Calendar\Controller;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Repository\UserRepository;
use Symfony\Component\HttpFoundation\RedirectResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Contracts\HttpClient\HttpClientInterface;

final class GoogleCalendarAuthController
{
    public function __construct(
        private readonly HttpClientInterface $httpClient,
        private readonly UserRepository $userRepository,
        private readonly EntityManagerInterface $entityManager,
        private readonly string $googleClientId,
        private readonly string $googleClientSecret,
        private readonly string $googleRedirectUri,
        private readonly string $adminUrl,
    ) {
    }

    #[Route('/api/auth/google-calendar/redirect', name: 'auth_google_calendar_redirect', methods: ['GET'])]
    public function redirect(Request $request): RedirectResponse
    {
        $userId = $request->query->get('user_id');
        $state = base64_encode(json_encode(['user_id' => $userId, 'flow' => 'calendar']));

        $callbackUri = str_replace(
            '/api/auth/google/callback',
            '/api/auth/google-calendar/callback',
            $this->googleRedirectUri
        );

        $params = http_build_query([
            'client_id' => $this->googleClientId,
            'redirect_uri' => $callbackUri,
            'response_type' => 'code',
            'scope' => 'https://www.googleapis.com/auth/calendar',
            'access_type' => 'offline',
            'prompt' => 'consent',
            'state' => $state,
        ]);

        return new RedirectResponse('https://accounts.google.com/o/oauth2/v2/auth?' . $params);
    }

    #[Route('/api/auth/google-calendar/callback', name: 'auth_google_calendar_callback', methods: ['GET'])]
    public function callback(Request $request): RedirectResponse
    {
        $adminUrl = rtrim($this->adminUrl, '/');
        $code = $request->query->get('code');
        $error = $request->query->get('error');
        $state = $request->query->get('state');

        if ($error || !$code) {
            return new RedirectResponse($adminUrl . '?calendar_auth_error=' . ($error ?? 'missing_code'));
        }

        $stateData = json_decode(base64_decode($state ?? ''), true);
        $userId = $stateData['user_id'] ?? null;

        if (!$userId) {
            return new RedirectResponse($adminUrl . '?calendar_auth_error=missing_user');
        }

        $callbackUri = str_replace(
            '/api/auth/google/callback',
            '/api/auth/google-calendar/callback',
            $this->googleRedirectUri
        );

        $tokenResponse = $this->httpClient->request('POST', 'https://oauth2.googleapis.com/token', [
            'body' => [
                'code' => $code,
                'client_id' => $this->googleClientId,
                'client_secret' => $this->googleClientSecret,
                'redirect_uri' => $callbackUri,
                'grant_type' => 'authorization_code',
            ],
        ]);

        if ($tokenResponse->getStatusCode() !== 200) {
            return new RedirectResponse($adminUrl . '?calendar_auth_error=token_exchange_failed');
        }

        $tokens = $tokenResponse->toArray();
        $user = $this->userRepository->find($userId);

        if (!$user) {
            return new RedirectResponse($adminUrl . '?calendar_auth_error=user_not_found');
        }

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

        return new RedirectResponse($adminUrl . '?calendar_auth=success');
    }
}

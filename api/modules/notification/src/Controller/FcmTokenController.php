<?php

declare(strict_types=1);

namespace Maggie\Notification\Controller;

use Maggie\Core\Entity\User;
use Maggie\Notification\Enum\DevicePlatform;
use Maggie\Notification\Message\RegisterDeviceTokenCommand;
use Maggie\Notification\Message\UnregisterDeviceTokenCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Where the app registers the FCM token of the device it runs on (MAG-26).
 *
 * Hand-written rather than an API resource: the token goes in, it never comes
 * back out, and the DELETE names it in the body — in a path it would land in
 * the access logs.
 */
final class FcmTokenController
{
    /** FCM tokens run to ~160 characters today; the column leaves room. */
    private const MAX_TOKEN_LENGTH = 512;

    private const MAX_DEVICE_NAME_LENGTH = 100;

    public function __construct(
        private readonly Security $security,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/fcm_tokens', name: 'api_fcm_token_register', methods: ['POST'])]
    public function register(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $body = $this->decode($request);
        if (\is_string($body)) {
            return new JsonResponse(['error' => $body], Response::HTTP_BAD_REQUEST);
        }

        $token = $this->token($body);
        if (null !== ($error = $token['error'] ?? null)) {
            return new JsonResponse(['error' => $error], Response::HTTP_BAD_REQUEST);
        }

        $platform = $body['platform'] ?? DevicePlatform::Android->value;
        if (!\is_string($platform) || null === DevicePlatform::tryFrom($platform)) {
            $allowed = implode(', ', array_map(static fn (DevicePlatform $p) => $p->value, DevicePlatform::cases()));

            return new JsonResponse(['error' => "platform must be one of: {$allowed}"], Response::HTTP_BAD_REQUEST);
        }

        $deviceName = $body['deviceName'] ?? null;
        if (null !== $deviceName && !\is_string($deviceName)) {
            return new JsonResponse(['error' => 'deviceName must be a string'], Response::HTTP_BAD_REQUEST);
        }
        $deviceName = null === $deviceName ? null : mb_substr(trim($deviceName), 0, self::MAX_DEVICE_NAME_LENGTH);

        try {
            $this->bus->dispatch(new RegisterDeviceTokenCommand(
                userId: (string) $user->getId(),
                token: $token['value'],
                platform: $platform,
                deviceName: '' === $deviceName ? null : $deviceName,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(null, Response::HTTP_NO_CONTENT);
    }

    #[Route('/api/fcm_tokens', name: 'api_fcm_token_unregister', methods: ['DELETE'])]
    public function unregister(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $body = $this->decode($request);
        if (\is_string($body)) {
            return new JsonResponse(['error' => $body], Response::HTTP_BAD_REQUEST);
        }

        $token = $this->token($body);
        if (null !== ($error = $token['error'] ?? null)) {
            return new JsonResponse(['error' => $error], Response::HTTP_BAD_REQUEST);
        }

        $this->bus->dispatch(new UnregisterDeviceTokenCommand(
            userId: (string) $user->getId(),
            token: $token['value'],
        ));

        return new JsonResponse(null, Response::HTTP_NO_CONTENT);
    }

    /** @return array<string, mixed>|string the decoded object, or why it is refused */
    private function decode(Request $request): array|string
    {
        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return 'A JSON object is expected';
        }

        return \is_array($body) && !array_is_list($body) ? $body : 'A JSON object is expected';
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array{value: string}|array{error: string}
     */
    private function token(array $body): array
    {
        $token = $body['token'] ?? null;
        if (!\is_string($token) || '' === trim($token)) {
            return ['error' => 'token is required'];
        }

        $token = trim($token);
        if (\strlen($token) > self::MAX_TOKEN_LENGTH) {
            return ['error' => sprintf('token must be at most %d characters', self::MAX_TOKEN_LENGTH)];
        }

        return ['value' => $token];
    }
}

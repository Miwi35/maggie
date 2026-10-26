<?php

declare(strict_types=1);

namespace Maggie\Core\Controller;

use Maggie\Core\Entity\User;
use Maggie\Core\Message\ParkCarCommand;
use Maggie\Core\Message\ReportUserLocationCommand;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * Where the owner is and where the car is parked, as the phone reports them
 * (MAG-650). Hand-written: an API Platform write on an identifier-less /me
 * operation builds a fresh object (MeOperationContractTest). Each PUT replaces
 * the user's last value; there is no history and nothing to read back.
 */
final class UserLocationController
{
    /** An ISO 8601 date-time with its offset: a position without one would be hours off. */
    private const DATE_TIME = '/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,6})?(Z|[+-]\d{2}:\d{2})$/';

    public function __construct(
        private readonly Security $security,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/me/location', name: 'api_me_location_update', methods: ['PUT'])]
    public function location(Request $request): Response
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $body = $this->body($request);
            $command = new ReportUserLocationCommand(
                userId: (string) $user->getId(),
                latitude: $this->coordinate($body, 'latitude', 90),
                longitude: $this->coordinate($body, 'longitude', 180),
                accuracy: $this->accuracy($body),
                capturedAt: $this->dateTime($body, 'capturedAt'),
            );
        } catch (InvalidLocationField $e) {
            return $e->response();
        }

        $this->bus->dispatch($command);

        return new Response(null, Response::HTTP_NO_CONTENT);
    }

    #[Route('/api/me/car', name: 'api_me_car_update', methods: ['PUT'])]
    public function car(Request $request): Response
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $body = $this->body($request);
            $command = new ParkCarCommand(
                userId: (string) $user->getId(),
                latitude: $this->coordinate($body, 'latitude', 90),
                longitude: $this->coordinate($body, 'longitude', 180),
                parkedAt: $this->dateTime($body, 'parkedAt'),
            );
        } catch (InvalidLocationField $e) {
            return $e->response();
        }

        $this->bus->dispatch($command);

        return new Response(null, Response::HTTP_NO_CONTENT);
    }

    /** @return array<mixed> */
    private function body(Request $request): array
    {
        try {
            $body = json_decode($request->getContent(), true, 16, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }

        if (!\is_array($body) || array_is_list($body) && [] !== $body) {
            throw new InvalidLocationField(null, 'A JSON object is expected');
        }

        return $body;
    }

    /** @param array<mixed> $body */
    private function coordinate(array $body, string $field, int $bound): float
    {
        $value = $this->number($body, $field);
        if ($value < -$bound || $value > $bound) {
            throw new InvalidLocationField($field, "{$field} must be between -{$bound} and {$bound}");
        }

        return $value;
    }

    /** @param array<mixed> $body */
    private function accuracy(array $body): float
    {
        $value = $this->number($body, 'accuracy');
        if ($value < 0) {
            throw new InvalidLocationField('accuracy', 'accuracy must be a positive number of metres');
        }

        return $value;
    }

    /** @param array<mixed> $body */
    private function number(array $body, string $field): float
    {
        if (!\array_key_exists($field, $body)) {
            throw new InvalidLocationField($field, "{$field} is required");
        }
        $value = $body[$field];
        if (!\is_int($value) && !\is_float($value)) {
            throw new InvalidLocationField($field, "{$field} must be a number");
        }

        return (float) $value;
    }

    /** @param array<mixed> $body */
    private function dateTime(array $body, string $field): \DateTimeImmutable
    {
        if (!\array_key_exists($field, $body)) {
            throw new InvalidLocationField($field, "{$field} is required");
        }
        $value = $body[$field];
        $invalid = new InvalidLocationField($field, "{$field} must be an ISO 8601 date-time with an offset");
        if (!\is_string($value) || 1 !== preg_match(self::DATE_TIME, $value)) {
            throw $invalid;
        }

        try {
            $dateTime = new \DateTimeImmutable($value);
        } catch (\Exception) {
            throw $invalid;
        }
        // PHP rolls 2026-02-30 over to March: the date must read back as it was sent.
        if (substr($value, 0, 19) !== $dateTime->format('Y-m-d\TH:i:s')) {
            throw $invalid;
        }

        return $dateTime;
    }
}

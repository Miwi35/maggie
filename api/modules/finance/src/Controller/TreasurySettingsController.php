<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Message\UpdateTreasurySettingsCommand;
use Maggie\Finance\UseCase\GetSalaryDelay;
use Maggie\Finance\UseCase\GetTreasurySettings;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The treasury settings, created on first read, and the salary delay they
 * make readable. Written here and not through an API Platform `Patch` on an
 * identifier-less path, which would build a fresh entity (MeOperationContractTest).
 */
final class TreasurySettingsController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetTreasurySettings $getTreasurySettings,
        private readonly GetSalaryDelay $getSalaryDelay,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/finance/treasury-settings', name: 'api_finance_treasury_settings_read', methods: ['GET'])]
    public function read(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        return new JsonResponse($this->describe($user));
    }

    #[Route('/api/finance/treasury-settings', name: 'api_finance_treasury_settings_update', methods: ['PATCH'])]
    public function update(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $body = json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }

        if (!\is_array($body) || !\array_key_exists('salaryExpectedDay', $body)) {
            return new JsonResponse(['error' => 'Provide salaryExpectedDay (1 to 31, or null to forget it)'], Response::HTTP_BAD_REQUEST);
        }

        $day = $body['salaryExpectedDay'];
        if (null !== $day && (!\is_int($day) || $day < 1 || $day > 31)) {
            return new JsonResponse(['error' => 'salaryExpectedDay must be an integer from 1 to 31, or null'], Response::HTTP_BAD_REQUEST);
        }

        $settings = $this->getTreasurySettings->forUser($user);

        try {
            $this->bus->dispatch(new UpdateTreasurySettingsCommand(
                userId: (string) $user->getId(),
                treasurySettingsId: (string) $settings->getId(),
                salaryExpectedDay: $day,
            ));
        } catch (HandlerFailedException $e) {
            return new JsonResponse(['error' => ($e->getPrevious() ?? $e)->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['success' => true] + $this->describe($user));
    }

    /** @return array<string, mixed> */
    private function describe(User $user): array
    {
        return GetTreasurySettings::describe($this->getTreasurySettings->forUser($user))
            + ['salaryDelay' => $this->getSalaryDelay->execute($user)];
    }
}

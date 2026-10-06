<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Message\UpdateSafetyCushionCommand;
use Maggie\Finance\Repository\SafetyCushionRepository;
use Maggie\Finance\UseCase\GetCushionStatus;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Routing\Attribute\Route;

final class ConfigureCushionController
{
    private const FIELDS = [
        'targetMonths',
        'monthlyNetIncomeCents',
        'rechargeCapCents',
        'rechargeTargetMonths',
    ];

    public function __construct(
        private readonly Security $security,
        private readonly SafetyCushionRepository $cushionRepository,
        private readonly GetCushionStatus $getCushionStatus,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/finance/cushion-config', name: 'api_finance_cushion_config', methods: ['PATCH'])]
    public function __invoke(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $content = $request->getContent();
        $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);

        $given = array_intersect_key($body, array_flip(self::FIELDS));
        if ([] === $given) {
            return new JsonResponse(
                ['error' => 'Provide at least one of: '.implode(', ', self::FIELDS)],
                Response::HTTP_BAD_REQUEST,
            );
        }

        foreach ($given as $name => $value) {
            if (!\is_int($value)) {
                return new JsonResponse(
                    ['error' => "{$name} must be an integer"],
                    Response::HTTP_BAD_REQUEST,
                );
            }
        }

        // Reading the status creates the cushion on first use.
        $this->getCushionStatus->execute($user);
        $cushion = $this->cushionRepository->findOneByUser($user);

        try {
            $this->bus->dispatch(new UpdateSafetyCushionCommand(
                userId: (string) $user->getId(),
                safetyCushionId: (string) $cushion->getId(),
                targetMonths: $given['targetMonths'] ?? null,
                monthlyNetIncomeCents: $given['monthlyNetIncomeCents'] ?? null,
                rechargeCapCents: $given['rechargeCapCents'] ?? null,
                rechargeTargetMonths: $given['rechargeTargetMonths'] ?? null,
            ));
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse(['success' => true] + $this->getCushionStatus->execute($user));
    }
}

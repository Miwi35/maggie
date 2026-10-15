<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Message\AttachRecurringOperationsCommand;
use Maggie\Finance\UseCase\ApplyCategorizationRules;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;

/**
 * The preview and the catch-up of `/api/operations`, for an operation with or
 * without recurrence: one door in front of the two engines a series and a
 * rule have kept (MAG-478).
 */
final class OperationsController
{
    public function __construct(
        private readonly Security $security,
        private readonly PreviewCategorizationRuleController $previewWithoutRecurrence,
        private readonly PreviewRecurringOperationController $previewSeries,
        private readonly ApplyCategorizationRules $applyCategorizationRules,
        private readonly MessageBusInterface $bus,
    ) {
    }

    /**
     * What an operation would catch while it is still being written. A body
     * with a `referenceAmountCents` is a series' (which lines it would attach);
     * without one, an operation without recurrence's (which lines it would file).
     * Nothing is written.
     */
    #[Route('/api/finance/operations/preview', name: 'api_finance_operation_preview', methods: ['POST'])]
    public function preview(Request $request): JsonResponse
    {
        if (!$this->security->getUser() instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $content = $request->getContent();
            $body = '' === $content ? [] : json_decode($content, true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return new JsonResponse(['error' => 'The body is not valid JSON.'], Response::HTTP_BAD_REQUEST);
        }

        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object.'], Response::HTTP_BAD_REQUEST);
        }

        return isset($body['referenceAmountCents'])
            ? ($this->previewSeries)($request)
            : ($this->previewWithoutRecurrence)($request);
    }

    /**
     * Catch-up pass over the whole history: the series attach the occurrences
     * they recognise first, then the operations without recurrence file what
     * is still unqualified — the order a new line goes through.
     */
    #[Route('/api/finance/operations/apply', name: 'api_finance_operations_apply', methods: ['POST'])]
    public function apply(): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $attached = $this->bus->dispatch(new AttachRecurringOperationsCommand((string) $user->getId()))
            ->last(HandledStamp::class)?->getResult();
        $categorized = $this->applyCategorizationRules->execute($user);

        return new JsonResponse([
            'success' => true,
            'attached' => \is_array($attached) ? $attached['attached'] : 0,
            'proposed' => \is_array($attached) ? $attached['proposed'] : 0,
            'categorized' => $categorized['categorized'],
            'scanned' => $categorized['scanned'],
        ]);
    }
}

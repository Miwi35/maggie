<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\ReviewProgress;
use Maggie\Finance\Message\SetReviewProgressCommand;
use Maggie\Finance\Service\FinanceReviewAppointment;
use Maggie\Finance\UseCase\GetReviewProgress;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * Where the user stands in the review of a month (MAG-456), so the activity
 * resumes there on any device: the step reached and the transactions handled.
 */
final class ReviewProgressController
{
    /** At most this many transactions handled in one call. */
    public const MAX_HANDLED = 200;

    private const MONTH = '/^\d{4}-(0[1-9]|1[0-2])$/';

    public function __construct(
        private readonly Security $security,
        private readonly GetReviewProgress $getReviewProgress,
        private readonly FinanceReviewAppointment $appointment,
        private readonly MessageBusInterface $bus,
    ) {
    }

    #[Route('/api/finance/review/progress', name: 'api_finance_review_progress_read', methods: ['GET'])]
    public function read(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $month = $request->query->get('month', (new \DateTimeImmutable('today'))->format('Y-m'));
        if (1 !== preg_match(self::MONTH, $month)) {
            return new JsonResponse(['error' => 'month must be a month as YYYY-MM'], Response::HTTP_BAD_REQUEST);
        }

        $progress = $this->getReviewProgress->execute($user, new \DateTimeImmutable($month.'-01'));
        $this->appointment->ensure($user);

        return new JsonResponse($progress);
    }

    /** Body: `month` (YYYY-MM), `step` (optional), `handledTransactionIds` (optional, added to those stored). */
    #[Route('/api/finance/review/progress', name: 'api_finance_review_progress_update', methods: ['PUT'])]
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
        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'Send a JSON object with month, and step or handledTransactionIds.'], Response::HTTP_BAD_REQUEST);
        }

        $month = $body['month'] ?? null;
        if (!\is_string($month) || 1 !== preg_match(self::MONTH, $month)) {
            return new JsonResponse(['error' => 'month must be a month as YYYY-MM'], Response::HTTP_BAD_REQUEST);
        }

        $step = $body['step'] ?? null;
        if (null !== $step && !\is_string($step)) {
            return new JsonResponse(['error' => 'step must be a string'], Response::HTTP_BAD_REQUEST);
        }

        $ids = $body['handledTransactionIds'] ?? [];
        $idsError = \sprintf('handledTransactionIds must be a list of at most %d transaction ids', self::MAX_HANDLED);
        if (!\is_array($ids) || !array_is_list($ids) || \count($ids) > self::MAX_HANDLED) {
            return new JsonResponse(['error' => $idsError], Response::HTTP_BAD_REQUEST);
        }
        $handled = [];
        foreach ($ids as $id) {
            if (!\is_string($id) || !Ulid::isValid($id)) {
                return new JsonResponse(['error' => $idsError], Response::HTTP_BAD_REQUEST);
            }
            $handled[] = Ulid::fromString($id)->toBase32();
        }

        try {
            $progress = $this->bus->dispatch(new SetReviewProgressCommand(
                userId: (string) $user->getId(),
                month: new \DateTimeImmutable($month.'-01'),
                step: $step,
                handledTransactionIds: $handled,
            ))->last(HandledStamp::class)?->getResult();
        } catch (HandlerFailedException $e) {
            // A refused step or id is the caller's; anything else is ours, and not theirs to read.
            $cause = $e->getPrevious() ?? $e;
            if (!$cause instanceof \DomainException) {
                throw $e;
            }

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }
        \assert($progress instanceof ReviewProgress);

        return new JsonResponse(GetReviewProgress::describe($progress));
    }
}

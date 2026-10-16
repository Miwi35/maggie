<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\UseCase\ControlRecognisedTransactions;
use Maggie\Finance\UseCase\GetRecognisedToControl;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/** « Reconnues à contrôler »: the lines an operation qualified, by operation, and the gesture that checks them. */
final class RecognisedToControlController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetRecognisedToControl $getRecognisedToControl,
        private readonly ControlRecognisedTransactions $controlRecognised,
    ) {
    }

    #[Route('/api/finance/review/recognised', name: 'api_finance_review_recognised', methods: ['GET'])]
    public function list(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $month = $request->query->get('month', (new \DateTimeImmutable('today'))->format('Y-m'));
        if (1 !== preg_match('/^\d{4}-(0[1-9]|1[0-2])$/', $month)) {
            return new JsonResponse(['error' => 'month must be a month as YYYY-MM'], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->getRecognisedToControl->execute($user, new \DateTimeImmutable($month.'-01')));
    }

    /** Body: `transactionIds` (1 to 200 ulids), `satisfaction` (`keep`, optional). */
    #[Route('/api/finance/review/controls', name: 'api_finance_review_controls', methods: ['POST'])]
    public function control(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        try {
            $body = json_decode($request->getContent(), true, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }
        $ids = \is_array($body) ? ($body['transactionIds'] ?? null) : null;
        if (!\is_array($ids) || !array_is_list($ids) || [] === $ids || \count($ids) > 200) {
            return new JsonResponse(['error' => 'transactionIds must be a list of 1 to 200 transaction ids'], Response::HTTP_BAD_REQUEST);
        }
        foreach ($ids as $id) {
            if (!\is_string($id) || !Ulid::isValid($id)) {
                return new JsonResponse(['error' => 'transactionIds must be a list of 1 to 200 transaction ids'], Response::HTTP_BAD_REQUEST);
            }
        }
        $satisfaction = $body['satisfaction'] ?? null;
        if (null !== $satisfaction && 'keep' !== $satisfaction) {
            return new JsonResponse(['error' => 'satisfaction can only be keep'], Response::HTTP_BAD_REQUEST);
        }

        try {
            return new JsonResponse($this->controlRecognised->execute($user, $ids, 'keep' === $satisfaction));
        } catch (\InvalidArgumentException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_NOT_FOUND);
        }
    }
}

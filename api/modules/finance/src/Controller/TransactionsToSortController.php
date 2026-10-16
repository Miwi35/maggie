<?php

declare(strict_types=1);

namespace Maggie\Finance\Controller;

use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\GetTransactionsToSort;
use Maggie\Finance\UseCase\SortTransaction;
use Symfony\Bundle\SecurityBundle\Security;
use Symfony\Component\HttpFoundation\JsonResponse;
use Symfony\Component\HttpFoundation\Request;
use Symfony\Component\HttpFoundation\Response;
use Symfony\Component\Messenger\Exception\HandlerFailedException;
use Symfony\Component\Routing\Attribute\Route;
use Symfony\Component\Uid\Ulid;

/**
 * The « À trier » pile, one line at a time: read it with the suggestion each
 * line would get and why, then answer a line with a category or « it is a
 * transfer between my accounts ».
 *
 * A dedicated endpoint rather than a PATCH of the transaction: « always for
 * this merchant » writes a rule next to the line, and the answer tells how
 * many other lines that filed.
 */
final class TransactionsToSortController
{
    public function __construct(
        private readonly Security $security,
        private readonly GetTransactionsToSort $getTransactionsToSort,
        private readonly SortTransaction $sortTransaction,
        private readonly TransactionRepository $transactionRepository,
    ) {
    }

    #[Route('/api/finance/transactions/to-sort', name: 'api_finance_transactions_to_sort', methods: ['GET'])]
    public function list(Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $limit = $request->query->get('limit');
        if (null !== $limit && (!ctype_digit((string) $limit) || (int) $limit < 1)) {
            return new JsonResponse(['error' => 'limit must be a positive integer'], Response::HTTP_BAD_REQUEST);
        }

        $order = $request->query->get('order', 'newest');
        if (!\in_array($order, ['newest', 'oldest'], true)) {
            return new JsonResponse(['error' => 'order must be newest or oldest'], Response::HTTP_BAD_REQUEST);
        }

        return new JsonResponse($this->getTransactionsToSort->execute(
            $user,
            null === $limit ? GetTransactionsToSort::DEFAULT_LIMIT : (int) $limit,
            'oldest' === $order,
        ));
    }

    #[Route('/api/finance/transactions/{id}/sort', name: 'api_finance_transaction_sort', methods: ['POST'])]
    public function sort(string $id, Request $request): JsonResponse
    {
        $user = $this->security->getUser();
        if (!$user instanceof User) {
            return new JsonResponse(['error' => 'Unauthorized'], Response::HTTP_UNAUTHORIZED);
        }

        $transaction = Ulid::isValid($id)
            ? $this->transactionRepository->findOneBy(['id' => $id, 'user' => $user])
            : null;
        if (null === $transaction) {
            return new JsonResponse(['error' => 'Transaction not found'], Response::HTTP_NOT_FOUND);
        }

        try {
            $body = '' === $request->getContent() ? [] : json_decode($request->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            $body = null;
        }
        if (!\is_array($body)) {
            return new JsonResponse(['error' => 'The body must be a JSON object'], Response::HTTP_BAD_REQUEST);
        }

        $categoryId = $body['categoryId'] ?? null;
        $transfer = $body['internalTransfer'] ?? null;
        $always = $body['alwaysForMerchant'] ?? false;

        if ((null === $categoryId) === (null === $transfer)) {
            return new JsonResponse(
                ['error' => 'Give either categoryId or internalTransfer, not both and not neither'],
                Response::HTTP_BAD_REQUEST,
            );
        }
        if (!\is_bool($always)) {
            return new JsonResponse(['error' => 'alwaysForMerchant must be a boolean'], Response::HTTP_BAD_REQUEST);
        }

        try {
            if (null !== $categoryId) {
                if (!\is_string($categoryId)) {
                    return new JsonResponse(['error' => 'categoryId must be a category id'], Response::HTTP_BAD_REQUEST);
                }
                $result = $this->sortTransaction->toCategory($user, $transaction, $categoryId, $always);

                return new JsonResponse([
                    'success' => true,
                    'transactionId' => $id,
                    'categoryId' => $categoryId,
                    'rule' => $this->serializeRule($result['rule']),
                    'ruleCreated' => $result['ruleCreated'],
                    'alsoSorted' => $result['alsoSorted'],
                    'remaining' => $result['remaining'],
                ]);
            }

            if ($always) {
                return new JsonResponse(['error' => 'alwaysForMerchant goes with a category'], Response::HTTP_BAD_REQUEST);
            }
            $counterpartId = \is_array($transfer) ? ($transfer['counterpartId'] ?? null) : false;
            if (false === $counterpartId || (null !== $counterpartId && !\is_string($counterpartId))) {
                return new JsonResponse(
                    ['error' => 'internalTransfer must be an object with an optional counterpartId'],
                    Response::HTTP_BAD_REQUEST,
                );
            }
            $result = $this->sortTransaction->toInternalTransfer($user, $transaction, $counterpartId);

            return new JsonResponse([
                'success' => true,
                'transactionId' => $id,
                'rule' => null,
                'ruleCreated' => false,
                'alsoSorted' => 0,
                'remaining' => $result['remaining'],
            ]);
        } catch (\DomainException $e) {
            return new JsonResponse(['error' => $e->getMessage()], Response::HTTP_BAD_REQUEST);
        } catch (HandlerFailedException $e) {
            $cause = $e->getPrevious() ?? $e;

            return new JsonResponse(['error' => $cause->getMessage()], Response::HTTP_BAD_REQUEST);
        }
    }

    /** @return array{id: string, labelPattern: ?string}|null */
    private function serializeRule(?RecurringOperation $rule): ?array
    {
        return null === $rule ? null : ['id' => (string) $rule->getId(), 'labelPattern' => $rule->getLabelPattern()];
    }
}
